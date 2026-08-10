-- =============================================================================
-- DIAGNÓSTICO: signup devuelve 500 "Database error saving new user" (2026-07-22)
-- =============================================================================
-- El alta de CUALQUIER usuario (email y anónimo) falla con 500 desde GoTrue.
-- Ese error significa que el INSERT en auth.users abortó, casi siempre porque
-- un trigger sobre auth.users (el que crea la fila en public.profiles) lanzó
-- una excepción. Ejecutar cada bloque en el SQL Editor y revisar la salida.
-- SOLO LECTURA: este archivo no cambia nada.

-- 1) Triggers definidos sobre auth.users (el sospechoso número uno).
select t.tgname,
       t.tgenabled,           -- 'O' = habilitado; 'D' = deshabilitado
       pg_get_triggerdef(t.oid) as definicion
from pg_trigger t
join pg_class c on c.oid = t.tgrelid
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'auth'
  and c.relname = 'users'
  and not t.tgisinternal;

-- 2) Código de las funciones que esos triggers invocan (nombres habituales).
select n.nspname, p.proname, pg_get_functiondef(p.oid) as codigo
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where p.proname ilike any (array['%handle_new_user%', '%on_auth_user%', '%new_user%'])
  and n.nspname in ('public', 'auth', 'dezzy');

-- 3) Estructura actual de public.profiles: una columna NOT NULL sin default
--    añadida por el otro producto rompería el INSERT del trigger.
select column_name, data_type, is_nullable, column_default
from information_schema.columns
where table_schema = 'public' and table_name = 'profiles'
order by ordinal_position;

-- 4) Constraints e índices únicos de profiles (colisiones de username, etc.).
select conname, pg_get_constraintdef(oid)
from pg_constraint
where conrelid = 'public.profiles'::regclass;

-- 5) Probar a mano lo que hace el trigger (NO ejecuta el signup real):
--    reproducir el INSERT del trigger con un uuid inventado dentro de un
--    ROLLBACK para ver el error exacto sin dejar rastro.
begin;
insert into public.profiles (id) values (gen_random_uuid());
rollback;
