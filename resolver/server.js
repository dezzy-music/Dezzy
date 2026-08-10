'use strict';

/**
 * Entrypoint del resolver. Vercel lo detecta como servidor Node (Fluid) y le
 * enruta TODO; también sirve para hosting standalone sin límite de tiempo
 * (Railway, Fly.io, VPS, local: `node server.js`). Toda la lógica vive en
 * lib/handlers.js, así corre el mismo código aquí y en las funciones de api/.
 */

const http = require('http');
const { URL } = require('url');
const { dl, stream, health } = require('./lib/handlers');

const PORT = process.env.PORT || 8787;

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  req.query = Object.fromEntries(url.searchParams.entries());

  try {
    if (url.pathname.startsWith('/dl/')) {
      req.query.provider = decodeURIComponent(url.pathname.slice('/dl/'.length));
      return await dl(req, res);
    }
    if (url.pathname === '/stream') return await stream(req, res);
    if (url.pathname === '/health' || url.pathname === '/') return await health(req, res);

    res.statusCode = 404;
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ success: false, message: 'Ruta no encontrada' }));
  } catch (e) {
    if (!res.headersSent) {
      res.statusCode = 500;
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ success: false, message: e.message }));
    } else {
      res.end();
    }
  }
});

server.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(`Dezzy lossless resolver escuchando en http://localhost:${PORT}`);
});
