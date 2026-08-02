# Ningún secreto se escribe en el repositorio. Se generan aquí, viven en el
# state (que está en .gitignore) y salen únicamente hacia el .env local.
#
# special = false en todos: estos valores acaban dentro de URLs JDBC, cadenas de
# conexión y variables de entorno de contenedor, donde los caracteres especiales
# obligan a escapar y provocan fallos que parecen de credenciales incorrectas.
# Se compensa la menor entropía por carácter con más longitud.

resource "random_password" "postgres" {
  length  = 32
  special = false
}

resource "random_password" "app_db" {
  length  = 32
  special = false
}

resource "random_password" "minio" {
  # MinIO exige un mínimo de 8; 40 alfanuméricos dan margen de sobra.
  length  = 40
  special = false
}

resource "random_password" "jwt" {
  # HMAC-SHA256 necesita al menos 32 bytes de clave; 64 caracteres lo garantizan
  # incluso contando en bytes tras la codificación.
  length  = 64
  special = false
}

resource "random_password" "api_key" {
  length  = 48
  special = false
}

# local_sensitive_file (no local_file): escribe con permisos 0600 y omite el
# contenido del plan, que si no aparecería en claro en la salida de terraform.
resource "local_sensitive_file" "env" {
  filename        = "${path.root}/${var.env_file_path}"
  file_permission = "0600"

  content = templatefile("${path.module}/templates/env.tftpl", {
    postgres_host     = "127.0.0.1"
    postgres_port     = var.services.database.published_port
    postgres_db       = var.app_db.name
    postgres_user     = var.app_db.role
    postgres_password = random_password.app_db.result

    redis_port = var.services.cache.published_port

    minio_port     = var.services.storage.published_port
    minio_user     = var.minio_root_user
    minio_password = random_password.minio.result
    minio_bucket   = var.media_bucket

    n8n_port = var.services.automation.published_port

    jwt_secret = random_password.jwt.result
    api_key    = random_password.api_key.result
  })
}
