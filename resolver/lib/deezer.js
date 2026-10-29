'use strict';

const crypto = require('crypto');

/**
 * Cliente Deezer del resolver. Implementa el método REAL de descarga lossless:
 *
 *   1. getUserData (con la cookie ARL)  -> api_token + license_token
 *   2. song.getData(sng_id)             -> TRACK_TOKEN + tamaños reales
 *   3. media.deezer.com/v1/get_url      -> URL del medio cifrado (BF_CBC_STRIPE)
 *
 * El descifrado Blowfish del stream vive en decrypt.js. La calidad FLAC exige un
 * ARL de una cuenta Deezer HiFi; con ARL gratis, get_url devuelve MP3 (hacemos
 * fallback automático de formatos).
 */

const GW_LIGHT = 'https://www.deezer.com/ajax/gw-light.php';
const GET_URL = 'https://media.deezer.com/v1/get_url';
const PUBLIC_API = 'https://api.deezer.com';

const BLOWFISH_SECRET = 'g4el58wc0zvf9na1';

// Orden de preferencia por calidad pedida (con fallback descendente real).
const FORMAT_CHAINS = {
  flac: ['FLAC', 'MP3_320', 'MP3_128'],
  mp3_320: ['MP3_320', 'MP3_128'],
  mp3: ['MP3_320', 'MP3_128'],
};

const CONTAINER_BY_FORMAT = {
  FLAC: 'flac',
  MP3_320: 'mp3',
  MP3_128: 'mp3',
};

const MIME_BY_FORMAT = {
  FLAC: 'audio/flac',
  MP3_320: 'audio/mpeg',
  MP3_128: 'audio/mpeg',
};

/** Cookie jar mínimo para conservar el `sid` entre llamadas del gateway. */
class Session {
  constructor(arl) {
    this.cookies = { arl };
    this.apiToken = '';
    this.licenseToken = '';
  }

  cookieHeader() {
    return Object.entries(this.cookies)
      .map(([k, v]) => `${k}=${v}`)
      .join('; ');
  }

  absorbSetCookie(res) {
    const raw = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
    for (const line of raw) {
      const [pair] = line.split(';');
      const idx = pair.indexOf('=');
      if (idx > 0) {
        const name = pair.slice(0, idx).trim();
        const value = pair.slice(idx + 1).trim();
        if (name && value && value !== 'deleted') this.cookies[name] = value;
      }
    }
  }

  async gw(method, body) {
    const url =
      `${GW_LIGHT}?method=${encodeURIComponent(method)}` +
      `&input=3&api_version=1.0&api_token=${encodeURIComponent(this.apiToken)}&cid=${(Math.random() * 1e9) | 0}`;
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36',
        Cookie: this.cookieHeader(),
        Accept: '*/*',
      },
      body: JSON.stringify(body || {}),
    });
    this.absorbSetCookie(res);
    const json = await res.json();
    return json;
  }
}

/** Autentica con el ARL y prepara api_token + license_token. */
async function doLogin(arl) {
  const s = new Session(arl);
  const data = await s.gw('deezer.getUserData', {});
  const results = data && data.results;
  const userId = results && results.USER && results.USER.USER_ID;
  if (!results || !userId || userId === 0) {
    throw new Error('ARL inválido o expirado (configura DEEZER_ARL en el resolver)');
  }
  s.apiToken = results.checkForm || '';
  s.licenseToken =
    (results.USER.OPTIONS && results.USER.OPTIONS.license_token) || '';
  const canLossless =
    !!(results.USER.OPTIONS && results.USER.OPTIONS.web_lossless) ||
    !!(results.USER.OPTIONS && results.USER.OPTIONS.web_hq);
  return { session: s, canLossless };
}

// ── Pool de ARLs con rotación + cooldown (estabilidad) ──────────────────────
// El api_token de Deezer caduca (~1h) y una cuenta puede saturarse/limitarse.
// Soportamos VARIOS ARLs (DEEZER_ARL separado por comas o saltos de línea): se
// cachea la sesión de cada uno, y si una falla se "bancarizada" un rato y otra
// toma el relevo. Con un solo ARL, se comporta como antes (set-once).

const SESSION_TTL_MS = 30 * 60 * 1000;
const COOLDOWN_MS = 10 * 60 * 1000;
const sessionCache = new Map(); // arl -> { session, canLossless, createdAt }
const cooldownUntil = new Map(); // arl -> timestamp

