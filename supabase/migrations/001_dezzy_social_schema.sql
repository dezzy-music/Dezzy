-- ============================================================================
--  Dezzy · Red social musical — Esquema base
--  Migración 001 · schema `dezzy`
-- ============================================================================
--
--  CONTEXTO CRÍTICO
--  Este proyecto Supabase está COMPARTIDO con otro producto (plataforma de
--  cursos/gamificación) que es dueño de `public.profiles`, `public.formulas`,
--  `public.challenges`, `public.social_posts`, `public.stories`, etc.
--
--  Por eso TODO lo nuevo vive en un schema propio `dezzy`. Esta migración:
--    · NUNCA hace DROP ni ALTER sobre nada de `public`.
--    · Solo LEE `public.profiles` (identidad compartida) mediante FKs y JOINs.
--    · Es idempotente: se puede correr varias veces sin romper nada.
--
--  DECISIÓN DE ARQUITECTURA — por qué las tablas van en `dezzy` pero las
--  funciones RPC en `public` con prefijo `dezzy_`:
--  PostgREST solo sirve los schemas listados en "Exposed schemas". Publicar
--  `dezzy` obligaría a tocar la configuración de la API del proyecto, que es
--  compartida. En su lugar las tablas quedan privadas (el cliente NUNCA las
--  consulta directo) y todo el acceso pasa por funciones SECURITY DEFINER en
--  `public`, que sí es visible. Esto además da una superficie de API estable:
--  podemos refactorizar tablas sin romper la app publicada.
--
--  ROLLBACK: ver 001_dezzy_social_schema_rollback.sql
-- ============================================================================

begin;

create schema if not exists dezzy;

-- El cliente no toca el schema directamente (todo va por RPC en public), pero
-- las funciones SECURITY DEFINER necesitan resolver los objetos.
grant usage on schema dezzy to postgres, service_role;

-- ============================================================================
--  1. PRESENCIA — "escuchando ahora"
-- ============================================================================
-- Una fila por usuario (no un log): el estado actual. La caducidad se decide
-- por `updated_at`, no borrando filas, para que el cliente pueda hacer upsert
-- barato cada pocos segundos sin generar basura.

create table if not exists dezzy.presence (
    user_id      uuid primary key references auth.users (id) on delete cascade,
    video_id     text,
    title        text,
    artist       text,
    album        text,
    artwork_url  text,
    is_playing   boolean     not null default false,
    position_ms  bigint      not null default 0,
    duration_ms  bigint      not null default 0,
    -- Ubicación opcional para el Music Map. NULL = no comparte ubicación.
    lat          double precision,
    lng          double precision,
    -- Visibilidad elegida por el usuario para su actividad.
    visibility   text        not null default 'friends'
                 check (visibility in ('public', 'friends', 'private')),
    device_name  text,
    updated_at   timestamptz not null default now()
);

create index if not exists presence_updated_idx  on dezzy.presence (updated_at desc);
create index if not exists presence_location_idx on dezzy.presence (lat, lng)
    where lat is not null and lng is not null;

-- ============================================================================
--  2. GRAFO SOCIAL — seguidores (asimétrico, estilo Spotify)
-- ============================================================================
-- `public.friends` (del otro producto, ya existente) modela amistad recíproca
-- con solicitud. Spotify es asimétrico: seguir sin pedir permiso. Conviven:
-- "amigos" = relación fuerte (chat), "seguidores" = descubrimiento.

create table if not exists dezzy.follows (
    follower_id uuid        not null references auth.users (id) on delete cascade,
    followee_id uuid        not null references auth.users (id) on delete cascade,
    created_at  timestamptz not null default now(),
    primary key (follower_id, followee_id),
    constraint no_self_follow check (follower_id <> followee_id)
);

create index if not exists follows_followee_idx on dezzy.follows (followee_id);

