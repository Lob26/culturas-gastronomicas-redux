locals {
  is_prod = var.environment == "prod"

  # Constante, no una referencia a postgresql_database.n8n.name.
  #
  # `injected_env` lo consumen LOS DOS bloques de módulo, así que apuntar al
  # atributo del recurso creaba un ciclo: base_services -> injected_env ->
  # postgresql_database.n8n -> base_services. El orden real ya lo garantiza el
  # depends_on explícito de module.dependent_services; lo único que hacía falta
  # aquí era el nombre, y este local lo mantiene como fuente única.
  n8n_db_name = "n8n"

  # Etiquetas de inventario en todos los contenedores. Permiten
  # `podman ps --filter label=project=culturas` y un borrado selectivo.
  common_labels = {
    project     = var.project
    environment = var.environment
    managed-by  = "terraform"
  }

  # Terraform NO admite depends_on por instancia dentro de un for_each: el
  # meta-argumento es estático. En vez de renunciar al bucle, el mapa se
  # particiona por nivel de dependencia y se instancian dos bloques de módulo,
  # con un depends_on real entre ellos.
  #
  # Con dos niveles basta para este stack. Si algún día hiciera falta un tercero,
  # el grafo debería construirse fuera de Terraform.
  base_services = {
    for key, svc in var.services : key => svc
    if length(svc.depends_on_keys) == 0
  }

  dependent_services = {
    for key, svc in var.services : key => svc
    if length(svc.depends_on_keys) > 0
  }

  # Secretos y endpoints que un servicio no puede conocer por sí mismo. Se
  # mantienen aparte de `services` para que ese mapa siga siendo declarativo y
  # libre de valores sensibles.
  injected_env = {
    database = {
      POSTGRES_PASSWORD = random_password.postgres.result
    }

    cache = {}

    storage = {
      MINIO_ROOT_USER            = var.minio_root_user
      MINIO_ROOT_PASSWORD        = random_password.minio.result
      MINIO_BROWSER_REDIRECT_URL = "http://localhost:9001"
    }

    automation = {
      # n8n guarda su estado en su propia base, en el mismo servidor. Compartir
      # servidor evita un contenedor más; compartir base mezclaría sus
      # migraciones con las de Flyway.
      DB_TYPE                = "postgresdb"
      DB_POSTGRESDB_HOST     = "database"
      DB_POSTGRESDB_PORT     = tostring(var.services.database.internal_port)
      DB_POSTGRESDB_DATABASE = local.n8n_db_name
      DB_POSTGRESDB_USER     = var.postgres_superuser
      DB_POSTGRESDB_PASSWORD = random_password.postgres.result
      N8N_PORT               = tostring(var.services.automation.internal_port)
      WEBHOOK_URL            = "http://localhost:${var.services.automation.published_port}/"
      # Podman rootless no ofrece host.docker.internal; el nombre equivalente
      # para alcanzar al anfitrión desde el contenedor es este.
      CULTURAS_API_URL = var.host_api_url
      CULTURAS_API_KEY = random_password.api_key.result
      # n8n bloquea $env dentro de las expresiones por defecto, así que sin
      # esto los nodos HTTP fallan con «access to env vars denied» y el webhook
      # devuelve un 500 cuyo cuerpo es sólo «Error in workflow» — el motivo real
      # únicamente aparece en el log del contenedor.
      #
      # Se desbloquea en lugar de guardar la clave en una credencial de n8n
      # porque las credenciales viven en SU base de datos: quedarían fuera de
      # Terraform, no se podrían versionar junto a los workflows y habría que
      # recrearlas a mano tras cada `task nuke`. Con la clave en el entorno, la
      # única fuente de verdad sigue siendo random_password.api_key.
      N8N_BLOCK_ENV_ACCESS_IN_NODE = "false"
    }
  }

  # Montajes de sólo lectura desde el repositorio.
  bind_mounts = {
    automation = [{
      host_path      = abspath("${path.root}/../workflows")
      container_path = "/workflows"
    }]
  }

  # Se unen las salidas de los dos bloques de módulo para que el resto del
  # archivo pueda tratar el stack como una sola colección.
  all_services = merge(module.base_services, module.dependent_services)
}
