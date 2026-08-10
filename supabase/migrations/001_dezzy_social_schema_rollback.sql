-- ============================================================================
--  ROLLBACK · Dezzy red social (migraciones 001 + 002)
-- ============================================================================
--  Revierte ÚNICAMENTE lo que crearon 001 y 002. No toca ningún objeto del
--  otro producto: el schema `dezzy` es exclusivo nuestro y las funciones se
--  seleccionan por el prefijo `dezzy_`.
--
--  DESTRUCTIVO: borra los datos sociales (posts, seguidores, comentarios…).
--  Haz un backup antes si ya hay actividad real de usuarios.
-- ============================================================================

begin;

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
        execute format('drop function if exists %s', fn);
    end loop;
end;
$$;

drop schema if exists dezzy cascade;

commit;
