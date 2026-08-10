-- ============================================================================
-- OpenTune / Dezzy — Reparación de la red social (Supabase)
--
-- Aplicado el 2026-07-02 vía Management API. Idempotente: se puede re-ejecutar.
--
-- Hechos verificados contra el backend real (qbjkiqbypctpcamnappl):
--   • profiles.level y profiles.xp son BIGINT (no integer).
--   • friends: user_id_1 = ENVÍA · user_id_2 = RECIBE · status = 'pending'|'accepted'
--     (confirmado leyendo send_friend_request / get_friend_requests).
--   • Existe el helper public._relationship(uuid) que calcula la relación.
--   • Estas RPC ya funcionaban y NO se tocan: get_friend_requests,
--     send_friend_request, respond_friend_request, set_username, set_phone_hash,
--     find_friends_by_contacts, accept_referral.
--
-- Solo 2 funciones estaban rotas:
--   • get_friends  → era un stub (WHERE 1=0 sobre 'leaderboard' inexistente) y
--                    su firma no traía 'username' que la app espera.
--   • search_users → declaraba level integer pero profiles.level es bigint (42804).
-- ============================================================================


-- 1) search_users(p_query): fix del tipo (bigint→integer) en 'level' + búsqueda GLOBAL
--    estilo Instagram (se quitó el filtro is_discoverable: cualquiera es encontrable por
--    @usuario o nombre). Reusa _relationship().
create or replace function public.search_users(p_query text)
returns table (id uuid, name text, username text, avatar_url text,
               level integer, rank text, relationship text)
language plpgsql
security definer
as $$
declare v_q text := lower(trim(p_query));
begin
    if length(v_q) < 2 then return; end if;
    return query
    select p.id, p.name, p.username, p.avatar_url,
           p.level::integer, p.rank, public._relationship(p.id)
    from public.profiles p
    where p.id <> auth.uid()
      and (lower(coalesce(p.username,'')) like v_q || '%'
        or lower(coalesce(p.name,''))     like '%' || v_q || '%')
    order by (lower(coalesce(p.username,'')) = v_q) desc,
             (lower(coalesce(p.username,'')) like v_q || '%') desc,
             p.level desc nulls last
    limit 40;
end; $$;


-- 2) get_friends(): implementación real. La firma cambia (añade username, quita
--    status) para coincidir con lo que la app parsea, por eso DROP + CREATE.
drop function if exists public.get_friends();
create function public.get_friends()
returns table (friend_id uuid, name text, username text, avatar_url text,
               level integer, rank text)
language plpgsql
security definer
as $$
begin
    return query
    select p.id as friend_id, p.name, p.username, p.avatar_url,
           p.level::integer, p.rank
    from public.friends f
    join public.profiles p
      on p.id = case when f.user_id_1 = auth.uid() then f.user_id_2
                     else f.user_id_1 end
    where f.status = 'accepted'
      and (f.user_id_1 = auth.uid() or f.user_id_2 = auth.uid())
    order by p.name nulls last;
end; $$;


-- 3) Permisos de ejecución. Solo 'authenticated' — las sesiones anónimas que crea la app
--    (SupabaseAuth.ensureSession) también usan el rol 'authenticated', así que la app no
--    pierde nada; lo que se cierra es la enumeración del directorio con la anon key pelada
--    (sin JWT). El revoke de PUBLIC es necesario porque Postgres da EXECUTE a public por
--    defecto al crear una función.
revoke execute on function public.search_users(text) from public, anon;
revoke execute on function public.get_friends()      from public, anon;
grant execute on function public.search_users(text) to authenticated;
grant execute on function public.get_friends()      to authenticated;


-- 4) Bucket de avatares (fotos de perfil): lectura pública, escritura del propio
--    usuario en su carpeta {uid}/...
insert into storage.buckets (id, name, public)
values ('avatars', 'avatars', true)
on conflict (id) do nothing;

do $$
begin
    if not exists (select 1 from pg_policies where schemaname='storage'
                   and tablename='objects' and policyname='avatars_public_read') then
        create policy "avatars_public_read" on storage.objects
            for select using (bucket_id = 'avatars');
    end if;
    if not exists (select 1 from pg_policies where schemaname='storage'
                   and tablename='objects' and policyname='avatars_user_insert') then
        create policy "avatars_user_insert" on storage.objects
            for insert to authenticated
            with check (bucket_id = 'avatars'
                        and (storage.foldername(name))[1] = auth.uid()::text);
    end if;
    if not exists (select 1 from pg_policies where schemaname='storage'
                   and tablename='objects' and policyname='avatars_user_update') then
        create policy "avatars_user_update" on storage.objects
            for update to authenticated
            using (bucket_id = 'avatars'
                   and (storage.foldername(name))[1] = auth.uid()::text);
    end if;
end $$;
