-- =============================================================================
-- FIX 2026-07-22: signup roto (500) + set_username fantasma + music_presence
-- =============================================================================
-- Diagnóstico verificado contra la BD:
--   * `public.handle_new_user()` (trigger on_auth_user_created de auth.users)
--     inserta una columna `email` que public.profiles NO tiene → todo alta de
--     usuario (correo y anónima) aborta y GoTrue devuelve 500
--     "Database error saving new user".
--   * `public.set_username()` hace UPDATE puro: sin fila de perfil actualiza
--     0 filas y aun así devuelve success=true → el @usuario "se guarda" sin
--     guardarse.
-- Ejecutar los tres bloques en el SQL Editor (o vía Management API).

-- ─── 1) Alta de usuarios ─────────────────────────────────────────────────────
-- Se quita `email` del INSERT (restaura el comportamiento previo a la rotura;
-- NO se añade la columna a profiles: expondría correos por las políticas de
-- lectura de perfiles). Idempotente y con red de seguridad: el alta nunca
-- vuelve a abortar por el perfil — las apps cliente reintentan el upsert.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles (id, name, avatar_url)
  values (new.id, new.raw_user_meta_data->>'name', new.raw_user_meta_data->>'avatar_url')
  on conflict (id) do nothing;
  return new;
exception when others then
  raise warning 'handle_new_user fallo: % (%)', sqlerrm, sqlstate;
  return new;
end;
$$;

-- ─── 2) set_username a prueba de perfiles sin fila ───────────────────────────
-- Mismo contrato (firma y jsonb de respuesta); ahora upsert + exige sesión.
create or replace function public.set_username(p_username text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare v_clean text;
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;
    v_clean := lower(trim(p_username));
    if v_clean !~ '^[a-z0-9_\.]{3,20}$' then
        raise exception 'Username must be 3-20 chars: letters, numbers, _ or .';
    end if;
    if exists (select 1 from public.profiles where lower(username) = v_clean and id <> auth.uid()) then
        raise exception 'Username already taken';
    end if;
    insert into public.profiles (id, username)
    values (auth.uid(), v_clean)
    on conflict (id) do update set username = excluded.username;
    return jsonb_build_object('success', true, 'username', v_clean);
end;
$$;

-- ─── 3) Cierre del agujero de music_presence (documentado el 21-07) ──────────
-- La tabla legacy era legible y escribible por `anon` con user_id puesto por el
-- cliente: cualquiera podía leer o suplantar presencias sin sesión. La app ya
-- publica por `dezzy_presence` (identidad de auth.uid()); el único uso restante
-- era el fallback de LECTURA del Music Map sin sesión, que degrada limpio a
-- lista vacía.
revoke all on public.music_presence from anon;
alter table public.music_presence enable row level security;
