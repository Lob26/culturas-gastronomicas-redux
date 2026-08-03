# Culturas Gastronómicas

Catálogo de culturas gastronómicas del mundo: cocinas, países, recetas, categorías,
productos representativos y restaurantes con estrellas Michelin.

Reescritura completa sobre stack 2026 del proyecto de curso ISIS2603 (Universidad de
los Andes, 2023-10). El código original vive intacto en `../ISISBack` y `../ISISFront`
como referencia; ver [`docs/adr/0001-reescritura-completa.md`](docs/adr/0001-reescritura-completa.md)
para el porqué de reescribir en lugar de migrar.

## Stack

| Capa | Tecnología |
|---|---|
| Backend | Java 25 LTS · Spring Boot 4.1 · Spring Security 7.1 · Hibernate 7.4 |
| Datos | PostgreSQL 18 + pgvector · Redis 8 · MinIO · Flyway |
| Frontend | Angular 22 (zoneless, SSR híbrido) · PrimeNG 22 · Tailwind 4 |
| IA | Spring AI 2.0 · embeddings ONNX en proceso · Ollama para preguntas en lenguaje natural |
| Automatización | n8n |
| Infraestructura | Terraform 1.15 · Podman (funciona igual con Docker) |

## Requisitos

```powershell
scoop bucket add java
scoop install java/temurin25-jdk main/maven main/task main/terraform
```

Además **Node 22+** y **Podman** (o Docker). En Windows, Podman necesita WSL2:

```powershell
wsl --install     # requiere admin + reinicio
```

## Arranque

```bash
task setup        # VM de Podman + terraform apply + migraciones + datos de ejemplo
task dev          # backend (8080) y frontend (4200) en paralelo
```

No hay que copiar `.env.example`: Terraform genera el `.env` con secretos
aleatorios como parte de `task up`.

## Infraestructura

`infra/` es la única fuente de verdad — no hay `compose.yml`. Cuatro proveedores:
`kreuzwerker/docker` para contenedores, red y volúmenes; `cyrilgdn/postgresql`
para roles, bases, extensiones y permisos; `aminueza/minio` para el bucket y su
política de expiración; `hashicorp/random` para los secretos generados.

Todo el stack se describe en un único `map(object)` en `infra/variables.tf` y se
materializa con `for_each` sobre un módulo local, así que añadir un servicio es
añadir una entrada, no copiar un bloque de recursos.

```bash
task infra:plan   # qué cambiaría
task up           # aplicar
task down         # destruye contenedores, CONSERVA los datos
task nuke         # destruye todo, incluidos los volúmenes
```

`down` y `nuke` son distintos a propósito: los volúmenes se declaran en la raíz
y no dentro del módulo, precisamente para que el ciclo de vida de los datos no
esté atado al de los contenedores.

| Servicio | URL |
|---|---|
| Frontend | http://localhost:4200 |
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Consola MinIO | http://localhost:9001 |
| n8n | http://localhost:5678 |

`task --list` muestra todas las tareas disponibles.

## Estructura

```
backend/      Spring Boot 4.1 (Maven wrapper)
frontend/     Angular 22
infra/        Terraform: contenedores, roles, extensiones, buckets y secretos
workflows/    workflows de n8n versionados en JSON
docs/adr/     decisiones de arquitectura
```

## Pantallas

| Ruta | Qué hace | Render |
|---|---|---|
| `/` | Portada y, con sesión, «recomendado para ti» | prerender |
| `/culturas`, `/recetas` | Catálogo paginado | prerender |
| `/culturas/:slug`, `/recetas/:slug` | Detalle, valoraciones, favorito, recetas parecidas | cliente |
| `/recetas/:slug/cocinar` | Modo cocina con temporizador y Wake Lock | cliente |
| `/buscar` | Búsqueda híbrida | cliente |
| `/preguntar` | Asistente, respuesta en streaming con fuentes | cliente |
| `/recetario` | Lo que has guardado | cliente |

