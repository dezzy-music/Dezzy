# Auditoría · Servidor Listen Together (HF Space `Dezzyyyy/listen-together`)

Fecha: 15/nov/2026 · Servidor v2.0.0 («Spirc monotonic NTP», Rust + axum) · App: `listentogether/` y `together/`

## Resumen

El servidor funciona para el caso básico (crear sala, unirse, play/pause/seek, cola, chat, sugerencias, salas públicas). Sin embargo, la app espera bastante más de lo que el servidor implementa, y el módulo tipo Spotify Connect (`together/`) habla otro protocolo y además apunta a otro servidor.

Sí es multiplataforma a nivel de transporte: WebSocket con JSON o Protobuf, sin nada específico de Android. Lo que no es todavía es un contrato estable que otro cliente (web o escritorio) pueda implementar sin adivinar.

---

## 1. Discrepancias app ↔ servidor

### 1.1 Mensajes que la app envía y el servidor ignora en silencio

El servidor cae en `_ => debug!("Unhandled…")`: no responde nada, y la app se queda esperando.

| La app envía | Qué espera la app | Qué pasa en el servidor |
|---|---|---|
| `approve_join` / `reject_join` | El anfitrión aprueba o rechaza a quien pide entrar | No existe. `join_room` aprueba a todos al instante, aunque `requireHostApprovalToJoin = true` |
| `kick_user` | Expulsar a un invitado | Se ignora. El invitado sigue dentro |
| `transfer_host` | Pasar el control a otro | Se ignora |
| `edit_message` | Editar un mensaje del chat | Se ignora |
| `chat_ack` | Confirmar entregado o leído | Se ignora |

### 1.2 Mensajes que la app escucha y el servidor nunca emite

`join_request`, `join_rejected`, `kicked`, `user_disconnected`, `chat_ack` y `edit_message` tienen manejador en `ListenTogetherClient`, pero el servidor no los envía nunca.

Hay un caso concreto que afecta al usuario: cuando un invitado pierde la conexión, el servidor lo **borra** de la sala (`remove_client_from_room`) en lugar de marcarlo como «desconectado». Por eso nunca llega `user_disconnected`, y el periodo de gracia de reconexión no funciona como se anuncia.

### 1.3 La sincronización «Spirc» que anuncia el servidor no se usa en la app

- El servidor calcula `play_at`, `seq_nbr` y `server_time` y responde a `ping` con la hora del servidor.
- La app **no lee** `playAt` ni `seqNbr`: solo están declarados en `Protocol.kt`. `MusicService` usa `serverTime` como si fuera la hora local.
- Ninguno de los dos lados calcula el desfase de reloj, aunque el README dice «NTP Skew Calibration» y `clock_offset_ms` existe pero no se usa.
- Resultado: el «arranque simultáneo» depende de la latencia de cada teléfono. Si dos mensajes llegan desordenados, no se descarta el viejo.

### 1.4 El módulo tipo Spotify Connect (`together/`) no puede hablar con este servidor

- **Apunta a otro servidor.** `TogetherOnlineEndpoint` descarga la URL de `DezzyServer.txt`, que hoy contiene `https://archivetune-api.koiiverse.cloud`. Es un servidor de terceros (del proyecto de origen), no el tuyo de HF. Las sesiones «en línea» de tus usuarios pasan por un servidor ajeno.
- **Habla otro protocolo.**
  - `together/` usa JSON plano con discriminador `type` (`client_hello`, `server_welcome`, `control_request`, `join_decision`, `kick`, `ban`, `vote_*`, `heartbeat_*`), versión `TogetherProtocolVersion = 1`, solo en tramas de **texto**.
  - El servidor de HF responde a `POST /v1/together/sessions` con `wsUrl: "/ws"`, que es el endpoint del protocolo Spirc (sobres `{type, payload: bytes}`), y siempre envía tramas **binarias**.
  - Aunque cambiaras la URL, `TogetherOnlineHost` descartaría todas las respuestas (`frame as? Frame.Text ?: continue`), y el servidor no entendería `client_hello`.
