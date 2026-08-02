# ADR 0003 — Los embeddings viven en las tablas del dominio, no en un almacén de vectores aparte

- **Estado:** aceptado
- **Fecha:** 2026-08-02
- **Afecta a:** `V5__vector_search.sql`, `VectorRepository`, `EmbeddingService`, `ReindexService`

## Contexto

La Fase 4 añade búsqueda semántica y recomendaciones. Spring AI 2.0 trae
`PgVectorStore`, una implementación de `VectorStore` sobre pgvector que se
activa con una sola dependencia (`spring-ai-starter-vector-store-pgvector`) y
que crea y gestiona su propio esquema.

Es el camino evidente, y es el que no se ha tomado.

## Decisión

Se usa **el modelo de embeddings de Spring AI** (`spring-ai-starter-model-transformers`,
que da un bean `EmbeddingModel` sobre ONNX Runtime) y **no** su almacén de
vectores. La columna `embedding vector(384)` se añade a `recipe` y a
`gastronomic_culture`, y las consultas KNN son SQL nativo en `VectorRepository`.

## Razones

**Una sola fuente de verdad.** `PgVectorStore` guarda cada documento en una tabla
`vector_store` con su `content` en texto y sus `metadata` en jsonb. Eso es una
copia del nombre, la descripción y los pasos que ya están en `recipe` y
`recipe_step`. Dos copias del mismo texto es un problema de consistencia, no una
optimización: editar una receta deja el documento indexado mintiendo hasta que
algo lo reindexe, y nada en el tipo ni en el esquema obliga a que ese algo
exista. Con la columna en la propia tabla, el texto vive en un sitio y el vector
es metadato derivado de la fila que lo contiene.

**El borrado se resuelve solo.** Al ser una columna, borrar una receta se lleva su
vector. Con un almacén aparte hace falta acordarse de borrar el documento
correspondiente, y olvidarlo no da error: da resultados de búsqueda que apuntan
a recetas que ya no existen.

**El KNN une con la entidad directamente.** La consulta de «recetas parecidas»
ordena por `r.embedding <=> seed.embedding` y ya tiene `r.slug` y `r.name` en la
misma fila. Por el almacén habría que recuperar documentos, leer el slug de sus
metadatos y volver a consultar la tabla real para pintar la tarjeta: un viaje de
ida y vuelta extra por cada búsqueda, para llegar a la fila de la que salió el
texto.

**Las exclusiones son un WHERE.** Recomendar exige excluir lo que el usuario ya
valoró. En SQL es un `NOT EXISTS` contra `rating` dentro de la misma consulta, y
pedir 10 devuelve 10. Con el almacén, `rating` está fuera de su mundo: habría
que pedir de más, filtrar en Java y aceptar que pedir 10 a veces devuelva 3.

## Lo que se pierde

- **Portabilidad del almacén.** `VectorStore` permite cambiar pgvector por Qdrant
  o Pinecone tocando una dependencia. Aquí no: cambiar de motor sería reescribir
  las consultas. Es una migración que este proyecto no tiene previsto hacer, y
  pagar hoy el precio de un desacoplamiento por si acaso es justo lo que
  [YAGNI](https://martinfowler.com/bliki/Yagni.html) desaconseja.
- **Las utilidades que trae el almacén** (troceado de documentos, filtros por
  metadatos con su propio lenguaje de expresiones). No hacen falta: los
  documentos son cortos y los filtros que se necesitan son SQL.
- **Hay que escribir el indexado a mano.** Son unas cien líneas —`ReindexService`
  y las dos consultas de pendientes— frente a llamar a `vectorStore.add()`. A
  cambio, el indexado es incremental por `updated_at` y no incrementa `@Version`,
  cosa que `add()` no puede hacer porque no conoce esas columnas.

## Consecuencias operativas

- El tipo `vector(384)` está acoplado al modelo. `EmbeddingService.DIMENSIONS` lo
  comprueba **al arrancar** contra `EmbeddingModel.dimensions()`, de modo que
  cambiar de modelo sin migrar la columna falla al iniciar y no en la primera
  escritura.
- El carril semántico **degrada, no rompe**: mientras haya filas sin vector, la
  búsqueda sigue respondiendo con los carriles léxico y difuso.
- El primer arranque en una máquina limpia descarga el modelo (~118 MB) y lo
  cachea en `~/.cache/culturas/onnx`. Se carga en el arranque, no en la primera
  consulta.
