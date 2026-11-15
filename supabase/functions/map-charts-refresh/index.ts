// Dezzy — Edge Function `map-charts-refresh`
//
// Descarga el top de YouTube Music de cada país del Mapa musical y lo guarda en
// dezzy.map_charts (vía RPC dezzy_map_charts_upsert). La llama pg_cron cada 3 h.
//
// Despliegue:
//   supabase secrets set MAP_CHARTS_CRON_SECRET=<secreto-largo-aleatorio>
//   supabase functions deploy map-charts-refresh --no-verify-jwt
//
// Seguridad: sin JWT, pero exige la cabecera `x-cron-secret`; sin ella responde 401,
// así nadie puede usarla para disparar peticiones a YouTube.
//
// Debe coincidir con MusicMapCharts.kt (app): mismos países, misma selección de playlist
// y mismo formato de canción {id,title,thumbnail,duration,artists:[{name,id}]}.

const COUNTRIES = [
  "US", "MX", "ES", "GB", "BR", "AR", "CO", "CL",
  "FR", "DE", "IT", "JP", "KR", "IN", "CA", "AU",
  "ZZ", // mundial
];
const SONGS_PER_COUNTRY = 10;
const CONCURRENCY = 4;

const BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
const CHARTS_PARAMS = "ggMGCgQIgAQ%3D";
const CLIENT_NAME = "WEB_REMIX";
const CLIENT_VERSION = "1.20260114.01.00";
const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

type Artist = { name: string; id: string };
type Song = { id: string; title: string; thumbnail: string; duration: number; artists: Artist[] };

// deno-lint-ignore no-explicit-any
type Json = any;

async function browse(body: Record<string, unknown>, gl: string): Promise<Json> {
  const payload = {
    context: { client: { clientName: CLIENT_NAME, clientVersion: CLIENT_VERSION, hl: "en", gl } },
    ...body,
  };
  let lastError: unknown = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetch(BROWSE_URL, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "User-Agent": USER_AGENT,
          "Origin": "https://music.youtube.com",
          "Referer": "https://music.youtube.com/",
        },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(15_000),
      });
      if (res.ok) return await res.json();
      lastError = new Error(`HTTP ${res.status}`);
      if (res.status < 500 && res.status !== 429) break;
    } catch (e) {
      lastError = e;
    }
    await new Promise((r) => setTimeout(r, 800 * (attempt + 1)));
  }
  throw lastError ?? new Error("browse failed");
}

/** Playlist oficial del chart del país: top diario → tendencias → top 100 (nunca directos ni géneros). */
async function chartPlaylistId(code: string): Promise<string | null> {
  const json = await browse(
    { browseId: "FEmusic_charts", params: CHARTS_PARAMS, formData: { selectedValues: [code] } },
    code === "ZZ" ? "US" : code,
  );
  const sections: Json[] =
    json?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content
      ?.sectionListRenderer?.contents ?? [];
  const playlists: { title: string; id: string }[] = [];
  for (const section of sections) {
    for (const item of section?.musicCarouselShelfRenderer?.contents ?? []) {
      const r = item?.musicTwoRowItemRenderer;
      const browseEp = r?.navigationEndpoint?.browseEndpoint;
      const pageType =
        browseEp?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
      if (pageType !== "MUSIC_PAGE_TYPE_PLAYLIST") continue;
      const id = String(browseEp?.browseId ?? "").replace(/^VL/, "");
      const title = String(r?.title?.runs?.[0]?.text ?? "").toLowerCase();
      if (id) playlists.push({ title, id });
    }
  }
  const prefs = [
    (t: string) => t.startsWith("daily top music videos"),
    (t: string) => t.startsWith("trending"),
    (t: string) => t.startsWith("top 100 music videos"),
  ];
  for (const match of prefs) {
    const hit = playlists.find((p) => match(p.title));
    if (hit) return hit.id;
  }
  return playlists.find((p) => !p.title.includes("live"))?.id ?? null;
}

