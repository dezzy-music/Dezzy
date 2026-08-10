-- =============================================================================
-- 004 — Perfil Threads + pistas más comentadas (2026-07-22)
-- =============================================================================
-- RPCs nuevas para: (a) los "hilos" del perfil (publicaciones de un usuario),
-- (b) sugerencias de gente a seguir, (c) las pistas con más comentarios.
-- Todas SECURITY DEFINER, derivan identidad de auth.uid(), search_path fijado.

-- (a) Publicaciones de un usuario concreto (para su perfil). Misma forma que
--     dezzy_get_feed para reusar el parser del cliente.
create or replace function public.dezzy_user_posts(
    p_user_id uuid,
    p_limit   integer default 30
) returns table (
    post_id uuid, user_id uuid, author_name text, author_username text,
    author_avatar text, entity_type text, entity_id text, entity_title text,
    entity_subtitle text, artwork_url text, caption text,
    likes_count integer, comments_count integer, liked_by_me boolean,
    created_at timestamptz
) language sql stable security definer set search_path = dezzy, public as $$
    select p.post_id, p.user_id,
           coalesce(pf.name, 'Usuario')::text, pf.username::text, pf.avatar_url::text,
           p.entity_type, p.entity_id, p.entity_title, p.entity_subtitle,
           p.artwork_url, p.caption, p.likes_count, p.comments_count,
           exists (select 1 from dezzy.post_likes l
                    where l.post_id = p.post_id and l.user_id = auth.uid()),
           p.created_at
      from dezzy.posts p
      join public.profiles pf on pf.id = p.user_id
     where p.user_id = p_user_id
     order by p.created_at desc
     limit least(greatest(p_limit, 1), 50);
$$;
grant execute on function public.dezzy_user_posts(uuid, integer) to authenticated;

-- (b) Sugerencias: gente de Dezzy (con @usuario) que no soy yo y que aún no
--     sigo, ordenada por número de seguidores. `username is not null` acota a
--     usuarios de Dezzy y deja fuera a los del otro producto que comparte
--     `public.profiles`.
create or replace function public.dezzy_suggestions(p_limit integer default 10)
returns table (
    user_id uuid, name text, username text, avatar_url text, followers bigint
) language sql stable security definer set search_path = dezzy, public as $$
    select pf.id,
           coalesce(pf.name, 'Usuario')::text,
           pf.username::text,
           pf.avatar_url::text,
           (select count(*) from dezzy.follows f where f.followee_id = pf.id)
      from public.profiles pf
     where pf.username is not null
       and pf.id <> auth.uid()
       and not exists (select 1 from dezzy.follows f
                        where f.follower_id = auth.uid() and f.followee_id = pf.id)
     order by (select count(*) from dezzy.follows f where f.followee_id = pf.id) desc,
              pf.username asc
     limit least(greatest(p_limit, 1), 30);
$$;
grant execute on function public.dezzy_suggestions(integer) to authenticated;

-- (c) Pistas más comentadas (comentarios con marca de tiempo, estilo YT Music):
--     por video_id, el total y una muestra del comentario más reciente + autor.
create or replace function public.dezzy_top_commented(p_limit integer default 10)
returns table (
    video_id       text,
    comment_count  bigint,
    sample_content text,
    sample_author  text,
    sample_avatar  text
) language sql stable security definer set search_path = dezzy, public as $$
    with agg as (
        select tc.video_id, count(*) as cnt
          from dezzy.track_comments tc
         group by tc.video_id
    ),
    latest as (
        select distinct on (tc.video_id)
               tc.video_id, tc.content, tc.user_id
          from dezzy.track_comments tc
         order by tc.video_id, tc.created_at desc
    )
    select a.video_id, a.cnt,
           l.content,
           coalesce(pf.name, 'Usuario')::text,
           pf.avatar_url::text
      from agg a
      join latest l on l.video_id = a.video_id
      left join public.profiles pf on pf.id = l.user_id
     order by a.cnt desc
     limit least(greatest(p_limit, 1), 30);
$$;
grant execute on function public.dezzy_top_commented(integer) to authenticated;
