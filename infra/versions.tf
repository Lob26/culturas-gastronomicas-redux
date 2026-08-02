terraform {
  required_version = "~> 1.15"

  required_providers {
    # Habla la API de Docker. Podman la expone de forma compatible, pero sólo
    # cuando corre `podman system service`; ver `task infra:socket`.
    docker = {
      source  = "kreuzwerker/docker"
      version = "~> 4.5"
    }
    # Roles, bases y permisos como recursos de primera clase, no como un script
    # de arranque que sólo corre cuando el volumen está vacío.
    postgresql = {
      source  = "cyrilgdn/postgresql"
      version = "~> 1.27"
    }
    minio = {
      source  = "aminueza/minio"
      version = "~> 3.39"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5"
    }
  }

  # Estado local a propósito: es un entorno de desarrollo de una sola máquina y
  # un backend remoto exigiría infraestructura que este proyecto no tiene.
  backend "local" {
    path = "terraform.tfstate"
  }
}

provider "docker" {
  host = var.container_host
}

# Los tres proveedores de abajo se conectan a servicios que este mismo plan
# crea, así que sus recursos dependen de los contenedores vía depends_on.
# Terraform no puede inferir esa relación: la configuración del proveedor se
# evalúa antes que cualquier recurso.
provider "postgresql" {
  host            = "127.0.0.1"
  port            = var.services.database.published_port
  username        = var.postgres_superuser
  password        = random_password.postgres.result
  sslmode         = "disable"
  connect_timeout = 30
  superuser       = false
}

provider "minio" {
  minio_server   = "127.0.0.1:${var.services.storage.published_port}"
  minio_user     = var.minio_root_user
  minio_password = random_password.minio.result
  minio_ssl      = false
}
