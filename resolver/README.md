# Dezzy Lossless Resolver

Resolver **propio** de Dezzy Music para la descarga lossless, sin depender del
backend cerrado de SpotiFLAC (`api.zarz.moe`) ni de sus extensiones `.sflx`.

Descarga FLAC **real** desde Deezer: busca la pista (id directo, por ISRC o por
título/artista), obtiene el medio cifrado con tu cuenta y lo **descifra al vuelo**
(Blowfish stripe) devolviéndolo como un archivo de audio normal. Compatible con
el `ResolverClient` de la app sin cambios: mismo contrato `POST /dl/:provider`.

## Por qué es más fuerte y estable que el de SpotiFLAC

- **Multi-fuente con failover por ISRC**: prueba las fuentes configuradas en orden
  (Deezer → Qobuz) y si una cae o no tiene el track/formato, otra lo cubre.
- **Ingeniería inversa self-healing (Qobuz)**: extrae el `app_id` y los secretos de
  firma EN VIVO del bundle web de Qobuz en cada arranque (cache 6 h). Nunca se rompe
  cuando Qobuz los rota — al contrario que los resolvers que los hardcodean y mueren.
- **Sesión auto-renovable (Deezer)**: pones el ARL UNA vez; el resolver renueva la
  sesión solo cuando el token caduca. Set-once, hands-off.
- **Descifrado en streaming** (Deezer): no guarda nada, entrega mientras procesa.
- **Fallback de formatos real**: FLAC → MP3 320 → MP3 128 según lo que dé la fuente.
- **Tamaño real** en la respuesta para que la barra de progreso de la app sea exacta.
- **Firma HMAC** opcional: `/stream` no queda como proxy abierto.
- **Sin lock-in de OpenSSL**: Blowfish en JS puro, funciona en cualquier runtime.

### La verdad sobre "sin credenciales"
El FLAC de catálogo general SIEMPRE está cifrado y tras una cuenta de pago — no hay
ingeniería inversa que lo evite (SpotiFLAC usa un pool de cuentas propias en su
backend). Lo que esta arquitectura consigue es que configures **una** credencial
**una sola vez** y luego sea estable sola durante meses. La ingeniería inversa
elimina el mantenimiento (secretos que rotan, tokens que caducan), no la credencial.

## Fuentes

| Fuente | Credencial | Qué entrega |
|--------|-----------|-------------|
| **Deezer** | `DEEZER_ARL` (cuenta HiFi → FLAC; gratis → MP3) | FLAC 16/44.1, descifrado al vuelo. **La fuente principal.** |
| **Qobuz** *(opcional)* | `QOBUZ_TOKEN` (suscripción Qobuz) | FLAC hi-res hasta 24/192. app_id+secreto self-healing por ingeniería inversa; Qobuz exige token para buscar y descargar. |

## Configuración (variables de entorno)

| Variable          | Obligatoria | Qué es |
|-------------------|-------------|--------|
| `DEEZER_ARL`      | Sí (fuente principal) | Cookie(s) `arl` de Deezer. **Puedes poner VARIAS separadas por coma o salto de línea** → el resolver las rota con failover y cooldown: si una se satura/expira, otra toma el relevo sola (más estable). Cuenta **HiFi** → FLAC; gratis → MP3. |
| `QOBUZ_TOKEN`     | Opcional    | Token de usuario de una suscripción Qobuz para el failover hi-res 24-bit. Sin él, Qobuz no se activa. |
| `RESOLVER_SECRET` | Recomendada | Secreto para firmar las URLs de `/stream`. Si se omite, el stream queda abierto. |

> **Estabilidad**: pon 2–3 ARLs (tuyos) en `DEEZER_ARL` separados por coma. El
> resolver reparte carga y rota si alguno falla, así "no se cae a cada rato".
> La app ya trae la URL del resolver oficial por defecto: los usuarios no
> configuran nada.

### Cómo obtener el ARL
En un navegador con sesión de Deezer: DevTools → Application → Cookies →
`https://www.deezer.com` → copia el valor de la cookie **`arl`**.

## Endpoints

- `POST /dl/dzr` — body `{ id, isrc, quality, title, artist }` → `{ success, download_url, format, size }`
- `GET /stream?sid=<id>&q=<flac|mp3_320>&sig=<hmac>` — audio descifrado
- `GET /health` — estado del resolver y validez del ARL (sin exponer secretos)

`quality`: `flac` (por defecto) o `mp3_320`.

## Desplegar en Vercel

```bash
cd resolver
vercel --prod
# luego, en el dashboard del proyecto → Settings → Environment Variables:
#   DEEZER_ARL = <tu arl>
#   RESOLVER_SECRET = <cadena aleatoria larga>
# y redeploy
```

> Nota: las funciones de Vercel tienen límite de duración (60 s). Para álbumes
> FLAC muy pesados sobre conexiones lentas conviene un host **sin** límite.

## Desplegar sin límite de tiempo (recomendado para FLAC grande)

Cualquier host que ejecute Node sirve con el servidor incluido:

```bash
cd resolver
npm install
DEEZER_ARL=<arl> RESOLVER_SECRET=<secreto> node server.js
# escucha en http://localhost:8787 (Railway/Fly/VPS: expón ese puerto)
```

## Conectar la app

En Dezzy Music → Ajustes → Descargas lossless → **URL del resolver**, pon la URL
pública del despliegue (p. ej. `https://tu-resolver.vercel.app`).