Las cuatro últimas van en cliente porque dependen de la query string o de la
sesión, y la sesión vive en `localStorage`, que no existe en el servidor:
prerrenderizarlas serviría el estado «no has entrado» a todo el mundo.

## Búsqueda

Tres carriles fusionados con Reciprocal Rank Fusion (k=60), que opera sobre
posiciones y no sobre puntuaciones — así no hay que normalizar un `ts_rank` de
escala abierta contra una similitud coseno entre 0 y 1:

| Carril | Resuelve | Ejemplo |
|---|---|---|
| Léxico (`tsvector` + GIN) | coincidencia de lexemas, también dentro de los pasos | `esterilla` → Sushi Rolls |
| Difuso (trigramas) | erratas y acentos, donde no queda raíz común | `carbonarra` → Pasta Carbonara |
| Semántico (pgvector + HNSW) | intención, sin compartir un solo carácter | `raw fish with seaweed` → Sushi Rolls |

Los embeddings se calculan en proceso con ONNX Runtime
(`paraphrase-multilingual-MiniLM-L12-v2`, 384 dimensiones): no hay servicio que
levantar ni llamada de red por consulta. El vector vive en las tablas del
dominio y no en un almacén aparte — ver
[`docs/adr/0003-embeddings-en-el-dominio.md`](docs/adr/0003-embeddings-en-el-dominio.md).

El carril semántico **degrada, no rompe**: mientras haya filas sin vector, la
búsqueda responde con los otros dos.

```bash
task reindex    # calcula los embeddings pendientes (idempotente)
```

## Verificación

### El end-to-end completo, de cero

Cuatro terminales, porque tres de las piezas se quedan en primer plano:

```bash
# 1 — infraestructura (Postgres, Redis, MinIO, n8n) y datos de ejemplo
task setup

# 2 — backend
task backend:dev

# 3 — el modelo local. Sólo lo necesita el asistente.
task llm:pull        # una vez, ~2,5 GB
task llm:up          # se queda en primer plano; Ctrl-C para pararlo

# 4 — los tests. Levanta el dev server de Angular por su cuenta.
task reindex         # calcula los embeddings (el backend tiene que estar arriba)
task e2e
```

`task e2e` **no necesita el paso 3**: sin Ollama el asistente responde 503 y su
test comprueba justamente esa degradación. Con Ollama en marcha, el mismo test
consume el flujo de verdad y verifica que la respuesta cita sus fuentes. Las dos
ejecuciones son verdes; la segunda cubre más.

El orden importa en un punto: `task reindex` va **después** de que el backend
arranque y **antes** de `task e2e`, porque el carril semántico necesita vectores
y sin ellos la búsqueda degrada a dos carriles — el test que comprueba el
tercero fallaría con razón.

Al terminar, `task down` para la VM de Podman y devuelve la memoria.

### Consola de tareas

Si prefieres botones a terminales:

```bash
task console        # imprime una URL con token; ábrela
```

Es a `task` lo que Swagger UI es a la API: `task --list --json` hace de
documento de especificación y la consola lo pinta. **La lista no se mantiene a
mano** — añade una tarea al Taskfile y aparece sola, con su descripción.

Node pelado, sin dependencias ni compilación, y deliberadamente fuera del
backend: haría falta el backend en marcha para levantar la infraestructura de
la que depende el backend. Tampoco va en un contenedor, porque su trabajo es
gobernar el `podman` y el `terraform` **del anfitrión**.

Funciona igual en Windows y en Linux. En Windows resuelve `task.exe` por ruta
absoluta para poder lanzarlo con `shell: false` (desde Node 20.12 un `.cmd` no
se puede lanzar sin shell), y al parar una tarea usa `taskkill /T` para llevarse
también a los nietos — parar `llm:up` matando sólo a `task` dejaría `ollama`
vivo ocupando el puerto.