-- ============================================================================
--  3. FEED MUSICAL
-- ============================================================================
-- Un post SIEMPRE referencia una entidad musical real de YouTube Music
-- (canción, álbum o playlist). No hay posts de solo texto ni de foto: eso es
-- lo que diferencia este feed del de Instagram que había antes.

create table if not exists dezzy.posts (
    post_id      uuid        primary key default gen_random_uuid(),
    user_id      uuid        not null references auth.users (id) on delete cascade,
    entity_type  text        not null check (entity_type in ('song', 'album', 'playlist', 'artist')),
    entity_id    text        not null,
    entity_title text        not null,
    entity_subtitle text,
    artwork_url  text,
    caption      text        check (caption is null or char_length(caption) <= 500),
    likes_count  integer     not null default 0,
    comments_count integer   not null default 0,
    created_at   timestamptz not null default now()
);

create index if not exists posts_created_idx on dezzy.posts (created_at desc);
create index if not exists posts_user_idx    on dezzy.posts (user_id, created_at desc);

create table if not exists dezzy.post_likes (
    post_id    uuid        not null references dezzy.posts (post_id) on delete cascade,
    user_id    uuid        not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (post_id, user_id)
);

create table if not exists dezzy.post_comments (
    comment_id uuid        primary key default gen_random_uuid(),
    post_id    uuid        not null references dezzy.posts (post_id) on delete cascade,
    user_id    uuid        not null references auth.users (id) on delete cascade,
    content    text        not null check (char_length(content) between 1 and 500),
    created_at timestamptz not null default now()
);

create index if not exists post_comments_post_idx on dezzy.post_comments (post_id, created_at);

-- Contadores denormalizados: el feed se lee muchísimo más de lo que se escribe,
-- así evitamos un COUNT() por post en cada carga.
create or replace function dezzy.sync_post_counters()
returns trigger language plpgsql security definer set search_path = dezzy, public as $$
begin
    if tg_table_name = 'post_likes' then
        update dezzy.posts
           set likes_count = greatest(0, likes_count + case when tg_op = 'INSERT' then 1 else -1 end)
         where post_id = coalesce(new.post_id, old.post_id);
    else
        update dezzy.posts
           set comments_count = greatest(0, comments_count + case when tg_op = 'INSERT' then 1 else -1 end)
         where post_id = coalesce(new.post_id, old.post_id);
    end if;
    return null;
end;
$$;

drop trigger if exists post_likes_counter on dezzy.post_likes;
create trigger post_likes_counter
    after insert or delete on dezzy.post_likes
    for each row execute function dezzy.sync_post_counters();

drop trigger if exists post_comments_counter on dezzy.post_comments;
create trigger post_comments_counter
    after insert or delete on dezzy.post_comments
    for each row execute function dezzy.sync_post_counters();

-- ============================================================================
--  4. COMENTARIOS CON MARCA DE TIEMPO (estilo SoundCloud)
-- ============================================================================

create table if not exists dezzy.track_comments (
    comment_id  uuid        primary key default gen_random_uuid(),
    video_id    text        not null,
    user_id     uuid        not null references auth.users (id) on delete cascade,
    position_ms integer     not null check (position_ms >= 0),
    content     text        not null check (char_length(content) between 1 and 300),
    created_at  timestamptz not null default now()
);

create index if not exists track_comments_video_idx on dezzy.track_comments (video_id, position_ms);

-- ============================================================================
--  5. PERFIL MUSICAL — estadísticas de escucha
-- ============================================================================
-- Agregado incremental (no un log de reproducciones): la app suma escuchas y
-- el perfil lee el top directamente. Mantiene la tabla pequeña y las lecturas
-- baratas sin necesidad de un job de agregación.

create table if not exists dezzy.listening_stats (
    user_id      uuid        not null references auth.users (id) on delete cascade,
    entity_type  text        not null check (entity_type in ('song', 'artist')),
    entity_id    text        not null,
    entity_name  text        not null,
    artwork_url  text,
    play_count   integer     not null default 1,
    last_played  timestamptz not null default now(),
    primary key (user_id, entity_type, entity_id)
);

