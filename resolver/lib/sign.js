'use strict';

const crypto = require('crypto');

/**
 * Firma HMAC de las URLs de streaming para que /stream no sea un proxy abierto:
 * solo las URLs que emitió nuestro propio /dl son válidas. Si no hay
 * RESOLVER_SECRET configurado, se omite la verificación (modo abierto).
 */
const SECRET = process.env.RESOLVER_SECRET || '';

function sign(payload) {
  if (!SECRET) return '';
  return crypto.createHmac('sha256', SECRET).update(payload).digest('hex').slice(0, 32);
}

function verify(payload, sig) {
  if (!SECRET) return true; // sin secreto configurado: abierto
  if (!sig) return false;
  const expected = sign(payload);
  // Comparación en tiempo constante
  if (expected.length !== sig.length) return false;
  return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(sig));
}

module.exports = { sign, verify };
