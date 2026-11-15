-- =============================================================================
-- 005 — Mapa musical: charts por país cacheados en el servidor (2026-11-14)
-- =============================================================================
-- Problema: cada apertura del Mapa musical pedía a YouTube Music 16+ charts desde
-- el teléfono. Con muchos usuarios eso es lento y dispara el rate-limit de YouTube.
--
-- Solución: la Edge Function `map-charts-refresh` descarga los charts cada 3 h y
-- los guarda aquí. La app hace UNA llamada (`dezzy_map_charts`) para todos los
-- países; solo si falla o está vacía cae a su caché local / descarga propia.
--
-- Todo es ADITIVO: tabla nueva en el esquema `dezzy` y RPCs nuevas `dezzy_*`.
-- No toca ninguna tabla compartida. Reversible con 005_dezzy_map_charts_rollback.sql.

create schema if not exists dezzy;

create table if not exists dezzy.map_charts (
    country    text        primary key check (country ~ '^[A-Z]{2}$'),
    songs      jsonb       not null default '[]'::jsonb check (jsonb_typeof(songs) = 'array'),
    updated_at timestamptz not null default now()
);

comment on table dezzy.map_charts is
    'Top de YouTube Music por país para el Mapa musical de Dezzy. Lo escribe la Edge Function map-charts-refresh.';

-- Sin políticas: nadie lee/escribe la tabla directamente. Lectura vía RPC,
-- escritura solo con service_role (vía RPC de upsert).
alter table dezzy.map_charts enable row level security;
revoke all on table dezzy.map_charts from anon, authenticated;

-- -----------------------------------------------------------------------------
-- Lectura pública (anon): devuelve un array JSON
--   [{ "country": "MX", "updated_ms": 1763150000000, "songs": [ {id,title,thumbnail,duration,artists:[{name,id}]} ] }]
-- Solo filas de las últimas 24 h, para no servir charts viejos si el cron se cae.
-- -----------------------------------------------------------------------------
create or replace function public.dezzy_map_charts()
returns jsonb
language sql
stable
security definer
set search_path = dezzy, public
as $$
    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'country',    c.country,
                'updated_ms', (extract(epoch from c.updated_at) * 1000)::bigint,
                'songs',      c.songs
            )
            order by c.country
        ),
        '[]'::jsonb
    )
    from dezzy.map_charts c
    where c.updated_at > now() - interval '24 hours'
      and jsonb_array_length(c.songs) > 0;
$$;

revoke all on function public.dezzy_map_charts() from public;
grant execute on function public.dezzy_map_charts() to anon, authenticated, service_role;

-- -----------------------------------------------------------------------------
-- Escritura (solo service_role, la usa la Edge Function).
-- p_rows: [{ "country": "MX", "songs": [...] }, ...]. Ignora filas vacías o inválidas,
-- así un fallo puntual de YouTube nunca borra el último chart bueno.
-- -----------------------------------------------------------------------------
create or replace function public.dezzy_map_charts_upsert(p_rows jsonb)
returns integer
language plpgsql
security definer
set search_path = dezzy, public
as $$
declare
    affected integer;
begin
    if p_rows is null or jsonb_typeof(p_rows) <> 'array' then
        return 0;
    end if;

    insert into dezzy.map_charts (country, songs, updated_at)
    select upper(r ->> 'country'), r -> 'songs', now()
    from jsonb_array_elements(p_rows) as r
    where upper(coalesce(r ->> 'country', '')) ~ '^[A-Z]{2}$'
      and jsonb_typeof(r -> 'songs') = 'array'
      and jsonb_array_length(r -> 'songs') between 1 and 50
    on conflict (country) do update
        set songs = excluded.songs,
            updated_at = excluded.updated_at;

    get diagnostics affected = row_count;
    return affected;
end;
$$;

revoke all on function public.dezzy_map_charts_upsert(jsonb) from public, anon, authenticated;
grant execute on function public.dezzy_map_charts_upsert(jsonb) to service_role;

-- =============================================================================
-- PROGRAMACIÓN (opcional, ejecutar aparte DESPUÉS de desplegar la Edge Function)
-- =============================================================================
-- Requiere las extensiones pg_cron y pg_net (Dashboard → Database → Extensions).
-- 1) Guarda el secreto del cron en Vault (el mismo valor que MAP_CHARTS_CRON_SECRET
--    en los secretos de la Edge Function):
--
--      select vault.create_secret('<secreto-largo-aleatorio>', 'dezzy_map_charts_cron_secret');
--
-- 2) Programa el refresco cada 3 horas (minuto 17 para no coincidir con otros jobs):
--
--      select cron.schedule(
--          'dezzy-map-charts-refresh',
--          '17 */3 * * *',
--          $cron$
--          select net.http_post(
--              url     := 'https://qbjkiqbypctpcamnappl.supabase.co/functions/v1/map-charts-refresh',
--              headers := jsonb_build_object(
--                  'Content-Type',  'application/json',
--                  'x-cron-secret', (select decrypted_secret from vault.decrypted_secrets
--                                    where name = 'dezzy_map_charts_cron_secret')
--              ),
--              body    := '{}'::jsonb,
--              timeout_milliseconds := 120000
--          );
--          $cron$
--      );
--
-- 3) Primera carga inmediata (sin esperar al cron): ejecuta solo el net.http_post de arriba.
