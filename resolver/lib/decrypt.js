'use strict';

/**
 * Descifrado del stream de Deezer (BF_CBC_STRIPE): el archivo llega en bloques de
 * 2048 bytes y SOLO 1 de cada 3 va cifrado con Blowfish-CBC (IV fijo 0..7, sin
 * padding). Los demás bloques y el resto final (<2048) van en claro.
 *
 * Blowfish en JS puro (`egoroof-blowfish`) porque el `bf-cbc` nativo de OpenSSL3
 * legacy no está en runtimes serverless. IMPORTANTE: se carga con import()
 * DINÁMICO y perezoso — es un paquete ESM, y un `require()` suyo en el nivel
 * superior rompía la interop CJS al empaquetar en Vercel (dejaba los exports de
 * los handlers como "not a function").
 */

const CHUNK = 2048;
const IV = Uint8Array.from([0, 1, 2, 3, 4, 5, 6, 7]);

let BlowfishClass = null;
async function getBlowfish() {
  if (!BlowfishClass) {
    const mod = await import('egoroof-blowfish');
    BlowfishClass = mod.Blowfish || (mod.default && mod.default.Blowfish) || mod.default;
  }
  return BlowfishClass;
}

function decryptBlock(Blowfish, block, key) {
  const bf = new Blowfish(key, Blowfish.MODE.CBC, Blowfish.PADDING.NULL);
  bf.setIv(IV);
  let out = bf.decode(block, Blowfish.TYPE.UINT8_ARRAY);
  // PADDING.NULL puede recortar ceros finales legítimos; re-rellenamos a 2048.
  if (out.length < block.length) {
    const padded = new Uint8Array(block.length);
    padded.set(out);
    out = padded;
  }
  return Buffer.from(out.buffer, out.byteOffset, out.byteLength);
}

/**
 * Lee el `webReadable` (body de fetch) en bloques exactos de 2048, descifra los
 * que toque y va escribiendo el resultado en `res` (respuesta HTTP de Node).
 */
async function pipeDecrypted(webReadable, key, res) {
  const Blowfish = await getBlowfish();
  const reader = webReadable.getReader();
  let carry = Buffer.alloc(0);
  let blockIndex = 0;

  const flushFull = () => {
    while (carry.length >= CHUNK) {
      const block = carry.subarray(0, CHUNK);
      carry = carry.subarray(CHUNK);
      const out = blockIndex % 3 === 0 ? decryptBlock(Blowfish, block, key) : block;
      blockIndex++;
      res.write(out);
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    carry = carry.length ? Buffer.concat([carry, Buffer.from(value)]) : Buffer.from(value);
    flushFull();
  }
  if (carry.length > 0) res.write(carry);
  res.end();
}

module.exports = { pipeDecrypted, CHUNK };
