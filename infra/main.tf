resource "docker_network" "this" {
  name   = "${var.project}-net"
  driver = "bridge"

  dynamic "labels" {
    for_each = local.common_labels
    content {
      label = labels.key
      value = labels.value
    }
  }
}

# Los volúmenes viven en la raíz, no dentro del módulo, porque su ciclo de vida
# es distinto al del contenedor: `task down` destruye los módulos y recrea los
# contenedores conservando los datos; sólo `task nuke` hace un destroy completo
# que se lleva también estos recursos.
resource "docker_volume" "data" {
  for_each = {
    for key, svc in var.services : key => svc
    if svc.volume_path != null
  }

  name = "${var.project}-${each.key}-data"

  dynamic "labels" {
    for_each = local.common_labels
    content {
      label = labels.key
      value = labels.value
    }
  }
}

# Servicios sin dependencias: base de datos, caché, almacenamiento.
module "base_services" {
  source   = "./modules/container_service"
  for_each = local.base_services

  name         = "${var.project}-${each.key}"
  alias        = each.key
  service      = each.value
  network_name = docker_network.this.name
  labels       = local.common_labels
  extra_env    = lookup(local.injected_env, each.key, {})
  bind_mounts  = lookup(local.bind_mounts, each.key, [])
  volume_name  = try(docker_volume.data[each.key].name, null)

  # Reiniciar siempre en producción; en desarrollo un contenedor que muere debe
  # quedarse muerto para que el fallo se note en vez de esconderse en un bucle.
  restart = local.is_prod ? "always" : "no"
}

# Servicios que necesitan que los anteriores existan primero.
module "dependent_services" {
  source   = "./modules/container_service"
  for_each = local.dependent_services

  name         = "${var.project}-${each.key}"
  alias        = each.key
  service      = each.value
  network_name = docker_network.this.name
  labels       = local.common_labels
  extra_env    = lookup(local.injected_env, each.key, {})
  bind_mounts  = lookup(local.bind_mounts, each.key, [])
  volume_name  = try(docker_volume.data[each.key].name, null)
  restart      = local.is_prod ? "always" : "no"

  # Estático y deliberado: es la razón de partir el mapa en dos niveles.
  # Además de los contenedores base, espera a que exista la base de n8n.
  depends_on = [
    module.base_services,
    postgresql_database.n8n,
  ]
}

# Terraform 1.5+: assertion continua que se evalúa en cada plan y apply sin
# bloquear la operación. Documenta una invariante del entorno que ningún
# recurso posee.
check "puerto_postgres_no_colisiona" {
  assert {
    condition     = var.services.database.published_port != 5432
    error_message = "La máquina de desarrollo ya tiene un Postgres en 5432; usa otro puerto publicado."
  }
}

check "presupuesto_de_memoria" {
  assert {
    # La VM de Podman está limitada a 6 GB y fuera de los contenedores todavía
    # corren la JVM y el dev server de Angular.
    condition     = sum(values(var.services)[*].memory_mb) <= 4096
    error_message = "El stack pide más de 4 GB en total y no deja aire para la JVM ni para Angular."
  }
}
