-- Búsqueda de texto completo sobre recetas y culturas.
--
-- Esto es lo que sustituye al buscador de 2023, cuyo botón abría un aviso
-- cuyo texto era, literalmente, "TO-DO".

-- Columna generada en lugar de trigger: se recalcula sola en cada escritura y
-- no hay forma de que se quede desfasada porque una ruta de actualización se
-- olvide de refrescarla.
--
-- La expresión de una columna generada tiene que ser INMUTABLE, y ahí está el
-- detalle: `unaccent()` NO lo es —depende de un diccionario que se puede
-- recargar— así que no puede ir aquí. Los acentos se resuelven por el otro
-- carril, el de trigramas, que sí admite una expresión inmutable.
ALTER TABLE recipe
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (
            setweight(to_tsvector('spanish', coalesce(name, '')), 'A') ||
            setweight(to_tsvector('spanish', coalesce(description, '')), 'B')
        ) STORED;

ALTER TABLE gastronomic_culture
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (
            setweight(to_tsvector('spanish', coalesce(name, '')), 'A') ||
            setweight(to_tsvector('spanish', coalesce(description, '')), 'B')
        ) STORED;

CREATE INDEX idx_recipe_search  ON recipe USING GIN (search_vector);
CREATE INDEX idx_culture_search ON gastronomic_culture USING GIN (search_vector);

-- Segundo carril: similitud por trigramas, que tolera erratas ("carbonarra")
-- y diferencias de acentuación, casos en los que la búsqueda por lexemas no
-- encuentra nada porque no hay raíz en común.
--
-- lower() es inmutable, así que sirve para indexar. La insensibilidad a
-- acentos se aplica en la consulta con unaccent() sobre ambos lados; con un
-- catálogo de este tamaño el coste es irrelevante y la corrección se mantiene.
CREATE INDEX idx_recipe_name_trgm
    ON recipe USING GIN (lower(name) gin_trgm_ops);

CREATE INDEX idx_culture_name_trgm
    ON gastronomic_culture USING GIN (lower(name) gin_trgm_ops);

-- Los pasos también se buscan: "¿en qué receta se usa la esterilla de bambú?"
-- sólo se responde mirando dentro de las instrucciones.
ALTER TABLE recipe_step
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (to_tsvector('spanish', coalesce(instruction, ''))) STORED;

CREATE INDEX idx_step_search ON recipe_step USING GIN (search_vector);
