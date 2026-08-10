-- ============================================================================
-- OpenTune / Dezzy — Backend del feed estilo Instagram (Supabase)
--
-- Aplicado el 2026-07-02 vía Management API. Idempotente.
--
-- 100% ADITIVO — NO toca la BD compartida:
--   • social_posts / social_stories YA existían con RLS correcto
--     ("Anyone can view", insert/delete own) y datos reales. NO se modifican.
--   • Solo se AÑADE la tabla post_likes (con su propio RLS) y funciones nuevas.
--   • Imágenes de posts/historias se suben al bucket 'avatars' existente, en la
--     carpeta {uid}/ del propio usuario (la política de avatars ya lo permite).
-- ============================================================================

-- Tabla de "me gusta" (evita likes duplicados).
create table if not exists public.post_likes (
    post_id uuid not null references public.social_posts(post_id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (post_id, user_id)
);
alter table public.post_likes enable row level security;
do $do$
begin
    if not exists (select 1 from pg_policies where schemaname='public' and tablename='post_likes' and policyname='post_likes_select_own') then
        create policy post_likes_select_own on public.post_likes for select to authenticated using (user_id = auth.uid());
    end if;
    if not exists (select 1 from pg_policies where schemaname='public' and tablename='post_likes' and policyname='post_likes_insert_own') then
        create policy post_likes_insert_own on public.post_likes for insert to authenticated with check (user_id = auth.uid());
    end if;
    if not exists (select 1 from pg_policies where schemaname='public' and tablename='post_likes' and policyname='post_likes_delete_own') then
        create policy post_likes_delete_own on public.post_likes for delete to authenticated using (user_id = auth.uid());
    end if;
end
$do$;

-- Feed: posts + autor (de profiles) + si el usuario actual le dio like.
create or replace function public.get_feed(p_limit int default 30, p_before timestamptz default null)
returns table (post_id uuid, user_id uuid, author_name text, author_username text, author_avatar text,
               content text, image_url text, likes_count int, liked_by_me boolean, created_at timestamptz)
language sql security definer set search_path=public as $fn$
    select sp.post_id, sp.user_id, pr.name, pr.username, pr.avatar_url,
           sp.content, sp.image_url, coalesce(sp.likes_count,0)::int,
           exists(select 1 from public.post_likes pl where pl.post_id=sp.post_id and pl.user_id=auth.uid()),
           sp.created_at
    from public.social_posts sp
    join public.profiles pr on pr.id = sp.user_id
    where (p_before is null or sp.created_at < p_before)
    order by sp.created_at desc
    limit greatest(1, least(coalesce(p_limit,30), 100));
$fn$;

create or replace function public.create_post(p_content text, p_image_url text default null)
returns uuid language plpgsql security definer set search_path=public as $fn$
declare v_id uuid;
begin
    if coalesce(trim(p_content),'')='' and coalesce(trim(p_image_url),'')='' then
        raise exception 'Publicacion vacia';
    end if;
    insert into public.social_posts (post_id, user_id, content, image_url, likes_count, created_at)
    values (gen_random_uuid(), auth.uid(), nullif(trim(p_content),''), nullif(trim(p_image_url),''), 0, now())
    returning post_id into v_id;
    return v_id;
end; $fn$;

create or replace function public.toggle_like(p_post_id uuid)
returns table (liked boolean, likes_count int)
language plpgsql security definer set search_path=public as $fn$
declare v_liked boolean; v_count int;
begin
    if exists(select 1 from public.post_likes where post_id=p_post_id and user_id=auth.uid()) then
        delete from public.post_likes where post_id=p_post_id and user_id=auth.uid();
        v_liked := false;
    else
        insert into public.post_likes(post_id, user_id) values (p_post_id, auth.uid()) on conflict do nothing;
        v_liked := true;
    end if;
    select count(*)::int into v_count from public.post_likes where post_id=p_post_id;
    update public.social_posts set likes_count=v_count where post_id=p_post_id;
    return query select v_liked, v_count;
end; $fn$;

create or replace function public.get_stories()
returns table (story_id uuid, user_id uuid, author_name text, author_username text, author_avatar text,
               media_url text, created_at timestamptz, expires_at timestamptz)
language sql security definer set search_path=public as $fn$
    select st.story_id, st.user_id, pr.name, pr.username, pr.avatar_url,
           st.media_url, st.created_at, st.expires_at
    from public.social_stories st
    join public.profiles pr on pr.id = st.user_id
    where st.expires_at > now()
    order by st.created_at desc;
$fn$;

create or replace function public.create_story(p_media_url text, p_hours int default 24)
returns uuid language plpgsql security definer set search_path=public as $fn$
declare v_id uuid;
begin
    if coalesce(trim(p_media_url),'')='' then raise exception 'Historia vacia'; end if;
    insert into public.social_stories (story_id, user_id, media_url, created_at, expires_at)
    values (gen_random_uuid(), auth.uid(), trim(p_media_url), now(),
            now() + make_interval(hours => greatest(1, least(coalesce(p_hours,24),72))))
    returning story_id into v_id;
    return v_id;
end; $fn$;

grant execute on function public.get_feed(int, timestamptz) to authenticated, anon;
grant execute on function public.create_post(text, text)     to authenticated;
grant execute on function public.toggle_like(uuid)           to authenticated;
grant execute on function public.get_stories()               to authenticated, anon;
grant execute on function public.create_story(text, int)     to authenticated;
