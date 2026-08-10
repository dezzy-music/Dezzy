'use strict';

const crypto = require('crypto');

/**
 * Cliente Qobuz con INGENIERÍA INVERSA self-healing: extrae el `app_id` y los
 * secretos de firma EN VIVO del bundle web de Qobuz en cada arranque, así el
 * resolver nunca se rompe cuando Qobuz los rota (a diferencia de los que los
 * hardcodean y mueren). Nada de secretos estáticos.
 *
 * Da FLAC hi-res (hasta 24-bit/192kHz). Qobuz protege TODO (búsqueda y descarga)
 * tras la cuenta, así que este proveedor solo se activa con un token de
 * suscripción Qobuz (env QOBUZ_TOKEN). La ingeniería inversa del app_id/secreto
 * es lo que se mantiene solo; el token de suscripción lo pone el usuario una vez.
 */

const LOGIN_PAGE = 'https://play.qobuz.com/login';
const API = 'https://www.qobuz.com/api.json/0.2';
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';

// Formatos Qobuz: 27=Hi-Res ≤192kHz, 7=Hi-Res ≤96kHz, 6=CD 16/44.1, 5=MP3 320.
const FORMAT_CHAIN = {
  flac: [27, 7, 6, 5],
  mp3_320: [5],
  mp3: [5],
};

let cachedConfig = null; // { appId, secrets:[...], at }
const CONFIG_TTL_MS = 6 * 60 * 60 * 1000;

