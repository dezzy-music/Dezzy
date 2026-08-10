-- ============================================================================
--  Dezzy · Red social musical — API pública (RPC)
--  Migración 002 · funciones `public.dezzy_*`
-- ============================================================================
--
--  Todas las funciones son SECURITY DEFINER y viven en `public` (el único
--  schema publicado por PostgREST). El prefijo `dezzy_` evita cualquier colisión
--  con las funciones del otro producto que comparte el proyecto.
--
--  Regla de oro de seguridad: NINGUNA función confía en un id de usuario que
--  venga del cliente. Siempre se usa auth.uid(). Un cliente manipulado no puede
--  publicar, dar like ni leer en nombre de otro.
--
--  `set search_path` está fijado en todas para evitar secuestro de search_path,
--  que es el riesgo clásico de SECURITY DEFINER.
-- ============================================================================

begin;

-- ============================================================================
--  Helpers internos
-- ============================================================================

-- ¿Son amigos aceptados? Reusa `public.friends` del ecosistema existente.
create or replace function dezzy._are_friends(a uuid, b uuid)
returns boolean language sql stable security definer set search_path = public, dezzy as $$
    select exists (
        select 1 from public.friends f
         where f.status = 'accepted'
           and ((f.user_id_1 = a and f.user_id_2 = b)
             or (f.user_id_1 = b and f.user_id_2 = a))
    );
$$;

-- ¿Puede `viewer` ver la actividad de `owner`, según la visibilidad elegida?
create or replace function dezzy._can_see_activity(viewer uuid, owner uuid)
returns boolean language sql stable security definer set search_path = public, dezzy as $$
    select case
        when viewer = owner then true
        else coalesce((
            select case p.visibility
                     when 'public'  then true
                     when 'friends' then dezzy._are_friends(viewer, owner)
                     else false
                   end
              from dezzy.presence p where p.user_id = owner
        ), false)
    end;
$$;

-- ============================================================================
--  PRESENCIA / ACTIVIDAD EN VIVO
-- ============================================================================

-- Publica lo que estoy escuchando. El cliente la llama cada ~20 s mientras suena
-- y una vez al pausar. Pasar p_video_id = NULL limpia la presencia.
create or replace function public.dezzy_update_presence(
    p_video_id    text default null,
    p_title       text default null,
    p_artist      text default null,
    p_album       text default null,
    p_artwork_url text default null,
    p_is_playing  boolean default false,
    p_position_ms bigint default 0,
    p_duration_ms bigint default 0,
    p_lat         double precision default null,
    p_lng         double precision default null,
    p_visibility  text default null,
    p_device_name text default null
) returns void language plpgsql security definer set search_path = dezzy, public as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;

    insert into dezzy.presence as pr (
        user_id, video_id, title, artist, album, artwork_url,
        is_playing, position_ms, duration_ms, lat, lng,
        visibility, device_name, updated_at
    ) values (
        v_uid, p_video_id, p_title, p_artist, p_album, p_artwork_url,
        p_is_playing, p_position_ms, p_duration_ms, p_lat, p_lng,
        coalesce(p_visibility, 'friends'), p_device_name, now()
    )
    on conflict (user_id) do update set
        video_id    = excluded.video_id,
        title       = excluded.title,
        artist      = excluded.artist,
        album       = excluded.album,
        artwork_url = excluded.artwork_url,
        is_playing  = excluded.is_playing,
        position_ms = excluded.position_ms,
        duration_ms = excluded.duration_ms,
        lat         = excluded.lat,
        lng         = excluded.lng,
        -- La visibilidad solo cambia si se manda explícitamente: los pings
        -- periódicos no deben pisar la preferencia del usuario.
        visibility  = coalesce(p_visibility, pr.visibility),
        device_name = excluded.device_name,
        updated_at  = now();
end;
$$;

-- Panel "Amigos escuchando" (el lateral de Spotify).
-- Solo devuelve gente cuya visibilidad me lo permite y con actividad reciente.
create or replace function public.dezzy_friend_activity(p_max_age_seconds integer default 900)
returns table (
    user_id     uuid,
    name        text,
    username    text,
    avatar_url  text,
    video_id    text,
    title       text,
    artist      text,
    artwork_url text,
    is_playing  boolean,
    updated_at  timestamptz
) language sql stable security definer set search_path = dezzy, public as $$
    select pr.user_id,
           coalesce(pf.name, 'Usuario')::text,
           pf.username::text,
           pf.avatar_url::text,
           pr.video_id, pr.title, pr.artist, pr.artwork_url,
           pr.is_playing, pr.updated_at
      from dezzy.presence pr
      join public.profiles pf on pf.id = pr.user_id
     where pr.user_id <> auth.uid()
       and pr.video_id is not null
       and pr.updated_at > now() - make_interval(secs => greatest(p_max_age_seconds, 60))
       and dezzy._can_see_activity(auth.uid(), pr.user_id)
     order by pr.is_playing desc, pr.updated_at desc
     limit 100;
