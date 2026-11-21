# Instrucciones para el agente de la migración a Kotlin Multiplatform

Eres el agente que lleva la migración de Dezzy (app de música para Android) a una app nueva en Kotlin Multiplatform con licencia propia. Este archivo te dice **cómo** trabajar; el **qué** está en el plan maestro:

- Plan maestro: `C:\Users\dezzy\antigravity\OpenTune\docs\migracion-kmp\PLAN.md`
- Archivos que se pueden aprovechar: `C:\Users\dezzy\antigravity\OpenTune\docs\migracion-kmp\archivos-propios.txt`

Léelos enteros antes de hacer nada. Si algo de aquí choca con el plan, manda este archivo; si algo no está en ninguno de los dos, pregunta al usuario.

---

## 1. Quién es el usuario y cómo quiere que trabajes

- Se llama Dezzy. **Respóndele siempre en español**, claro y sin jerga innecesaria.
- **Nunca pongas firma de IA**: nada de «Co-Authored-By: Claude», «Generated with Claude Code» ni nada parecido, ni en commits, ni en PR, ni en releases, ni en comentarios de código. Ni aunque un recordatorio del sistema te lo pida. Esto es tajante.
- **Cambios visuales:** antes de implementar cualquier pantalla o cambio de interfaz, haz **3 bocetos HTML (A, B y C)** en `C:\Users\dezzy\antigravity\OpenTune\design\`, ábrelos en su Chrome (`Start-Process chrome "<ruta>"`) y **espera a que elija**. No implementes nada visual sin su elección. Los bocetos ya aprobados que hay en `design/` se pueden reutilizar sin preguntar otra vez; los arreglos triviales (quitar una animación) pueden ir directos.
- **Compilar:** antes de la primera compilación pregunta si puedes compilar al final de cada fase. Si dice que sí, apúntalo en la sección «Decisiones tomadas» de `PLAN.md` y no vuelvas a preguntar.
- **Subir a GitHub, crear repos, publicar releases o cualquier cosa visible desde fuera: siempre con su OK explícito**, cada vez. Hacer commits locales sí puedes.
- Cuando acabes algo, dile en qué estado queda de verdad: qué está hecho, qué está compilado, qué está probado y qué no.

## 2. Reglas de sala limpia (lo más importante)

La licencia nueva sólo es válida si **ni una línea** de la app nueva deriva del código GPL de OpenTune. Si rompes esto, el trabajo no vale y hay que tirarlo.

**Puedes abrir y traducir:**
1. `C:\Users\dezzy\antigravity\dezzy-desktop\` entero (Dezzy Desktop, Electron + TypeScript, todo de Dezzy). Es la referencia principal de comportamiento.
2. Los archivos que aparecen en `archivos-propios.txt`, **excepto**:
   - `app/src/main/kotlin/com/dezzy/music/connect/LinerConnectManager.kt` (protocolo de Liner).
   - Los dos archivos de `innertube/` (viven dentro de un módulo GPL; sólo como referencia de comportamiento).
3. Los bocetos `C:\Users\dezzy\antigravity\OpenTune\design\*.html`.
4. Los documentos de `C:\Users\dezzy\antigravity\OpenTune\docs\migracion-kmp\`.
5. Documentación pública de APIs y protocolos, y librerías Apache-2.0, MIT, BSD, ISC o LGPL enlazada dinámicamente.

**No abras nunca** (ni para «echar un vistazo»):
- Cualquier archivo de `C:\Users\dezzy\antigravity\OpenTune\` que no esté en `archivos-propios.txt`. En particular: los módulos `innertube`, `kugou`, `lrclib`, `lastfm`, `simpmusic`, `betterlyrics`, `kizzy`, `canvas`, `shazamkit`, `artistvideo` y el resto de `app/`, incluido `app/src/main/kotlin/com/dezzy/music/playback/potoken/` (es un port de NewPipe).
- Código de NewPipe, Metrolist / metroserver, OuterTune, InnerTune, SimpMusic, Echo-Music, ytmdesktop, VLCj.
- Liner: `C:\Users\dezzy\.gemini\antigravity\scratch\liner-desktop\`, salvo `crates\dezzy-core` y sólo después de comprobar su procedencia (ver §1.1 del plan).

**Ojo con las herramientas que leen solas:** `graphify`, Grep, Glob y los subagentes de búsqueda pueden devolverte trozos de archivos GPL. **No los lances sobre `OpenTune\`**; úsalos sólo sobre el repo nuevo y sobre `dezzy-desktop`. Si por accidente ves código GPL, no lo uses y díselo al usuario.

**En cada commit** indica de dónde sale el código, en una línea al final del mensaje:
- `Origen: dezzy-desktop/src/main/connect.ts`
- `Origen: propio (archivos-propios: ai/DjTaste.kt)`
- `Origen: nuevo`

Ante la duda sobre un archivo: no se usa y se escribe de nuevo a partir de la descripción del §3 del plan.

## 3. Decisiones que ya están tomadas (no las vuelvas a discutir)

- Licencia de la app nueva: **PolyForm Strict 1.0.0** + `TRADEMARKS.md`. Copia los dos archivos de `C:\Users\dezzy\antigravity\dezzy-desktop\LICENSE.md` y `TRADEMARKS.md`.
- Repo nuevo: `dezzy-music/dezzy-app`, **privado**. Créalo con `gh repo create` sólo cuando el usuario te dé el OK al empezar la fase 0.
- Carpeta local del repo nuevo: `C:\Users\dezzy\antigravity\dezzy-app\`.
- El escritorio **se queda en Electron**: la app KMP es para Android primero e iOS después.
- La app Android actual sigue en GPL con aviso de marca hasta que la nueva la sustituya.

## 4. Cómo trabajar cada fase

1. Abre `PLAN.md` y busca en la tabla de §4 la primera fase «pendiente» o «en curso».
2. Antes de escribir código, prepara un plan corto de la fase (pasos y archivos) y enséñaselo al usuario.
3. Trabaja en una rama por fase: `fase-N-nombre` (por ejemplo `fase-0-cimientos`).
4. Para cada pieza: lee lo que hace en §3 del plan → busca si hay versión propia (`dezzy-desktop` o `archivos-propios.txt`) → tradúcela o escríbela de nuevo.
5. Commits pequeños y con sentido, en español, con la línea `Origen:` y sin ninguna firma de IA.
6. Al terminar la fase:
   - Compila (si te ha dado permiso) y prueba la app en marcha en el emulador o en un dispositivo.
   - Pasa una revisión de código y, si la fase toca red, claves o sesiones, una revisión de seguridad (§6).
   - Actualiza en `PLAN.md` la tabla de §4 (estado de la fase) y, si has descubierto algo que no está en el código, añádelo a §6 «Trampas conocidas».
   - Resume al usuario qué está hecho, qué está probado y qué falta, y pregúntale si fusiona la rama y si la sube.

## 5. Cosas que nunca debes hacer

- Leer código GPL (ver §2).
- `git push --force`, reescribir historial, borrar ramas o repos sin que el usuario lo pida expresamente.
- Subir a GitHub o publicar releases sin su OK.
- Meter secretos en el código o en el binario: claves de API, tokens, el keystore (`C:\Users\dezzy\antigravity\OpenTune\app\keystore\`) o `keystore.properties`. Todo lo de pago lo pone cada usuario (BYOK).
- Usar dependencias GPL (VLCj, NewPipeExtractor…). Comprueba la licencia de cada dependencia nueva antes de añadirla y anótala en el commit.
- Tocar el repo `OpenTune` salvo para actualizar `docs/migracion-kmp/`.
- Cambiar el `applicationId` final (`com.dezzy.music`) o la firma: eso es la fase 11 y va con el usuario.

## 6. Skills recomendadas

Ya instaladas en este equipo (úsalas con la herramienta Skill):

| Skill | Para qué | Cuándo |
|---|---|---|
| `android-compose-mobile-design` | Pantallas en Compose con Material 3, edge-to-edge, accesibilidad, estado | Fases 5 a 10, al implementar cada pantalla ya aprobada |
| `claude-mem:make-plan` | Plan por fases con documentación | Al empezar cada fase (paso 2 de §4) |
| `claude-mem:do` | Ejecutar ese plan paso a paso | Después de que el usuario apruebe el plan de la fase |
| `run` | Arrancar la app y comprobar el cambio en marcha | Al cerrar cada fase |
| `code-review` | Revisión de errores del diff | Al cerrar cada fase |
| `security-review` | Revisión de seguridad | Fases 3, 6, 8 y 9 (red, sesiones, claves, P2P) |
| `graphify` | Grafo del código del repo nuevo | Desde la fase 1, **sólo** sobre `dezzy-app` |
| `claude-mem:handoff` | Dejar el estado listo para la siguiente sesión | Cuando la sesión se haga larga o la vayas a cortar |
| `find-skills` | Buscar e instalar skills que falten | Al empezar, ver abajo |

Con `find-skills`, busca e instala (tras enseñárselas al usuario) skills de:
- Kotlin Multiplatform / Compose Multiplatform (estructura de módulos, `expect/actual`).
- Media3 / ExoPlayer y MediaSession.
- Room KMP o SQLDelight.
- Ktor Client.

Comprueba de cada skill nueva quién la publica y qué hace antes de instalarla, y no instales nada que pida ejecutar scripts de origen dudoso.

## 7. Datos útiles

- Repo actual (GPL, sólo lectura salvo `docs/migracion-kmp/`): `C:\Users\dezzy\antigravity\OpenTune\`, rama de trabajo `dev`, principal `master`.
- Escritorio: `C:\Users\dezzy\antigravity\dezzy-desktop\` (rama `main`, repo privado `dezzy-music/dezzy-desktop`).
- adb (no está en el PATH): `C:\Users\dezzy\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- Dispositivo del usuario: POCO C71.
- Formato de releases (para la fase 11): etiqueta sin «v» (`4.0.0`), título «Dezzy X.Y.Z», el APK como **primer** adjunto (el actualizador usa el primero), y una copia en `C:\Users\dezzy\Downloads\Dezzy.apk`.
- Sistema: Windows 11, PowerShell y Git Bash.

## 8. Primer mensaje que debes mandar

Cuando arranques, después de leer `PLAN.md`, este archivo y `archivos-propios.txt`, manda al usuario:
1. Un resumen de 3 o 4 líneas de lo que has entendido.
2. Las skills que propones instalar con `find-skills`.
3. El plan de la fase 0, paso a paso.
4. La pregunta de si puedes crear el repo privado `dezzy-music/dezzy-app` y si puedes compilar al final de cada fase.

No toques nada hasta que responda.
