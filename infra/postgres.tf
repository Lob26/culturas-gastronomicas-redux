# Roles, bases, extensiones y permisos como recursos de Terraform.
#
# En la versión con Compose esto vivía en un script de docker-entrypoint-initdb.d,
# que sólo se ejecuta cuando el volumen está vacío: cambiarlo no tenía ningún
# efecto sobre un entorno ya creado, y la única forma de aplicarlo era borrar la
# base. Aquí cada objeto tiene estado, y modificarlo produce un plan.
#
# Todos dependen de que el contenedor exista. Terraform no puede deducirlo: la
# configuración del proveedor se evalúa antes que los recursos, así que la
# relación se declara a mano.

resource "postgresql_role" "app" {
  name     = var.app_db.role
  login    = true
  password = random_password.app_db.result

  # La aplicación no necesita crear bases ni roles; Flyway sólo hace DDL dentro
  # de su propia base, y para eso basta con ser dueño del esquema.
  create_database = false
  create_role     = false
  superuser       = false

  depends_on = [module.base_services]
}

resource "postgresql_database" "app" {
  name              = var.app_db.name
  owner             = postgresql_role.app.name
  encoding          = "UTF8"
  lc_collate        = "C"
  lc_ctype          = "C"
  template          = "template0"
  connection_limit  = -1
  allow_connections = true

  depends_on = [postgresql_role.app]
}

# Base separada para el estado interno de n8n.
resource "postgresql_database" "n8n" {
  name       = local.n8n_db_name
  owner      = var.postgres_superuser
  template   = "template0"
  encoding   = "UTF8"
  lc_collate = "C"
  lc_ctype   = "C"

  depends_on = [module.base_services]
}

# Extensiones declaradas en un mapa y creadas con for_each, para que añadir una
# sea una línea y no un bloque nuevo.
locals {
  app_extensions = {
    vector   = "Búsqueda semántica: columnas vector e índices HNSW (Fase 4)."
    pg_trgm  = "Similitud trigram, tolera erratas en nombres propios donde tsvector no ayuda."
    unaccent = "Los usuarios escriben 'jalapeno' y el corpus dice 'jalapeño'."
  }
}

resource "postgresql_extension" "app" {
  for_each = local.app_extensions

  name     = each.key
  database = postgresql_database.app.name

  # Que un `terraform destroy` no arrastre las extensiones si alguna tabla
  # todavía depende de ellas.
  drop_cascade = false

  depends_on = [postgresql_database.app]
}

# El rol de la aplicación es dueño de la base, pero el esquema public de
# Postgres 15+ ya no da CREATE a PUBLIC: sin esta concesión Flyway falla al
# crear la primera tabla, con un error de permisos que no menciona el esquema.
resource "postgresql_grant" "app_schema" {
  database    = postgresql_database.app.name
  role        = postgresql_role.app.name
  schema      = "public"
  object_type = "schema"
  privileges  = ["CREATE", "USAGE"]

  depends_on = [postgresql_extension.app]
}