function parseDuration(text: string | undefined): number {
  if (!text) return 0;
  const parts = text.split(":").map((n) => parseInt(n, 10));
  if (parts.some((n) => Number.isNaN(n))) return 0;
  return parts.reduce((acc, n) => acc * 60 + n, 0);
}

async function playlistSongs(playlistId: string, gl: string): Promise<Song[]> {
  const json = await browse({ browseId: `VL${playlistId}` }, gl);
  const shelves: Json[] =
    json?.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents ??
      json?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content
        ?.sectionListRenderer?.contents ?? [];
  const items: Json[] = shelves.flatMap((s) => s?.musicPlaylistShelfRenderer?.contents ?? []);

  const songs: Song[] = [];
  const seen = new Set<string>();
  for (const item of items) {
    const r = item?.musicResponsiveListItemRenderer;
    if (!r) continue;
    const id: string | undefined =
      r.playlistItemData?.videoId ??
        r.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
          ?.playNavigationEndpoint?.watchEndpoint?.videoId;
    if (!id || seen.has(id)) continue;
    const cols: Json[] = r.flexColumns ?? [];
    const title: string | undefined =
      cols[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text;
    if (!title) continue;
    const runs: Json[] = cols[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs ?? [];
    let artists: Artist[] = runs
      .filter((run) => String(run?.navigationEndpoint?.browseEndpoint?.browseId ?? "").startsWith("UC"))
      .map((run) => ({ name: String(run.text), id: String(run.navigationEndpoint.browseEndpoint.browseId) }));
    if (artists.length === 0 && runs[0]?.text) artists = [{ name: String(runs[0].text), id: "" }];
    const thumbs: Json[] = r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails ?? [];
    const thumbnail: string = thumbs.length
      ? String(thumbs[thumbs.length - 1].url)
      : `https://i.ytimg.com/vi/${id}/hqdefault.jpg`;
    const duration = parseDuration(
      r.fixedColumns?.[0]?.musicResponsiveListItemFixedColumnRenderer?.text?.runs?.[0]?.text,
    );
    seen.add(id);
    songs.push({ id, title, thumbnail, duration, artists });
    if (songs.length >= SONGS_PER_COUNTRY) break;
  }
  return songs;
}

async function fetchCountry(code: string): Promise<Song[]> {
  const playlistId = await chartPlaylistId(code);
  if (!playlistId) return [];
  return await playlistSongs(playlistId, code === "ZZ" ? "US" : code);
}

async function mapLimited<T, R>(items: T[], limit: number, fn: (t: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i]);
    }
  });
  await Promise.all(workers);
  return out;
}

Deno.serve(async (req) => {
  const secret = Deno.env.get("MAP_CHARTS_CRON_SECRET");
  if (!secret || req.headers.get("x-cron-secret") !== secret) {
    return new Response("unauthorized", { status: 401 });
  }

  const started = Date.now();
  const results = await mapLimited(COUNTRIES, CONCURRENCY, async (country) => {
    try {
      return { country, songs: await fetchCountry(country), error: null as string | null };
    } catch (e) {
      return { country, songs: [] as Song[], error: String(e) };
    }
  });

  // Solo se guardan países con canciones: un fallo puntual no borra el último chart bueno.
  const rows = results.filter((r) => r.songs.length > 0).map(({ country, songs }) => ({ country, songs }));
  let saved = 0;
  if (rows.length > 0) {
    const url = Deno.env.get("SUPABASE_URL")!;
    const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const res = await fetch(`${url}/rest/v1/rpc/dezzy_map_charts_upsert`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "apikey": key,
        "Authorization": `Bearer ${key}`,
      },
      body: JSON.stringify({ p_rows: rows }),
    });
    if (!res.ok) {
      return Response.json(
        { ok: false, error: `upsert HTTP ${res.status}: ${await res.text()}` },
        { status: 500 },
      );
    }
    saved = Number(await res.json()) || 0;
  }

  return Response.json({
    ok: true,
    saved,
    ms: Date.now() - started,
    countries: results.map((r) => ({ country: r.country, songs: r.songs.length, error: r.error })),
  });
});