> **Esto ejecuta comandos en tu máquina.** Escucha sólo en `127.0.0.1`, exige un
> token que cambia en cada arranque, y valida `Host` y `Origin` en cada llamada.
> Las tres cosas hacen falta: aunque no esté expuesta a la red, **tu propio
> navegador sí puede llegar**, así que cualquier pestaña abierta podría intentar
> un POST. El token va en una cabecera propia, lo que obliga a preflight, y como
> no se responde nada de CORS ese preflight falla. Los nombres de tarea salen
> siempre del Taskfile —nunca del cliente— y se pasan como argumento con
> `shell: false`, así que no hay forma de colar `up; rm -rf ~`. `nuke` exige
> además escribir su nombre para confirmar.

### Lo que corre en CI

```bash
task verify
```

Cuatro niveles, cada uno probando lo que sólo él puede probar:

| Nivel | Cuántos | Contra qué | Qué cubre |
|---|---|---|---|
| Unitarios | 34 | nada, lógica pura | fusión RRF, slugs, duración de pasos, prompt del asistente |
| Cortes de MockMvc | 18 | servicios dobles | validación de entrada, traducción a RFC 9457, negociación de contenido |
| Integración | 39 | Postgres + Redis reales (Testcontainers) | esquema, restricciones, concurrencia, autorización, pub/sub, cupos |
| End-to-end | 27 | el stack entero + navegador | los recorridos completos, con Ollama si está en marcha |

El énfasis está en los caminos que **fallan**. El feliz lo cubre el end-to-end;
lo que rara vez se prueba —y donde se esconden los fallos de seguridad y los
500 evitables— es qué pasa cuando el cliente se equivoca: cuerpos malformados,
parámetros fuera de rango, duplicados, escrituras concurrentes, tokens
inventados, cupos agotados.

Los tests de integración levantan Postgres con pgvector una sola vez para toda
la suite y **no** descargan el modelo de embeddings: el perfil `test` apaga la
autoconfiguración de IA y aporta un sustituto determinista que deriva el vector
del texto. Lo que ese sustituto no puede comprobar —que los resultados
semánticos tengan sentido— lo comprueba el end-to-end contra el modelo real.

Ese último es la pieza central: una sola ejecución que recorre infraestructura,
base de datos, API, seguridad, búsqueda, trabajos en segundo plano y navegador.
Es también lo que cubre la frontera entre las dos mitades — en 2023 el frontend
llamaba a `/categories/{nombre}` contra un backend que sólo aceptaba `?id=`, y
nada lo detectaba porque ningún test la cruzaba.

Cada bloque comprueba además algo que el proyecto original hacía mal, para que
el test no diga sólo que la reescritura funciona sino en qué se diferencia.

## Notas

- La API permite escritura con registro instantáneo y sin verificación de correo,
  por decisión de diseño: es un proyecto de demostración y la fricción de registro
  no aporta nada. La autorización sí es real — `ROLE_USER` sólo puede editar y
  borrar lo que creó, y eso se valida en el servidor, no ocultando botones.
- Los textos de interfaz y la documentación están en español; los identificadores,
  logs e infraestructura en inglés, siguiendo la convención del repositorio original.
- **No hay ninguna clave de API en este proyecto.** El asistente
  (`/api/v2/asistente/preguntar`) corre sobre Ollama en la misma máquina, y los
  embeddings de la búsqueda van incrustados en la JVM sobre ONNX. Todo el
  catálogo se ejecuta sin cuenta en ningún servicio.
- Con Ollama parado, el asistente responde **503** y todo lo demás funciona
  igual: es una función accesoria, no un requisito para que el catálogo sirva.
  Se sondea de verdad si el proceso está escuchando, porque «configurado» y
  «encendido» no son lo mismo cuando el modelo es un proceso que se arranca a
  mano.
- El asistente exige identidad aunque sea un GET: una generación local ocupa CPU
  o GPU durante segundos, así que dejarlo abierto sería regalar un botón de
  denegación de servicio. **Pendiente**: eso limita quién puede lanzarlo, no con
  qué frecuencia — falta un límite por usuario, para el que Redis ya está en el
  stack.
