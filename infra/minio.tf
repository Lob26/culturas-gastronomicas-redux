# Almacenamiento de las imágenes del catálogo.
#
# El proyecto de 2023 enlazaba en caliente a sitios de terceros: los datos
# semilla apuntan a vecteezy, istock y wordpress, y varias de esas URLs ya están
# muertas. Con un bucket propio las imágenes dejan de depender de que un tercero
# siga sirviéndolas, y el verificador de enlaces de la Fase 5 puede rehospedar
# lo que encuentre roto en vez de sólo señalarlo.

resource "minio_s3_bucket" "media" {
  bucket = var.media_bucket

  # Lectura anónima: las imágenes se sirven directamente al navegador y firmar
  # cada GET obligaría a proxearlas por el backend sin ganar nada, porque un
  # catálogo público no tiene nada que ocultar. La escritura sí exige credenciales.
  acl = "public-read"

  # En desarrollo `terraform destroy` debe poder llevarse el bucket con objetos
  # dentro; en producción eso sería una pérdida de datos silenciosa.
  force_destroy = !local.is_prod

  depends_on = [module.base_services]
}

# Las subidas se hacen con URL prefirmada directamente desde el navegador, así
# que un usuario que abandona el formulario deja un objeto que ninguna fila de
# la base referencia. Esta regla los caduca en vez de acumularlos para siempre.
resource "minio_ilm_policy" "expire_orphans" {
  bucket = minio_s3_bucket.media.bucket

  rule {
    id         = "expirar-subidas-huerfanas"
    status     = "Enabled"
    expiration = "${var.orphan_upload_expiry_days}d"
    filter     = "staging/"
  }
}
