variable "environment" {
  description = "Entorno objetivo. Cambia la política de reinicio y si se exponen puertos de depuración."
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment debe ser 'dev' o 'prod'."
  }
}

variable "project" {
  description = "Prefijo de todos los nombres de recurso. Permite levantar dos stacks en paralelo."
  type        = string
  default     = "culturas"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.project))
    error_message = "project debe ser minúsculas, empezar por letra y no pasar de 21 caracteres."
  }
}

variable "container_host" {
  description = <<-EOT
    Endpoint de la API de contenedores.

    Podman en Windows: npipe:////./pipe/docker_engine
    Podman en Linux:   unix:///run/user/1000/podman/podman.sock
    Docker:            unix:///var/run/docker.sock

    Ojo con el nombre en Windows: `podman machine start` publica la API
    compatible con Docker en el pipe `docker_engine`, no en uno que lleve el
    nombre de la máquina. Lo confirma la propia salida del arranque:
    "API forwarding listening on: npipe:////./pipe/docker_engine".
  EOT
  type        = string
  default     = "npipe:////./pipe/docker_engine"
}

variable "host_api_url" {
  description = <<-EOT
    Cómo alcanza n8n al backend, que corre en el anfitrión y no en un contenedor.

    El valor por defecto vale en Podman/Docker sobre Linux, donde
    `host.containers.internal` apunta al anfitrión de verdad.

    En Podman para Windows NO vale, y el síntoma engaña: los contenedores
    corren dentro de una VM de WSL2, así que `host.containers.internal`
    resuelve a la puerta de enlace de ESA VM (169.254.1.2) y no a Windows, que
    es donde escucha la JVM. El webhook devuelve entonces un 500 cuyo cuerpo
    es sólo «Error in workflow»; el ECONNREFUSED únicamente aparece en el log
    del contenedor.

    La dirección correcta ahí es la puerta de enlace por defecto de la VM:

      podman machine ssh "ip route | awk '/default/{print \$3}'"

    y se pasa con -var, porque WSL la reasigna y fijarla en el repositorio
    dejaría un valor que caduca en el siguiente reinicio.
  EOT
  type        = string
  default     = "http://host.containers.internal:8080"

  validation {
    condition     = can(regex("^https?://", var.host_api_url))
    error_message = "host_api_url debe incluir el esquema (http:// o https://)."
  }
}

