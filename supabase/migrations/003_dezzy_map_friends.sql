-- =============================================================================
-- 003 — Music Map: incluir amigos + video_id reproducible (2026-07-22)
-- =============================================================================
-- Antes `dezzy_map_presences` solo devolvía presencias con visibilidad 'public',
-- y sin `video_id`, así que en el mapa los amigos no aparecían y sus estrellas no
-- se podían reproducir. Cambios (additivos, no rompen al cliente que mapea por
-- nombre de columna):
--   * Se añade `video_id` a la salida → la app puede reproducir la canción real.
--   * El filtro pasa de `visibility = 'public'` a `_can_see_activity(auth.uid, …)`,
--     que ya encapsula la política: entran los públicos (para cualquiera) Y mis
--     amigos que comparten ubicación. Nadie que no me deje ver su actividad
--     aparece.
-- Cambia la firma (RETURNS TABLE), así que hay que DROP + CREATE.

drop function if exists public.dezzy_map_presences(integer);

create or replace function public.dezzy_map_presences(p_max_age_seconds integer default 900)
returns table (
    user_id     uuid,
    name        text,
    avatar_url  text,
    video_id    text,
    title       text,
    artist      text,
    artwork_url text,
    lat         double precision,
    lng         double precision
) language sql stable security definer set search_path = dezzy, public as $$
    select pr.user_id,
           coalesce(pf.name, 'Usuario')::text,
           pf.avatar_url::text,
           pr.video_id,
           pr.title, pr.artist, pr.artwork_url,
           pr.lat, pr.lng
      from dezzy.presence pr
      join public.profiles pf on pf.id = pr.user_id
     where pr.lat is not null and pr.lng is not null
       and pr.video_id is not null
       and pr.updated_at > now() - make_interval(secs => greatest(p_max_age_seconds, 60))
       and dezzy._can_see_activity(auth.uid(), pr.user_id)
     order by pr.updated_at desc
     limit 2000;
$$;

grant execute on function public.dezzy_map_presences(integer) to authenticated;