$$;

-- Music Map: presencias públicas y geolocalizadas.
-- Solo entra quien eligió visibilidad 'public' Y compartió ubicación.
create or replace function public.dezzy_map_presences(p_max_age_seconds integer default 900)
returns table (
    user_id     uuid,
    name        text,
    avatar_url  text,
    title       text,
    artist      text,
    artwork_url text,
    lat         double precision,
    lng         double precision
) language sql stable security definer set search_path = dezzy, public as $$
    select pr.user_id,
           coalesce(pf.name, 'Usuario')::text,
           pf.avatar_url::text,
           pr.title, pr.artist, pr.artwork_url,
           pr.lat, pr.lng
      from dezzy.presence pr
      join public.profiles pf on pf.id = pr.user_id
     where pr.visibility = 'public'
       and pr.lat is not null and pr.lng is not null
       and pr.video_id is not null
       and pr.updated_at > now() - make_interval(secs => greatest(p_max_age_seconds, 60))
     order by pr.updated_at desc
     limit 2000;
$$;

-- ============================================================================
--  SEGUIDORES
-- ============================================================================

create or replace function public.dezzy_follow(p_user_id uuid, p_follow boolean default true)
returns void language plpgsql security definer set search_path = dezzy, public as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;
    if v_uid = p_user_id then
        raise exception 'No puedes seguirte a ti mismo';
    end if;

    if p_follow then
        insert into dezzy.follows (follower_id, followee_id)
        values (v_uid, p_user_id)
        on conflict do nothing;
    else
        delete from dezzy.follows
         where follower_id = v_uid and followee_id = p_user_id;
    end if;
end;
$$;

create or replace function public.dezzy_follow_counts(p_user_id uuid)
returns table (followers bigint, following bigint, i_follow boolean)
language sql stable security definer set search_path = dezzy, public as $$
    select (select count(*) from dezzy.follows where followee_id = p_user_id),
           (select count(*) from dezzy.follows where follower_id = p_user_id),
           exists (select 1 from dezzy.follows
                    where follower_id = auth.uid() and followee_id = p_user_id);
$$;

-- ============================================================================
--  FEED MUSICAL
-- ============================================================================

create or replace function public.dezzy_create_post(
    p_entity_type     text,
    p_entity_id       text,
    p_entity_title    text,
    p_entity_subtitle text default null,
    p_artwork_url     text default null,
    p_caption         text default null
) returns uuid language plpgsql security definer set search_path = dezzy, public as $$
declare
    v_uid uuid := auth.uid();
    v_id  uuid;
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;

    insert into dezzy.posts (user_id, entity_type, entity_id, entity_title,
                             entity_subtitle, artwork_url, caption)
    values (v_uid, p_entity_type, p_entity_id, p_entity_title,
            p_entity_subtitle, p_artwork_url, nullif(btrim(p_caption), ''))
    returning post_id into v_id;

    return v_id;
end;
$$;

-- Feed cronológico de a quién sigo + mis amigos + yo. Paginado por cursor
-- (created_at) en vez de OFFSET: estable aunque entren posts nuevos mientras
-- el usuario hace scroll.
create or replace function public.dezzy_get_feed(
    p_before timestamptz default null,
    p_limit  integer default 20
) returns table (
    post_id         uuid,
    user_id         uuid,
    author_name     text,
    author_username text,
    author_avatar   text,
    entity_type     text,
    entity_id       text,
    entity_title    text,
    entity_subtitle text,
    artwork_url     text,
    caption         text,
    likes_count     integer,
    comments_count  integer,
    liked_by_me     boolean,
    created_at      timestamptz
) language sql stable security definer set search_path = dezzy, public as $$
    select p.post_id, p.user_id,
           coalesce(pf.name, 'Usuario')::text,
           pf.username::text,
           pf.avatar_url::text,
           p.entity_type, p.entity_id, p.entity_title, p.entity_subtitle,
           p.artwork_url, p.caption, p.likes_count, p.comments_count,
           exists (select 1 from dezzy.post_likes l
                    where l.post_id = p.post_id and l.user_id = auth.uid()),
           p.created_at
      from dezzy.posts p
      join public.profiles pf on pf.id = p.user_id
     where (p_before is null or p.created_at < p_before)
       and (
             p.user_id = auth.uid()
          or exists (select 1 from dezzy.follows f
                      where f.follower_id = auth.uid() and f.followee_id = p.user_id)
          or dezzy._are_friends(auth.uid(), p.user_id)
       )
     order by p.created_at desc
     limit least(greatest(p_limit, 1), 50);
$$;