- **Las claves no sirven.** `hostKey` y `guestKey` del REST nunca se guardan ni se validan. Cualquiera con el código de 6 caracteres entra, y el `guestKey` es decorativo.

### 1.5 Lo que anuncia el README y no se cumple

- **«Resilient State Persistence… atomic serialization».**
  - El estado solo se guarda al recibir `Ctrl-C` (SIGINT). HF y Docker paran con SIGTERM, así que en la práctica nunca se guarda.
  - La escritura no es atómica.
  - El disco del Space es efímero.
  - Tras un reinicio se pierden todas las salas y los `session_token`, y la reconexión de la app falla con `session_not_found`.
- **«Clientes Liner de escritorio y web».** Un cliente de navegador que envíe JSON por texto recibe binario. Funciona, pero `payload` viaja como un **array de números** (`[123,34,…]`): ocupa unas 3,5 veces más y es incómodo de implementar.

### 1.6 Servidores en la lista de la app

`ListenTogetherServers` incluye `metroserver.meowery.eu` (Polonia, operado por un tercero) como alternativa. Si el usuario lo elige, la sala y el chat pasan por ese servidor.

---

## 2. Robustez y seguridad del servidor

| Severidad | Problema | Dónde |
|---|---|---|
| Alta | `POST /v1/together/sessions` crea salas **sin autenticación ni límite**, y una sala sin `host_disconnected_at` nunca se limpia. Basta un bucle para agotar la memoria del Space. | `main.rs`, `manager.rs::cleanup_stale` |
| Alta | Sin límites de tamaño de mensaje, de mensajes por segundo ni de conexiones por IP. | `ws.rs` |
| Alta | Canal de salida `unbounded_channel` por socket: un cliente lento acumula memoria sin tope. | `ws.rs` |
| Media | `SYNC_QUEUE` se salta el tope de 100 canciones; `pending_suggestions` y la longitud del chat no tienen límite. | `room.rs` |
| Media | `SUGGEST_TRACK` no comprueba si la sala permite sugerencias. | `ws.rs` |
| Media | El anfitrión se reasigna a un cliente cualquiera si se le cae la conexión un segundo; al volver ya no es anfitrión. | `manager.rs` |
| Media | La puerta de búfer (`BUFFER_WAIT`) sigue esperando a clientes que ya se desconectaron, hasta el tope de 1800 ms. | `room.rs` |
| Media | Las sesiones (`session_token`) no caducan nunca (`disconnect_at` no se usa). | `manager.rs` |
| Baja | `CorsLayer::permissive`, salas de prueba codificadas (`S6F4D5`, nombres con «Test») y ninguna negociación de versión de protocolo. | `main.rs`, `persistence.rs` |

---

## 3. Propuesta: blindaje

### Fase A · Contrato único

Sin tocar la infraestructura todavía.

1. **Un solo `.proto` de verdad** en `metroproto/` con número de versión. Se generan los tipos de Rust y de Kotlin a partir de él, y se elimina el `Protocol.kt` escrito a mano. El primer mensaje es `hello {protocol_version, client, capabilities[]}`; el servidor responde `welcome {server_version, capabilities[]}`. La app oculta lo que el servidor no soporte (por ejemplo, el botón de expulsar).
2. **JSON legible para clientes web**: `payload` como objeto JSON, no como array de bytes. Responder con texto a quien habla texto y con binario a quien habla binario.
3. **Unificar `together/` con `listentogether/`.** Recomiendo que el modo «Connect» sea un **rol** dentro del mismo protocolo, no un protocolo aparte:
   - **Dispositivo «altavoz»**: reproduce y obedece. Así funciona la TV, otro teléfono o el escritorio.
   - **Dispositivo «mando»**: solo controla, no reproduce.
   - **Sala de escucha**: todos reproducen sincronizados (lo que hay hoy).
   - Para la LAN, el mismo protocolo sirve sobre el `TogetherServer` local (Ktor) con descubrimiento mDNS/NSD. El servidor de HF solo hace de **relé** cuando no están en la misma red.
4. **Cambiar `DezzyServer.txt`** para que apunte a tu servidor, o eliminar el modo en línea de `together/` hasta que esté unificado.

### Fase B · Servidor completo y seguro

