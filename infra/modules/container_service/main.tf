terraform {
  required_providers {
    docker = {
      source  = "kreuzwerker/docker"
      version = "~> 4.5"
    }
  }
}

locals {
  # El proveedor de Docker espera env como lista de "CLAVE=valor", no como mapa.
  # La conversión se hace aquí para que quien llama al módulo siga trabajando
  # con mapas, que sí se pueden combinar con merge().
  env_pairs = [for k, v in merge(var.service.env, var.extra_env) : "${k}=${v}"]

  # Un volumen nombrado sólo tiene sentido si el servicio declara dónde montarlo
  # y la raíz le ha pasado uno ya creado.
  has_volume = var.service.volume_path != null && var.volume_name != null
}

resource "docker_image" "this" {
  name = var.service.image

  # En dev conservar la imagen evita volver a descargar cientos de MB en cada
  # ciclo destroy/apply. En prod se fuerza la descarga para no arrastrar capas viejas.
  keep_locally = var.restart == "no"
}

resource "docker_container" "this" {
  name    = var.name
  image   = docker_image.this.image_id
  restart = var.restart
  memory  = var.service.memory_mb
  env     = local.env_pairs

  # Una lista vacía significa "usa el entrypoint de la imagen".
  command = length(var.service.command) > 0 ? var.service.command : null

  networks_advanced {
    name = var.network_name
    # El alias es lo que permite que el backend se conecte a `database:5432`
    # en lugar de a una IP que cambia en cada recreación.
    aliases = [var.alias]
  }

  ports {
    internal = var.service.internal_port
    external = var.service.published_port
    # Sólo loopback: publicar en 0.0.0.0 expondría Postgres y MinIO sin
    # autenticación fuerte a cualquier equipo de la red local.
    ip = "127.0.0.1"
  }

  dynamic "volumes" {
    for_each = local.has_volume ? [var.service.volume_path] : []
    content {
      volume_name    = var.volume_name
      container_path = volumes.value
    }
  }

  dynamic "volumes" {
    for_each = var.bind_mounts
    content {
      host_path      = volumes.value.host_path
      container_path = volumes.value.container_path
      read_only      = true
    }
  }

  dynamic "healthcheck" {
    # `optional()` sin valor por defecto deja el atributo en null cuando el
    # servicio no declara sonda; el bloque simplemente no se emite.
    for_each = var.service.healthcheck != null ? [var.service.healthcheck] : []
    content {
      test         = healthcheck.value.test
      interval     = healthcheck.value.interval
      timeout      = healthcheck.value.timeout
      retries      = healthcheck.value.retries
      start_period = healthcheck.value.start_period
    }
  }

  dynamic "labels" {
    for_each = var.labels
    content {
      label = labels.key
      value = labels.value
    }
  }

  lifecycle {
    precondition {
      condition     = var.service.memory_mb >= 128
      error_message = "El servicio ${var.alias} pide ${var.service.memory_mb} MB; por debajo de 128 MB ningún contenedor de este stack arranca."
    }
    precondition {
      condition     = !local.has_volume || startswith(var.service.volume_path, "/")
      error_message = "volume_path debe ser una ruta absoluta dentro del contenedor."
    }
  }
}