# El corazón del diseño: un único mapa describe TODO el stack. Añadir un
# servicio es añadir una entrada aquí, no copiar un bloque de recursos.
# Los `optional()` con valor por defecto evitan repetir lo que casi nunca cambia.
variable "services" {
  description = "Definición declarativa de cada servicio de infraestructura."

  type = map(object({
    image          = string
    internal_port  = number
    published_port = number

    # Null significa "sin volumen": el bloque dynamic de abajo no se emite.
    volume_path = optional(string)
    memory_mb   = optional(number, 512)
    env         = optional(map(string), {})
    command     = optional(list(string), [])

    healthcheck = optional(object({
      test         = list(string)
      interval     = optional(string, "5s")
      timeout      = optional(string, "5s")
      retries      = optional(number, 24)
      start_period = optional(string, "10s")
    }))

    # Claves de otros servicios de este mismo mapa que deben arrancar antes.
    depends_on_keys = optional(list(string), [])
  }))

  default = {
    database = {
      image         = "docker.io/pgvector/pgvector:0.8.6-pg18-trixie"
      internal_port = 5432
      # 55432 y no 5432: la máquina de desarrollo ya tiene otro Postgres ahí.
      published_port = 55432
      # /var/lib/postgresql, NO /var/lib/postgresql/data.
      #
      # Las imágenes de Postgres 18+ cambiaron la convención: los datos pasan a
      # un subdirectorio por versión mayor (PGDATA=/var/lib/postgresql/18/docker)
      # para que pg_upgrade --link pueda trabajar sin cruzar puntos de montaje.
      # Montando en la ruta antigua el contenedor detecta un volumen suelto en
      # /var/lib/postgresql/data y aborta el arranque en vez de arriesgar una
      # actualización silenciosa. Ver docker-library/postgres#1259.
      volume_path = "/var/lib/postgresql"
      memory_mb   = 1024
      # Deliberadamente SIN POSTGRES_DB: si se declara, el entrypoint de la
      # imagen crea la base y luego postgresql_database.app fallaría con
      # "already exists". La base la crea Terraform, que es quien la gestiona.
      env = {
        LANG = "en_US.utf8"
      }
      healthcheck = {
        test = ["CMD-SHELL", "pg_isready -U postgres"]
      }
    }

    cache = {
      image          = "docker.io/library/redis:8.10-alpine"
      internal_port  = 6379
      published_port = 6379
      memory_mb      = 512
      # Redis es caché y bus de pub/sub para el fan-out de SSE. Nada que viva
      # sólo en Redis es fuente de verdad, así que no se persiste.
      #
      # Va a través de `sh -c` en lugar de como lista de argumentos porque
      # desactivar los snapshots en Redis se escribe `--save ''`, con cadena
      # vacía, y el proveedor de Docker rechaza los elementos vacíos en
      # `command` ("values for command may not be empty"). El `exec` deja a
      # redis-server como PID 1 para que reciba las señales de parada.
      command     = ["sh", "-c", "exec redis-server --save '' --appendonly no --maxmemory 384mb --maxmemory-policy allkeys-lru"]
      healthcheck = { test = ["CMD", "redis-cli", "ping"] }
    }

    storage = {
      image          = "docker.io/minio/minio:RELEASE.2025-09-07T16-13-09Z"
      internal_port  = 9000
      published_port = 9000
      volume_path    = "/data"
      memory_mb      = 512
      command        = ["server", "/data", "--console-address", ":9001"]
      healthcheck    = { test = ["CMD", "mc", "ready", "local"] }
    }

    automation = {
      image          = "docker.io/n8nio/n8n:2.32.7"
      internal_port  = 5678
      published_port = 5678
      volume_path    = "/home/node/.n8n"
      memory_mb      = 768
      env = {
        GENERIC_TIMEZONE                  = "America/Bogota"
        TZ                                = "America/Bogota"
        N8N_DIAGNOSTICS_ENABLED           = "false"
        N8N_VERSION_NOTIFICATIONS_ENABLED = "true"
        N8N_RUNNERS_ENABLED               = "true"
      }
      depends_on_keys = ["database"]
    }
  }

  # Falla en `plan`, no a mitad de `apply`, si dos servicios chocan de puerto.
  validation {
    condition     = length(values(var.services)[*].published_port) == length(distinct(values(var.services)[*].published_port))
    error_message = "Dos servicios declaran el mismo published_port."
  }

  validation {
    condition = alltrue([
      for svc in values(var.services) : svc.published_port > 1024
    ])
    error_message = "published_port debe estar por encima de 1024; los puertos privilegiados requieren admin."
  }

  # Una clave en depends_on_keys que no existe sería un depends_on a la nada.
  validation {
    condition = alltrue(flatten([
      for svc in values(var.services) : [
        for dep in svc.depends_on_keys : contains(keys(var.services), dep)
      ]
    ]))
    error_message = "depends_on_keys referencia un servicio que no está definido en el mapa."
  }
}

variable "postgres_superuser" {
  description = "Rol superusuario del contenedor de Postgres."
  type        = string
  default     = "postgres"
}

variable "app_db" {
  description = "Base y rol que usa la aplicación."
  type = object({
    name = string
    role = string
  })
  default = {
    name = "culturas"
    role = "culturas"
  }
}

variable "minio_root_user" {
  description = "Usuario raíz de MinIO. La contraseña se genera, no se declara."
  type        = string
  default     = "minioadmin"
}

variable "media_bucket" {
  description = "Bucket de imágenes del catálogo."
  type        = string
  default     = "culturas-media"
}

variable "orphan_upload_expiry_days" {
  description = "Días tras los que se purgan las subidas incompletas o huérfanas."
  type        = number
  default     = 7

  validation {
    condition     = var.orphan_upload_expiry_days >= 1 && var.orphan_upload_expiry_days <= 365
    error_message = "orphan_upload_expiry_days debe estar entre 1 y 365."
  }
}

variable "env_file_path" {
  description = "Dónde escribir el .env generado con los secretos. Está en .gitignore."
  type        = string
  default     = "../.env"
}
