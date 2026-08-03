-- Extensiones que en producción crea Terraform con un rol superusuario.
--
-- El rol de la aplicación no puede crearlas —ni debe— así que aquí las crea el
-- script de arranque del contenedor, que sí corre como superusuario. Sin la
-- extensión `vector` la migración V5 falla y no llega a arrancar ni el contexto
-- de Spring, de modo que el fallo no se parecería en nada a su causa.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