function arlPool() {
  return (process.env.DEEZER_ARL || '')
    .split(/[\n,]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

async function sessionFor(arl, forceRefresh = false) {
  const now = Date.now();
  const cached = sessionCache.get(arl);
  if (!forceRefresh && cached && now - cached.createdAt < SESSION_TTL_MS) {
    return { session: cached.session, canLossless: cached.canLossless };
  }
  const fresh = await doLogin(arl);
  sessionCache.set(arl, { ...fresh, createdAt: now });
  return fresh;
}

function shuffle(array) {
  for (let i = array.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [array[i], array[j]] = [array[j], array[i]];
  }
  return array;
}

/**
 * Devuelve una sesión de un ARL sano del pool, rotando si alguno falla. Los que
 * fallan se bancarizan (cooldown) para no reintentarlos en cada descarga.
 * Devuelve { session, canLossless, arl }.
 */
async function acquireSession(forceRefresh = false, customArl = null) {
  if (customArl) {
    const s = await sessionFor(customArl, forceRefresh);
    return { ...s, arl: customArl };
  }
  const pool = arlPool();
  if (pool.length === 0) throw new Error('No hay DEEZER_ARL configurado');
  const now = Date.now();
  const healthy = pool.filter((a) => (cooldownUntil.get(a) || 0) < now);
  // Distribución de carga: Shuffle aleatorio
  const order = shuffle(healthy.length ? healthy : [...pool]);
  let lastErr = null;
  for (const arl of order) {
    try {
      const s = await sessionFor(arl, forceRefresh);
      cooldownUntil.delete(arl);
      return { ...s, arl };
    } catch (e) {
      cooldownUntil.set(arl, Date.now() + COOLDOWN_MS);
      sessionCache.delete(arl);
      lastErr = e;
    }
  }
  throw lastErr || new Error('Ningún ARL del pool está disponible');
}

/** Bancariza un ARL concreto (tras un fallo de token en mitad de una descarga). */
function benchArl(arl) {
  if (!arl) return;
  cooldownUntil.set(arl, Date.now() + COOLDOWN_MS);
  sessionCache.delete(arl);
}

/** Estado del pool para /health (sin exponer los ARLs). */
function poolHealth() {
  const pool = arlPool();
  const now = Date.now();
  return {
    total: pool.length,
    available: pool.filter((a) => (cooldownUntil.get(a) || 0) < now).length,
  };
}

// Compatibilidad: login() ahora usa el pool. El primer argumento se ignora
// (los ARLs vienen del entorno); forceRefresh sigue funcionando.
async function login(_arlIgnored, forceRefresh = false) {
  return acquireSession(forceRefresh);
}

function invalidateSession() {
  sessionCache.clear();
}

const searchCache = new Map();
function getCached(key) { return searchCache.get(key); }
function setCached(key, val) { 
  if (searchCache.size > 1000) searchCache.clear();
  searchCache.set(key, val); 
  return val;
}

/** Resuelve un ISRC a un id de pista de Deezer usando la API pública (sin auth). */
async function trackIdFromIsrc(isrc) {
  if (!isrc) return null;
  const cacheKey = `isrc:${isrc}`;
  const cached = getCached(cacheKey);
  if (cached) return cached;

  const res = await fetch(`${PUBLIC_API}/track/isrc:${encodeURIComponent(isrc)}`, {
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) return null;
  const json = await res.json();
  if (json && json.id && !json.error) return setCached(cacheKey, String(json.id));
  return null;
}

/** Busca la mejor pista por título + artista (fallback cuando no hay id ni ISRC). */
async function searchTrackId(title, artist) {
  const cacheKey = `search:${title}:${artist}`;
  const cached = getCached(cacheKey);
  if (cached) return cached;

  const q = `track:"${title}" artist:"${artist}"`;
  const res = await fetch(`${PUBLIC_API}/search?q=${encodeURIComponent(q)}&limit=1`, {
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) return null;
  const json = await res.json();
  const first = json && json.data && json.data[0];
  if (first && first.id) return setCached(cacheKey, String(first.id));
  return null;
}

/** Datos de la pista (TRACK_TOKEN + tamaños) desde el gateway autenticado. */
async function songData(session, sngId) {
  const cacheKey = `songData:${sngId}`;
  const cached = getCached(cacheKey);
  if (cached) return cached;

  const data = await session.gw('song.getData', { sng_id: String(sngId) });
  const r = data && data.results;
  if (!r || !r.SNG_ID) throw new Error('La pista no existe en Deezer');
  return setCached(cacheKey, r);
}

/**
 * Obtiene la URL del medio cifrado para el mejor formato disponible, bajando de
 * calidad si el ARL/pista no lo permite. Devuelve { url, format, container, mime }.
 */
async function mediaUrl(session, trackToken, requestedQuality) {
  const chain = FORMAT_CHAINS[requestedQuality] || FORMAT_CHAINS.flac;
  let lastError = 'Sin fuentes disponibles';
  for (const format of chain) {
    const body = {
      license_token: session.licenseToken,
      media: [
        {
          type: 'FULL',
          formats: [{ cipher: 'BF_CBC_STRIPE', format }],
        },
      ],
      track_tokens: [trackToken],
    };
    const res = await fetch(GET_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: '*/*' },
      body: JSON.stringify(body),
    });
    const json = await res.json().catch(() => null);
    const entry = json && json.data && json.data[0];
    if (entry && entry.media && entry.media[0] && entry.media[0].sources && entry.media[0].sources[0]) {
      return {
        url: entry.media[0].sources[0].url,
        format,
        container: CONTAINER_BY_FORMAT[format],
        mime: MIME_BY_FORMAT[format],
      };
    }
    if (entry && entry.errors && entry.errors[0]) {
      lastError = entry.errors[0].message || `Formato ${format} no disponible`;
    }
  }
  throw new Error(lastError);
}

/** Clave Blowfish de una pista: derivada del MD5 de su id + secreto de Deezer. */
function blowfishKey(sngId) {
  const md5 = crypto.createHash('md5').update(String(sngId), 'ascii').digest('hex');
  const key = Buffer.alloc(16);
  for (let i = 0; i < 16; i++) {
    key[i] =
      md5.charCodeAt(i) ^ md5.charCodeAt(i + 16) ^ BLOWFISH_SECRET.charCodeAt(i);
  }
  return key;
}

module.exports = {
  login,
  acquireSession,
  benchArl,
  poolHealth,
  invalidateSession,
  trackIdFromIsrc,
  searchTrackId,
  songData,
  mediaUrl,
  blowfishKey,
  CONTAINER_BY_FORMAT,
  MIME_BY_FORMAT,
};
