'use strict';

/**
 * Orquestador multi-fuente con failover por ISRC. Recibe una pista y prueba los
 * proveedores CONFIGURADOS (los que tienen credencial) en orden, empezando por
 * el preferido; devuelve un "plan" que /stream ejecuta. Así, si una fuente cae
 * o no tiene el track (o no da el formato pedido), otra lo cubre.
 */

const deezer = require('./deezer');
const qobuz = require('./qobuz');

function deezerConfigured(customArl) {
  return !!customArl || !!process.env.DEEZER_ARL;
}
function qobuzConfigured(customQbz) {
  return !!customQbz || !!process.env.QOBUZ_TOKEN;
}

/** Lista de proveedores activos, con el preferido primero. */
function activeProviders(preferred, customArl, customQbz) {
  const all = [];
  if (deezerConfigured(customArl)) all.push('dzr');
  if (qobuzConfigured(customQbz)) all.push('qbz');
  // preferido al frente
  return all.sort((a, b) => (a === preferred ? -1 : b === preferred ? 1 : 0));
}

// ── Resolución por proveedor: localizar la pista y su tamaño (sin descargar) ──

async function planDeezer({ id, isrc, title, artist, quality, customArl }) {
  let sngId = /^\d+$/.test(String(id || '')) ? String(id) : null;
  if (!sngId && isrc) sngId = await deezer.trackIdFromIsrc(isrc);
  if (!sngId && (title || artist)) sngId = await deezer.searchTrackId(title || '', artist || '');
  if (!sngId) return null;

  const { session } = await deezer.acquireSession(false, customArl);
  const song = await deezer.songData(session, sngId);
  const size =
    quality === 'flac'
      ? Number(song.FILESIZE_FLAC || 0) ||
        Number(song.FILESIZE_MP3_320 || 0) ||
        Number(song.FILESIZE_MP3_128 || 0)
      : Number(song.FILESIZE_MP3_320 || 0) || Number(song.FILESIZE_MP3_128 || 0);
  // Sin FLAC disponible y se pidió FLAC: dejamos que otro proveedor lo intente.
  const hasFlac = Number(song.FILESIZE_FLAC || 0) > 0;
  return {
    provider: 'dzr',
    trackId: String(sngId),
    container: quality === 'flac' && hasFlac ? 'flac' : 'mp3',
    size: size > 0 ? size : undefined,
    preferFlac: quality === 'flac',
    hasFlac,
  };
}

async function planQobuz({ id, isrc, title, artist, quality, customQbz }) {
  let track = null;
  if (isrc) track = await qobuz.trackByIsrc(isrc, customQbz);
  if (!track && (title || artist)) track = await qobuz.searchTrackId(title || '', artist || '', customQbz);
  if (!track) return null;
  return {
    provider: 'qbz',
    trackId: String(track.id),
    container: quality === 'flac' ? 'flac' : 'mp3',
    size: undefined, // Qobuz da el tamaño al pedir la URL del archivo
    preferFlac: quality === 'flac',
    hasFlac: !!track.hires || true,
  };
}

const PLANNERS = { dzr: planDeezer, qbz: planQobuz };

/**
 * Devuelve el mejor plan disponible. `preferred` es el proveedor que pidió la
 * app (resolverPath). Si se pidió FLAC, priorizamos un proveedor que lo tenga.
 */
async function plan(preferred, params) {
  const providers = activeProviders(preferred, params.customArl, params.customQbz);
  if (providers.length === 0) {
    throw new Error('El resolver no tiene ninguna credencial configurada (DEEZER_ARL o QOBUZ_TOKEN)');
  }
  const errors = [];
  let fallbackNonFlac = null;

  for (const p of providers) {
    try {
      const result = await PLANNERS[p](params);
      if (!result) continue;
      // Si pidió FLAC y este proveedor no lo tiene, lo guardamos como respaldo
      // y seguimos buscando uno con FLAC real.
      if (result.preferFlac && !result.hasFlac) {
        if (!fallbackNonFlac) fallbackNonFlac = result;
        continue;
      }
      return result;
    } catch (e) {
      errors.push(`${p}: ${e.message}`);
    }
  }
  if (fallbackNonFlac) return fallbackNonFlac;
  throw new Error(
    errors.length ? `Ningún proveedor resolvió (${errors.join(' | ')})` : 'Pista no encontrada',
  );
}

module.exports = { plan, activeProviders, deezerConfigured, qobuzConfigured };