function capitalize(s) {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * Descarga el bundle web y extrae app_id + secretos candidatos, replicando el
 * algoritmo de derivación (seed + info + extras por timezone, base64). Devuelve
 * varios secretos candidatos: al firmar se prueban hasta que uno valida.
 */
async function fetchAppConfig() {
  const t = (ms) => (AbortSignal.timeout ? AbortSignal.timeout(ms) : undefined);
  const loginRes = await fetch(LOGIN_PAGE, { headers: { 'User-Agent': UA }, signal: t(15000) });
  const loginHtml = await loginRes.text();
  const bundleMatch = loginHtml.match(/<script src="(\/resources\/[^"]+\/bundle\.js)"/);
  if (!bundleMatch) throw new Error('No se encontró el bundle de Qobuz');
  const bundleUrl = 'https://play.qobuz.com' + bundleMatch[1];
  const bundle = await (
    await fetch(bundleUrl, { headers: { 'User-Agent': UA }, signal: t(30000) })
  ).text();

  const appIdMatch = bundle.match(/production:\{api:\{appId:"(\d+)"/);
  if (!appIdMatch) throw new Error('No se pudo extraer el app_id de Qobuz');
  const appId = appIdMatch[1];

  // seed + timezone
  const seedRe = /[a-z]\.initialSeed\("([\w=]+)",window\.utimezone\.([a-z]+)\)/g;
  const order = [];
  const byTz = new Map();
  for (const m of bundle.matchAll(seedRe)) {
    byTz.set(m[2], [m[1]]);
    order.push(m[2]);
  }
  // Qobuz intercambia la 1ª y 2ª timezone en su derivación (rareza histórica).
  if (order.length > 1) {
    const tmp = order[0];
    order[0] = order[1];
    order[1] = tmp;
  }

  const timezones = order.map(capitalize).join('|');
  const infoRe = new RegExp(
    `name:"\\w+/(${timezones})",info:"([\\w=]+)",extras:"([\\w=]+)"`,
    'g',
  );
  for (const m of bundle.matchAll(infoRe)) {
    const tz = m[1].toLowerCase();
    if (byTz.has(tz)) byTz.get(tz).push(m[2], m[3]);
  }

  const secrets = [];
  for (const tz of order) {
    const parts = byTz.get(tz) || [];
    if (parts.length < 3) continue; // sin info/extras no hay secreto
    const joined = parts.join('').slice(0, -44);
    try {
      const dec = Buffer.from(joined, 'base64').toString('utf8');
      if (dec && /^[0-9a-f]{32}$/i.test(dec)) secrets.push(dec);
    } catch (_) {
      /* candidato inválido */
    }
  }
  if (secrets.length === 0) throw new Error('No se derivó ningún secreto de Qobuz');
  return { appId, secrets, at: Date.now() };
}

async function getConfig(force = false) {
  if (!force && cachedConfig && Date.now() - cachedConfig.at < CONFIG_TTL_MS) {
    return cachedConfig;
  }
  cachedConfig = await fetchAppConfig();
  return cachedConfig;
}

async function apiGet(path, params, appId, authToken) {
  const url = new URL(`${API}/${path}`);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, String(v));
  const headers = { 'User-Agent': UA, 'X-App-Id': appId };
  if (authToken) headers['X-User-Auth-Token'] = authToken;
  const res = await fetch(url, { headers });
  const json = await res.json().catch(() => null);
  return { status: res.status, json };
}

/**
 * Busca la pista por ISRC. Qobuz exige el token de suscripción incluso para
 * buscar, así que este camino solo funciona con QOBUZ_TOKEN configurado.
 */
async function trackByIsrc(isrc) {
  if (!isrc) return null;
  const token = process.env.QOBUZ_TOKEN || '';
  const { appId } = await getConfig();
  const { json } = await apiGet('track/search', { query: isrc, limit: 5 }, appId, token);
  const items = json && json.tracks && json.tracks.items;
  if (!items || !items.length) return null;
  const hit =
    items.find((t) => (t.isrc || '').toUpperCase() === isrc.toUpperCase()) || items[0];
  return {
    id: hit.id,
    hires: !!hit.hires_streamable || !!hit.hires,
    title: hit.title,
    artist: hit.performer && hit.performer.name,
  };
}

async function searchTrackId(title, artist) {
  const token = process.env.QOBUZ_TOKEN || '';
  const { appId } = await getConfig();
  const { json } = await apiGet(
    'track/search',
    { query: `${title} ${artist}`, limit: 1 },
    appId,
    token,
  );
  const first = json && json.tracks && json.tracks.items && json.tracks.items[0];
  return first ? { id: first.id, hires: !!first.hires_streamable } : null;
}

/** Firma request_sig de Qobuz (params ordenados alfabéticamente + ts + secreto). */
function signGetFileUrl(trackId, formatId, ts, secret) {
  const base =
    'trackgetFileUrl' +
    'format_id' + formatId +
    'intent' + 'stream' +
    'track_id' + trackId +
    ts +
    secret;
  return crypto.createHash('md5').update(base).digest('hex');
}

/**
 * URL directa del archivo (sin cifrar, a diferencia de Deezer). Requiere el
 * token de suscripción. Prueba cada secreto candidato hasta que uno valide.
 * Devuelve { url, formatId, container, mime, bitDepth, samplingRate } o lanza.
 */
async function fileUrl(trackId, quality, authToken) {
  if (!authToken) throw new Error('Qobuz necesita QOBUZ_TOKEN (suscripción) para descargar');
  const cfg = await getConfig();
  const chain = FORMAT_CHAIN[quality] || FORMAT_CHAIN.flac;

  for (const formatId of chain) {
    for (const secret of cfg.secrets) {
      const ts = Math.floor(Date.now() / 1000);
      const sig = signGetFileUrl(trackId, formatId, ts, secret);
      const { json } = await apiGet(
        'track/getFileUrl',
        {
          request_ts: ts,
          request_sig: sig,
          track_id: trackId,
          format_id: formatId,
          intent: 'stream',
        },
        cfg.appId,
        authToken,
      );
      if (json && json.url) {
        const isFlac = formatId >= 6;
        return {
          url: json.url,
          formatId,
          container: isFlac ? 'flac' : 'mp3',
          mime: isFlac ? 'audio/flac' : 'audio/mpeg',
          bitDepth: json.bit_depth,
          samplingRate: json.sampling_rate,
        };
      }
      // sig inválida → probamos el siguiente secreto; otro error → siguiente formato
      if (json && json.code && json.code !== 'InvalidRequestSignature') break;
    }
  }
  throw new Error('Qobuz no devolvió URL (¿token sin permiso para este formato?)');
}

module.exports = { getConfig, trackByIsrc, searchTrackId, fileUrl };
