output "container_id" {
  description = "Id del contenedor creado."
  value       = docker_container.this.id
}

output "name" {
  description = "Nombre del contenedor."
  value       = docker_container.this.name
}

output "internal_endpoint" {
  description = "Host:puerto alcanzable desde otros contenedores de la misma red."
  value       = "${var.alias}:${var.service.internal_port}"
}

output "external_endpoint" {
  description = "Host:puerto alcanzable desde la máquina anfitriona."
  value       = "127.0.0.1:${var.service.published_port}"
}

output "volume_name" {
  description = "Nombre del volumen montado, o null si el servicio no persiste nada."
  value       = local.has_volume ? var.volume_name : null
}
