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
    const p = await providers.plan(preferred, {
      id: body.id,
      isrc: body.isrc,
      title: body.title,
      artist: body.artist,
      quality: String(body.quality || 'flac'),
    });

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
    if (provider === 'qbz') return await streamQobuz(trackId, quality, res);
    return await streamDeezer(trackId, quality, res);
  } catch (e) {
    if (!res.headersSent) jsonError(502, e.message || 'Error al resolver el stream');
    else res.end();
  }
}

async function streamDeezer(sngId, quality, res) {
  if (!process.env.DEEZER_ARL) throw new Error('DEEZER_ARL no configurado');

  let session;
  let song;
  let media;
  let lastErr = null;
  const poolSize = process.env.DEEZER_ARL.split(/[\n,]+/).filter(Boolean).length;
  const attempts = Math.min(3, Math.max(1, poolSize + 1));
  for (let i = 0; i < attempts; i++) {
    let acquired = null;
    try {
      acquired = await deezer.acquireSession(i > 0);
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

async function streamQobuz(trackId, quality, res) {
  const token = process.env.QOBUZ_TOKEN || '';
  const file = await qobuz.fileUrl(trackId, quality, token);
  const upstream = await fetch(file.url);
  if (!upstream.ok || !upstream.body) throw new Error(`Qobuz CDN respondió ${upstream.status}`);

  res.statusCode = 200;
  res.setHeader('Content-Type', file.mime);
  res.setHeader('Content-Disposition', `attachment; filename="track.${file.container}"`);
  const len = upstream.headers.get('content-length');
  if (len) res.setHeader('Content-Length', len);
  res.setHeader('Cache-Control', 'no-store');

  const reader = upstream.body.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    res.write(Buffer.from(value));
  }
  res.end();
}

// ── GET /health ─────────────────────────────────────────────────────────────
async function health(req, res) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  const out = { ok: false, signed: !!process.env.RESOLVER_SECRET, sources: {} };

  if (providers.deezerConfigured()) {
    try {
      const { canLossless } = await deezer.acquireSession();
      out.sources.deezer = { configured: true, ok: true, lossless: canLossless, pool: deezer.poolHealth() };
    } catch (e) {
      out.sources.deezer = { configured: true, ok: false, message: e.message, pool: deezer.poolHealth() };
    }
  } else {
    out.sources.deezer = { configured: false };
  }

  if (providers.qobuzConfigured()) {
    try {
      const cfg = await qobuz.getConfig();
      out.sources.qobuz = { configured: true, reverseEngineered: { appId: cfg.appId, secrets: cfg.secrets.length } };
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