create index if not exists listening_stats_top_idx
    on dezzy.listening_stats (user_id, entity_type, play_count desc);

-- ============================================================================
--  6. LISTENING PARTY — escucha sincronizada
-- ============================================================================

create table if not exists dezzy.parties (
    party_id    uuid        primary key default gen_random_uuid(),
    host_id     uuid        not null references auth.users (id) on delete cascade,
    -- Código corto para invitar/QR sin exponer el uuid.
    join_code   text        not null unique,
    title       text,
    video_id    text,
    position_ms bigint      not null default 0,
    is_playing  boolean     not null default false,
    is_open     boolean     not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table if not exists dezzy.party_members (
    party_id  uuid        not null references dezzy.parties (party_id) on delete cascade,
    user_id   uuid        not null references auth.users (id) on delete cascade,
    joined_at timestamptz not null default now(),
    primary key (party_id, user_id)
);

-- ============================================================================
--  7. PLAYLISTS COLABORATIVAS
-- ============================================================================

create table if not exists dezzy.playlists (
    playlist_id uuid        primary key default gen_random_uuid(),
    owner_id    uuid        not null references auth.users (id) on delete cascade,
    name        text        not null check (char_length(name) between 1 and 120),
    description text,
    artwork_url text,
    is_public   boolean     not null default false,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table if not exists dezzy.playlist_collaborators (
    playlist_id uuid        not null references dezzy.playlists (playlist_id) on delete cascade,
    user_id     uuid        not null references auth.users (id) on delete cascade,
    role        text        not null default 'editor' check (role in ('editor', 'viewer')),
    added_at    timestamptz not null default now(),
    primary key (playlist_id, user_id)
);

create table if not exists dezzy.playlist_items (
    item_id     uuid        primary key default gen_random_uuid(),
    playlist_id uuid        not null references dezzy.playlists (playlist_id) on delete cascade,
    video_id    text        not null,
    title       text        not null,
    artist      text,
    artwork_url text,
    added_by    uuid        references auth.users (id) on delete set null,
    position    integer     not null default 0,
    added_at    timestamptz not null default now()
);

create index if not exists playlist_items_order_idx on dezzy.playlist_items (playlist_id, position);

-- ============================================================================
--  8. SINCRONIZACIÓN DE POSICIÓN ENTRE DISPOSITIVOS
-- ============================================================================
-- Distinta de `presence`: esto es privado del usuario (continuar en otro
-- dispositivo), mientras que presence es lo que ven los demás.

create table if not exists dezzy.playback_state (
    user_id     uuid        primary key references auth.users (id) on delete cascade,
    video_id    text,
    title       text,
    artist      text,
    artwork_url text,
    position_ms bigint      not null default 0,
    queue_json  jsonb,
    device_name text,
    updated_at  timestamptz not null default now()
);

-- ============================================================================
--  RLS — todo cerrado por defecto
-- ============================================================================
-- Las tablas no se exponen por PostgREST (schema no publicado) y todo el
-- acceso va por funciones SECURITY DEFINER. Aun así activamos RLS sin políticas
-- permisivas: defensa en profundidad por si el schema se publicara por error.

alter table dezzy.presence               enable row level security;
alter table dezzy.follows                enable row level security;
alter table dezzy.posts                  enable row level security;
alter table dezzy.post_likes             enable row level security;
alter table dezzy.post_comments          enable row level security;
alter table dezzy.track_comments         enable row level security;
alter table dezzy.listening_stats        enable row level security;
alter table dezzy.parties                enable row level security;
alter table dezzy.party_members          enable row level security;
alter table dezzy.playlists              enable row level security;
alter table dezzy.playlist_collaborators enable row level security;
alter table dezzy.playlist_items         enable row level security;
alter table dezzy.playback_state         enable row level security;

commit;