-- Feed de descubrimiento: lo más gustado de las últimas 48 h, de cualquiera.
create or replace function public.dezzy_get_discover(p_limit integer default 20)
returns table (
    post_id         uuid,
    user_id         uuid,
    author_name     text,
    author_username text,
    author_avatar   text,
    entity_type     text,
    entity_id       text,
    entity_title    text,
    entity_subtitle text,
    artwork_url     text,
    caption         text,
    likes_count     integer,
    comments_count  integer,
    liked_by_me     boolean,
    created_at      timestamptz
) language sql stable security definer set search_path = dezzy, public as $$
    select p.post_id, p.user_id,
           coalesce(pf.name, 'Usuario')::text,
           pf.username::text,
           pf.avatar_url::text,
           p.entity_type, p.entity_id, p.entity_title, p.entity_subtitle,
           p.artwork_url, p.caption, p.likes_count, p.comments_count,
           exists (select 1 from dezzy.post_likes l
                    where l.post_id = p.post_id and l.user_id = auth.uid()),
           p.created_at
      from dezzy.posts p
      join public.profiles pf on pf.id = p.user_id
     where p.created_at > now() - interval '48 hours'
     order by p.likes_count desc, p.created_at desc
     limit least(greatest(p_limit, 1), 50);
$$;

create or replace function public.dezzy_toggle_like(p_post_id uuid)
returns boolean language plpgsql security definer set search_path = dezzy, public as $$
declare
    v_uid   uuid := auth.uid();
    v_liked boolean;
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;

    delete from dezzy.post_likes
     where post_id = p_post_id and user_id = v_uid;

    if found then
        return false;
    end if;

    insert into dezzy.post_likes (post_id, user_id) values (p_post_id, v_uid);
    return true;
end;
$$;

create or replace function public.dezzy_add_comment(p_post_id uuid, p_content text)
returns uuid language plpgsql security definer set search_path = dezzy, public as $$
declare
    v_uid uuid := auth.uid();
    v_id  uuid;
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;

    insert into dezzy.post_comments (post_id, user_id, content)
    values (p_post_id, v_uid, btrim(p_content))
    returning comment_id into v_id;

    return v_id;
end;
$$;

create or replace function public.dezzy_get_comments(p_post_id uuid, p_limit integer default 50)
returns table (
    comment_id  uuid,
    user_id     uuid,
    author_name text,
    author_avatar text,
    content     text,
    created_at  timestamptz
) language sql stable security definer set search_path = dezzy, public as $$
    select c.comment_id, c.user_id,
           coalesce(pf.name, 'Usuario')::text,
           pf.avatar_url::text,
           c.content, c.created_at
      from dezzy.post_comments c
      join public.profiles pf on pf.id = c.user_id
     where c.post_id = p_post_id
     order by c.created_at
     limit least(greatest(p_limit, 1), 200);
$$;

create or replace function public.dezzy_delete_post(p_post_id uuid)
returns void language plpgsql security definer set search_path = dezzy, public as $$
begin
    delete from dezzy.posts
     where post_id = p_post_id and user_id = auth.uid();
end;
$$;

-- ============================================================================
--  COMENTARIOS CON MARCA DE TIEMPO
-- ============================================================================

create or replace function public.dezzy_add_track_comment(
    p_video_id    text,
    p_position_ms integer,
    p_content     text
) returns uuid language plpgsql security definer set search_path = dezzy, public as $$
declare
    v_uid uuid := auth.uid();
    v_id  uuid;
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;

    insert into dezzy.track_comments (video_id, user_id, position_ms, content)
    values (p_video_id, v_uid, greatest(p_position_ms, 0), btrim(p_content))
    returning comment_id into v_id;

    return v_id;
end;
$$;

create or replace function public.dezzy_get_track_comments(p_video_id text)
returns table (
    comment_id  uuid,
    user_id     uuid,
    author_name text,
    author_avatar text,
    position_ms integer,
    content     text,
    created_at  timestamptz
) language sql stable security definer set search_path = dezzy, public as $$
    select c.comment_id, c.user_id,
           coalesce(pf.name, 'Usuario')::text,
           pf.avatar_url::text,
           c.position_ms, c.content, c.created_at
      from dezzy.track_comments c
      join public.profiles pf on pf.id = c.user_id
     where c.video_id = p_video_id
     order by c.position_ms
     limit 300;
$$;

-- ============================================================================
--  PERFIL MUSICAL
-- ============================================================================

-- Suma una escucha. Idempotente por (usuario, entidad): acumula play_count.
create or replace function public.dezzy_track_play(
    p_entity_type text,
    p_entity_id   text,
    p_entity_name text,
    p_artwork_url text default null
) returns void language plpgsql security definer set search_path = dezzy, public as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        return;  -- Sin sesión no se registra nada, pero no es un error para el cliente.
    end if;

    insert into dezzy.listening_stats (user_id, entity_type, entity_id, entity_name, artwork_url)
    values (v_uid, p_entity_type, p_entity_id, p_entity_name, p_artwork_url)
    on conflict (user_id, entity_type, entity_id) do update set
        play_count  = dezzy.listening_stats.play_count + 1,
        entity_name = excluded.entity_name,
        artwork_url = coalesce(excluded.artwork_url, dezzy.listening_stats.artwork_url),
        last_played = now();
