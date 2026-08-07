variable "name" {
  description = "Nombre final del contenedor, ya prefijado por el proyecto."
  type        = string
}

variable "network_name" {
  description = "Red de contenedores a la que se une el servicio."
  type        = string
}

variable "alias" {
  description = "Alias DNS dentro de la red. Es la clave lógica del servicio (database, cache...)."
  type        = string
}

# El mismo tipo que en el mapa raíz. Repetirlo aquí hace del módulo un contrato
# verificable en lugar de un `any` que sólo falla en tiempo de apply.
variable "service" {
  description = "Definición del servicio."
  type = object({
    image          = string
    internal_port  = number
    published_port = number
    volume_path    = optional(string)
    extra_port     = optional(number)
    memory_mb      = optional(number, 512)
    env            = optional(map(string), {})
    command        = optional(list(string), [])
    healthcheck = optional(object({
      test         = list(string)
      interval     = optional(string, "5s")
      timeout      = optional(string, "5s")
      retries      = optional(number, 24)
      start_period = optional(string, "10s")
    }))
    depends_on_keys = optional(list(string), [])
  })
}

variable "extra_env" {
  description = "Variables inyectadas desde la raíz (secretos generados, endpoints de otros servicios)."
  type        = map(string)
  default     = {}
  sensitive   = true
}

variable "restart" {
  description = "Política de reinicio, decidida por el entorno en la raíz."
  type        = string
  default     = "no"
}

variable "labels" {
  description = "Etiquetas comunes de inventario."
  type        = map(string)
  default     = {}
}

variable "volume_name" {
  description = <<-EOT
    Volumen nombrado que se monta en service.volume_path, o null si el servicio
    no persiste nada.

    El volumen lo crea la raíz, no el módulo, porque su ciclo de vida es
    distinto al del contenedor: `task down` recrea contenedores y conserva los
    datos, y sólo `task nuke` los borra. Con el volumen dentro del módulo las
    dos operaciones serían la misma.
  EOT
  type        = string
  default     = null
}

variable "bind_mounts" {
  description = "Montajes de sólo lectura desde el host: rutas de init, workflows versionados."
  type = list(object({
    host_path      = string
    container_path = string
  }))
  default = []
}
