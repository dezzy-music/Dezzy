## graphify (la "araña" del proyecto)

Hay un grafo de conocimiento del código en `graphify-out/graph.json` (~6.000 nodos: funciones, clases, archivos y quién llama a quién). Úsalo ANTES de grep/glob para ubicar código y gastar menos tokens.

Cómo consultarlo (todo local, 0 tokens de API, ~1 s):
- Localizar un símbolo, su archivo:línea y quién lo usa: `graphify explain "NombreFuncion"` (p. ej. `graphify explain "MusicMapScreen"`).
- Relación entre dos piezas: `graphify path "A" "B"`.
- Búsqueda amplia por concepto: `graphify query "<pregunta>" --budget 600` (pon siempre `--budget` para no inundar el contexto).
- Si está conectado el servidor MCP `graphify`, prefiere sus herramientas `get_node`, `get_neighbors` y `shortest_path`.

Reglas:
- NO leas `graphify-out/GRAPH_REPORT.md` completo (son ~70 KB); como mucho, sus primeras 60 líneas para ver los nodos centrales.
- El grafo sólo tiene símbolos de código. Los textos visibles de la UI (p. ej. "Rankings globales") no están: para esos, usa grep.
- Después de modificar código: `graphify update .` (sólo AST, sin coste). Los hooks de git `post-commit` y `post-checkout` ya lo reconstruyen solos.
- `.graphifyignore` excluye `_temp_scripts/`, `design/`, los `build/` y otras carpetas de ruido.