end;
$$;

create or replace function public.dezzy_get_top(
    p_user_id     uuid,
    p_entity_type text default 'artist',
    p_limit       integer default 10
) returns table (
    entity_id   text,
    entity_name text,
    artwork_url text,
    play_count  integer
) language sql stable security definer set search_path = dezzy, public as $$
    select s.entity_id, s.entity_name, s.artwork_url, s.play_count
      from dezzy.listening_stats s
     where s.user_id = p_user_id
       and s.entity_type = p_entity_type
     order by s.play_count desc, s.last_played desc
     limit least(greatest(p_limit, 1), 50);
$$;

-- Perfil musical completo de un usuario, en una sola llamada.
create or replace function public.dezzy_get_profile(p_user_id uuid)
returns table (
    user_id     uuid,
    name        text,
    username    text,
    avatar_url  text,
    bio         text,
    followers   bigint,
    following   bigint,
    i_follow    boolean,
    is_friend   boolean,
    now_playing text,
    now_artist  text,
    now_artwork text
) language sql stable security definer set search_path = dezzy, public as $$
    select pf.id,
           coalesce(pf.name, 'Usuario')::text,
           pf.username::text,
           pf.avatar_url::text,
           pf.bio::text,
           (select count(*) from dezzy.follows where followee_id = pf.id),
           (select count(*) from dezzy.follows where follower_id = pf.id),
           exists (select 1 from dezzy.follows
                    where follower_id = auth.uid() and followee_id = pf.id),
           dezzy._are_friends(auth.uid(), pf.id),
           case when dezzy._can_see_activity(auth.uid(), pf.id)
                then (select title from dezzy.presence where user_id = pf.id
                       and updated_at > now() - interval '15 minutes') end,
           case when dezzy._can_see_activity(auth.uid(), pf.id)
                then (select artist from dezzy.presence where user_id = pf.id
                       and updated_at > now() - interval '15 minutes') end,
           case when dezzy._can_see_activity(auth.uid(), pf.id)
                then (select artwork_url from dezzy.presence where user_id = pf.id
                       and updated_at > now() - interval '15 minutes') end
      from public.profiles pf
     where pf.id = p_user_id;
$$;

-- ============================================================================
--  SINCRONIZACIÓN ENTRE DISPOSITIVOS
-- ============================================================================

create or replace function public.dezzy_save_playback_state(
    p_video_id    text,
    p_title       text default null,
    p_artist      text default null,
    p_artwork_url text default null,
    p_position_ms bigint default 0,
    p_queue       jsonb default null,
    p_device_name text default null
) returns void language plpgsql security definer set search_path = dezzy, public as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then return; end if;

    insert into dezzy.playback_state (user_id, video_id, title, artist, artwork_url,
                                      position_ms, queue_json, device_name, updated_at)
    values (v_uid, p_video_id, p_title, p_artist, p_artwork_url,
            p_position_ms, p_queue, p_device_name, now())
    on conflict (user_id) do update set
        video_id    = excluded.video_id,
        title       = excluded.title,
        artist      = excluded.artist,
        artwork_url = excluded.artwork_url,
        position_ms = excluded.position_ms,
        queue_json  = coalesce(excluded.queue_json, dezzy.playback_state.queue_json),
        device_name = excluded.device_name,
        updated_at  = now();
end;
$$;

create or replace function public.dezzy_get_playback_state()
returns table (
    video_id    text,
    title       text,
    artist      text,
    artwork_url text,
    position_ms bigint,
    queue_json  jsonb,
    device_name text,
    updated_at  timestamptz
) language sql stable security definer set search_path = dezzy, public as $$
    select s.video_id, s.title, s.artist, s.artwork_url, s.position_ms,
           s.queue_json, s.device_name, s.updated_at
      from dezzy.playback_state s
     where s.user_id = auth.uid();
$$;

-- ============================================================================
--  PERMISOS
-- ============================================================================
-- Solo usuarios autenticados. `anon` queda fuera a propósito: la red social
-- exige sesión (aunque sea anónima de Supabase, que ya emite un JWT válido).

do $$
declare fn text;
begin
    for fn in
        select p.oid::regprocedure::text
          from pg_proc p
          join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public'
           and p.proname like 'dezzy\_%'
    loop
        execute format('revoke all on function %s from public, anon', fn);
        execute format('grant execute on function %s to authenticated', fn);
    end loop;
end;
$$;

commit;
