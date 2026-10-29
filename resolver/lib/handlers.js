'use strict';

/**
 * Handlers HTTP compartidos (fuente única de verdad). Tanto server.js (entrypoint
 * en Vercel / standalone) como las funciones de api/ los reusan, así corre
 * EXACTAMENTE el mismo código en todos lados.
 */

const deezer = require('./deezer');
const qobuz = require('./qobuz');
const providers = require('./providers');
const { pipeDecrypted } = require('./decrypt');
const { sign, verify } = require('./sign');

function readRawBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', () => resolve(''));
  });
}

// ── POST /dl/:provider ──────────────────────────────────────────────────────
async function dl(req, res) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  if (req.method !== 'POST') {
    res.statusCode = 405;
    return res.end(JSON.stringify({ success: false, message: 'Usa POST' }));
  }

  const rawProvider = (req.query && req.query.provider) || 'auto';
  const preferred = rawProvider === 'auto' ? undefined : rawProvider;

  let body = req.body;
  if (body === undefined || body === null) body = await readRawBody(req);
  if (typeof body === 'string') {
    try {
      body = JSON.parse(body);
    } catch (_) {
      body = {};
    }
  }
  body = body || {};

  try {
    const params = {
      id: body.id,
      isrc: body.isrc,
      title: body.title,
      artist: body.artist,
      quality: body.quality || 'flac',
      customArl: req.headers['x-deezer-arl'],
      customQbz: req.headers['x-qobuz-token'],
    };
    const p = await providers.plan(preferred, params);

    const payload = `${p.provider}|${p.trackId}|${body.quality || 'flac'}`;
    const sig = sign(payload);
    const proto = (req.headers['x-forwarded-proto'] || 'https').split(',')[0];
    const host = req.headers['x-forwarded-host'] || req.headers.host;
    const base = `${proto}://${host}`;
    const downloadUrl =
      `${base}/stream?p=${p.provider}&tid=${encodeURIComponent(p.trackId)}` +
      `&q=${encodeURIComponent(String(body.quality || 'flac'))}` +
      (sig ? `&sig=${sig}` : '');

    res.statusCode = 200;
    return res.end(
      JSON.stringify({
        success: true,
        download_url: downloadUrl,
        format: p.container,
        size: p.size,
        source: p.provider,
      }),
    );
  } catch (e) {
    res.statusCode = 200;
    return res.end(JSON.stringify({ success: false, message: e.message || 'Error del resolver' }));
  }
}

// ── GET /stream ─────────────────────────────────────────────────────────────
async function stream(req, res) {
  const q = req.query || {};
  const provider = String(q.p || 'dzr');
  const trackId = String(q.tid || q.sid || '').trim();
  const quality = String(q.q || 'flac');
  const sig = q.sig ? String(q.sig) : '';

  const jsonError = (code, message) => {
    res.statusCode = code;
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ success: false, message }));
  };

  if (!trackId) return jsonError(400, 'Falta tid');
  if (!verify(`${provider}|${trackId}|${quality}`, sig)) return jsonError(403, 'Firma inválida');

  try {
    if (provider === 'qbz') return await streamQobuz(trackId, quality, res, req.headers['x-qobuz-token']);
    return await streamDeezer(trackId, quality, res, req.headers['x-deezer-arl']);
  } catch (e) {
    if (!res.headersSent) jsonError(502, e.message || 'Error al resolver el stream');
    else res.end();
  }
}

