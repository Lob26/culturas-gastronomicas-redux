-- =============================================================================
-- V5 — Búsqueda semántica: columnas vector e índices HNSW.
--
-- El tercer carril de la búsqueda. Los otros dos comparan cadenas: el léxico
-- necesita compartir lexemas y el de trigramas necesita compartir letras.
-- Ninguno encuentra «algo picante con maíz» en un texto que dice «chile» y
-- «tortilla», porque no hay ni un carácter en común. Eso es lo que resuelve
-- este.
--
-- La extensión `vector` la crea Terraform (infra/postgres.tf), no esta
-- migración: crear extensiones exige superusuario y el rol de la aplicación
-- no lo es —ni debe serlo—. Si falta, esto falla en seco al arrancar, que es
-- exactamente lo que debe pasar.
-- =============================================================================

-- 384 dimensiones porque es lo que produce paraphrase-multilingual-MiniLM-L12-v2.
-- El número NO es una preferencia: si el modelo cambia, cambia el tipo de la
-- columna y hace falta una migración. Está fijado en un solo sitio del lado
-- Java (EmbeddingProperties.DIMENSIONS) y se comprueba contra esto al arrancar,
-- para que un cambio de modelo salte al iniciar y no en la primera consulta.
ALTER TABLE recipe               ADD COLUMN embedding vector(384);
ALTER TABLE gastronomic_culture  ADD COLUMN embedding vector(384);

-- Cuándo se calculó el vector. Permite que el reindexado sea incremental
-- —`embedding IS NULL OR embedded_at < updated_at`— en lugar de recalcular el
-- catálogo entero cada noche. Reutiliza el updated_at de la auditoría JPA que
-- ya existe, así que no hace falta guardar un hash del texto de origen.
ALTER TABLE recipe               ADD COLUMN embedded_at TIMESTAMPTZ;
ALTER TABLE gastronomic_culture  ADD COLUMN embedded_at TIMESTAMPTZ;

-- HNSW y no IVFFlat: IVFFlat necesita entrenar las listas sobre datos que ya
-- existan, así que un índice creado sobre una tabla vacía queda inservible
-- hasta reconstruirlo. HNSW se construye incrementalmente y no tiene ese
-- problema, que aquí importa porque los vectores se calculan DESPUÉS de la
-- migración, no antes.
--
-- vector_cosine_ops porque el modelo produce vectores normalizados y lo que
-- compara es dirección, no magnitud. Debe coincidir con el operador de la
-- consulta: un índice cosine no se usa para una consulta con <-> (L2), y el
-- planificador se limita a ignorarlo en silencio.
--
-- Con el catálogo de ejemplo (15 documentos) el planificador hará recorrido
-- secuencial y no tocará estos índices: para tan pocas filas es más barato.
-- Están aquí porque la consulta debe seguir siendo correcta con 15 y con 150
-- mil, y añadir el índice después, sobre una tabla caliente, es un candado
-- largo que aquí no cuesta nada evitar.
CREATE INDEX idx_recipe_embedding_hnsw
    ON recipe USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_culture_embedding_hnsw
    ON gastronomic_culture USING hnsw (embedding vector_cosine_ops);

COMMENT ON COLUMN recipe.embedding IS
    'Vector semántico de nombre+descripción+pasos. NULL = pendiente de indexar.';
COMMENT ON COLUMN gastronomic_culture.embedding IS
    'Vector semántico de nombre+descripción. NULL = pendiente de indexar.';
