-- Se ejecuta UNA sola vez, cuando el volumen de Postgres está vacío.
--
-- Aquí van únicamente extensiones y bases de datos: cosas que Flyway no puede
-- crear porque necesitan superusuario o porque deben existir antes de que la
-- aplicación abra su primera conexión. El esquema completo (tablas, índices,
-- triggers) es responsabilidad exclusiva de Flyway y NO debe aparecer en este
-- archivo, o las dos fuentes de verdad se van a contradecir.

-- Búsqueda vectorial (Fase 4). La extensión debe existir antes de que Flyway
-- intente crear una columna `vector(384)`.
CREATE EXTENSION IF NOT EXISTS vector;

-- Similitud trigram: complementa la búsqueda full-text para tolerar errores de
-- tipeo en nombres propios ("carbonarra" -> "carbonara"), donde tsvector no
-- ayuda porque no hay lexema en común.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- unaccent: los usuarios escriben "jalapeno" y el corpus dice "jalapeño".
-- Sin esto la búsqueda en español falla en el caso más común que existe.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Base separada para el estado interno de n8n. Compartir servidor evita un
-- segundo contenedor; compartir base mezclaría sus migraciones con las nuestras.
SELECT 'CREATE DATABASE n8n OWNER culturas'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'n8n')\gexec

-- Configuración de texto en español que ignora acentos. Se usa desde las
-- migraciones de Flyway para construir las columnas tsvector.
CREATE TEXT SEARCH CONFIGURATION es_unaccent (COPY = spanish);
ALTER TEXT SEARCH CONFIGURATION es_unaccent
    ALTER MAPPING FOR hword, hword_part, word
    WITH unaccent, spanish_stem;