async function streamDeezer(sngId, quality, res, customArl = null) {
  if (!customArl && !process.env.DEEZER_ARL) throw new Error('DEEZER_ARL no configurado');

  let session;
  let song;
  let media;
  let lastErr = null;
  const poolSize = customArl ? 1 : (process.env.DEEZER_ARL || '').split(/[\n,]+/).filter(Boolean).length;
  const attempts = Math.min(3, Math.max(1, poolSize + 1));
  for (let i = 0; i < attempts; i++) {
    let acquired = null;
    try {
      acquired = await deezer.acquireSession(i > 0, customArl);
      session = acquired.session;
      song = await deezer.songData(session, sngId);
      media = await deezer.mediaUrl(session, song.TRACK_TOKEN, quality);
      break;
    } catch (e) {
      lastErr = e;
      if (acquired) deezer.benchArl(acquired.arl);
      media = null;
    }
  }
  if (!media) throw lastErr || new Error('No se pudo resolver el medio en Deezer');

  const upstream = await fetch(media.url);
  if (!upstream.ok || !upstream.body) throw new Error(`Deezer CDN respondió ${upstream.status}`);

  const key = deezer.blowfishKey(sngId);
  const safe = (s) => String(s || '').replace(/[^\w\s.-]/g, '').trim() || 'track';
  const filename = `${safe(song.ART_NAME)} - ${safe(song.SNG_TITLE)}.${media.container}`;

  res.statusCode = 200;
  res.setHeader('Content-Type', media.mime);
  res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);
  const len = upstream.headers.get('content-length');
  if (len) res.setHeader('Content-Length', len);
  res.setHeader('Cache-Control', 'no-store');

  await pipeDecrypted(upstream.body, key, res);
}

async function streamQobuz(trackId, quality, res, customQbz = null) {
  const poolSize = customQbz ? 1 : qobuz.poolHealth().total;
  const attempts = Math.min(3, Math.max(1, poolSize + 1));
  let lastErr = null;
  let file = null;

  for (let i = 0; i < attempts; i++) {
    let token = null;
    try {
      token = qobuz.acquireToken(customQbz);
      file = await qobuz.fileUrl(trackId, quality, token);
      break; // Éxito
    } catch (e) {
      if (token) qobuz.benchToken(token);
      lastErr = e;
      file = null;
    }
  }

  if (!file || !file.url) {
    throw lastErr || new Error('No se pudo resolver el medio en Qobuz');
  }

  // ¡Mejora de escalabilidad! En lugar de que Vercel descargue y reenvíe el archivo
  // (lo que consume cuota de ancho de banda y arriesga Timeouts de 10s en Serverless),
  // enviamos un Redirect 302 para que la app lo baje directamente de la CDN de Qobuz.
  res.statusCode = 302;
  res.setHeader('Location', file.url);
  // Cache en la CDN de Vercel por 1 hora. Evita agotar la API de Qobuz para canciones populares.
  res.setHeader('Cache-Control', 'public, s-maxage=3600, stale-while-revalidate=600');
  res.end();
}

// ── GET /health ─────────────────────────────────────────────────────────────
async function health(req, res) {
  const customArl = req.headers['x-deezer-arl'];
  const customQbz = req.headers['x-qobuz-token'];
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  const out = { ok: false, signed: !!process.env.RESOLVER_SECRET, sources: {} };

  if (providers.deezerConfigured(customArl)) {
    try {
      const { canLossless } = await deezer.acquireSession(false, customArl);
      out.sources.deezer = { configured: true, ok: true, lossless: canLossless, pool: deezer.poolHealth() };
    } catch (e) {
      out.sources.deezer = { configured: true, ok: false, message: e.message, pool: deezer.poolHealth() };
    }
  } else {
    out.sources.deezer = { configured: false };
  }

  if (providers.qobuzConfigured(customQbz)) {
    try {
      const cfg = await qobuz.getConfig();
      out.sources.qobuz = { configured: true, reverseEngineered: { appId: cfg.appId, secrets: cfg.secrets.length }, pool: qobuz.poolHealth() };
    } catch (e) {
      out.sources.qobuz = { configured: true, reverseEngineered: false, message: e.message };
    }
  } else {
    out.sources.qobuz = { configured: false };
  }

  out.ok =
    (out.sources.deezer && out.sources.deezer.ok) ||
    (out.sources.qobuz && out.sources.qobuz.configured);

  res.statusCode = 200;
  res.end(JSON.stringify(out));
}

module.exports = { dl, stream, health };