1. Implementar lo que falta:
   - `join_request` → `approve_join`/`reject_join`, respetando `requireHostApprovalToJoin`;
   - `kick_user`/`kicked` y una lista de expulsados por sala;
   - `transfer_host`/`host_changed`;
   - `user_disconnected` con **periodo de gracia** de 60–120 s (marcar `is_connected = false`, no borrar);
   - el anfitrión conserva su rol si vuelve dentro de la gracia;
   - `edit_message` y `chat_ack`, o quitarlos de la app.
2. **Autenticación real**:
   - `hostKey` y `guestKey` guardados y firmados (HMAC con caducidad);
   - el WebSocket exige un token en el `hello`;
   - el REST de creación pide un token de app (o `X-Install-Id` firmado) y se limita por IP: unas 5 salas por hora.
3. **Límites**:
   - `max_message_size` de 64 KB;
   - tope de unos 30 mensajes/s por socket con un token bucket;
   - canal acotado (`mpsc::channel(256)`), y si se llena, se cierra el socket del cliente lento;
   - tope de clientes por sala (por ejemplo, 50) y de salas por IP.
4. **Limpieza**: borrar las salas vacías tras 10 min, sin depender de `host_disconnected_at`, y caducar los tokens a las 24 h.
5. **Sincronización de verdad**:
   - la app hace 5 `ping` al entrar, toma la mediana del RTT y calcula `offset = server_time − (t0 + rtt/2)`;
   - usa `play_at − offset` para arrancar;
   - descarta todo mensaje con `seq_nbr` menor que el último aplicado.
6. **Apagado correcto**: escuchar SIGTERM, guardar con escritura atómica (temporal + `rename`) y quitar los hacks de salas de prueba.

### Fase C · Escalado

El estado vive en la memoria de **un solo proceso**. No se puede poner un segundo servidor detrás de un balanceador sin cambiar esto. Hay dos caminos, y recomiendo el primero:

- **Opción 1 (recomendada): estado en Redis o Valkey.**
  - Salas, sesiones y colas en Redis, con tiempo de expiración nativo.
  - Cada mensaje de sala se publica en un canal `room:{code}`, y cada instancia reenvía a sus sockets.
  - Esto da persistencia de verdad (sobrevive a reinicios), varias instancias y limpieza automática.
  - Cabe en un Redis gestionado gratuito (Upstash o Redis Cloud).
- **Opción 2: enrutar por sala.** Afinidad por el código de sala (hash consistente en el balanceador): cada sala vive en una sola instancia. Es más simple, pero un reinicio sigue perdiendo las salas.

Sobre el alojamiento:
- HF Spaces gratis se duerme, tiene disco efímero y una sola réplica. Sirve para pruebas, no para producción.
- Para producción, una instancia pequeña (Fly.io, Railway, un VPS de 4–6 €/mes o Cloud Run con *session affinity*) junto a Redis gestionado.
- Dejar HF como **servidor alternativo**, no como el principal.
- Añadir `/metrics` (Prometheus) con salas, sockets, mensajes/s y latencia de la puerta de búfer, y un `/health` que compruebe la conexión con Redis.

---

## 4. Orden sugerido

1. **Ya mismo**:
   - límite y autenticación en `POST /v1/together/sessions`;
   - limpieza de salas sin anfitrión;
   - tamaño máximo de mensaje y canal acotado;
   - SIGTERM con guardado atómico.
2. **Contrato** (`.proto` versionado y `hello`/`welcome`), y la app oculta lo no soportado.
3. Implementar aprobación, expulsión, traspaso de anfitrión y periodo de gracia.
4. Sincronización con desfase de reloj, `play_at` y `seq_nbr` en la app.
5. Unificar `together/` como rol «Connect» y apuntar `DezzyServer.txt` a tu servidor.
6. Redis y varias instancias.

> Nota de la auditoría: para comprobar el punto 2 (alta), hice **una** llamada a `POST /v1/together/sessions` en el servidor en vivo. Quedó creada la sala privada `HAGKYN` sin nadie dentro. No sale en la lista pública, pero por el mismo fallo no se limpiará hasta que se reinicie el Space.
