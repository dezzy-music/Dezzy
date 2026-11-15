-- =============================================================================
-- Rollback de 005 — Mapa musical: charts cacheados
-- =============================================================================
-- Solo elimina lo que creó 005 (objetos `dezzy_*` / esquema dezzy). La app sigue
-- funcionando sin esto: cae a su caché local y a la descarga en el dispositivo.

-- Job de pg_cron (si se programó).
do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.unschedule(jobid)
        from cron.job
        where jobname = 'dezzy-map-charts-refresh';
    end if;
end;
$$;

-- Secreto de Vault (si se creó).
do $$
begin
    if exists (select 1 from pg_extension where extname = 'supabase_vault') then
        delete from vault.secrets where name = 'dezzy_map_charts_cron_secret';
    end if;
end;
$$;

drop function if exists public.dezzy_map_charts_upsert(jsonb);
drop function if exists public.dezzy_map_charts();
drop table if exists dezzy.map_charts;
