output "service_endpoints" {
  description = "Dónde escucha cada servicio, desde el host y desde la red de contenedores."
  value = {
    for key, mod in local.all_services : key => {
      external = mod.external_endpoint
      internal = mod.internal_endpoint
      volume   = mod.volume_name
    }
  }
}

output "urls" {
  description = "Enlaces útiles tras un apply."
  value = {
    minio_console = "http://localhost:9001"
    n8n           = "http://localhost:${var.services.automation.published_port}"
    api           = "http://localhost:8080"
    swagger       = "http://localhost:8080/swagger-ui.html"
    frontend      = "http://localhost:4200"
  }
}

output "database_url" {
  description = "URL JDBC de la base de la aplicación, sin credenciales."
  value       = "jdbc:postgresql://127.0.0.1:${var.services.database.published_port}/${postgresql_database.app.name}"
}

output "media_bucket" {
  description = "Bucket de imágenes."
  value       = minio_s3_bucket.media.bucket
}

# Marcado sensible: Terraform lo oculta en la salida de apply y sólo se puede
# leer de forma explícita con `terraform output -raw app_db_password`.
output "app_db_password" {
  description = "Contraseña del rol de la aplicación."
  value       = random_password.app_db.result
  sensitive   = true
}

output "api_key" {
  description = "Clave X-API-Key para n8n y scripts."
  value       = random_password.api_key.result
  sensitive   = true
}

output "env_file" {
  description = "Ruta del .env generado que consumen backend y frontend."
  value       = local_sensitive_file.env.filename
}
