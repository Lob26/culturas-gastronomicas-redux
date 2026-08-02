# Culturas Gastronómicas

Catálogo de culturas gastronómicas del mundo: cocinas, países, recetas, categorías,
productos representativos y restaurantes con estrellas Michelin.

Reescritura completa sobre stack 2026 del proyecto de curso ISIS2603 (Universidad de
los Andes, 2023-10). El código original vive intacto en `../ISISBack` y `../ISISFront`
como referencia; ver [`docs/adr/0001-reescritura-completa.md`](docs/adr/0001-reescritura-completa.md)
para el porqué de reescribir en lugar de migrar.

## Stack

| Capa | Tecnología |
|---|---|
| Backend | Java 25 LTS · Spring Boot 4.1 · Spring Security 7.1 · Hibernate 7.4 |
| Datos | PostgreSQL 18 + pgvector · Redis 8 · MinIO · Flyway |
| Frontend | Angular 22 (zoneless, SSR híbrido) · PrimeNG 22 · Tailwind 4 |
| IA | Spring AI 2.0 · embeddings ONNX locales · Claude para preguntas en lenguaje natural |
| Automatización | n8n |
| Infraestructura | Terraform 1.15 · Podman (funciona igual con Docker) |

## Requisitos

```powershell
scoop bucket add java
scoop install java/temurin25-jdk main/maven main/task main/terraform
```

Además **Node 22+** y **Podman** (o Docker). En Windows, Podman necesita WSL2:

```powershell
wsl --install     # requiere admin + reinicio
```

## Arranque

```bash
task setup        # VM de Podman + terraform apply + migraciones + datos de ejemplo
task dev          # backend (8080) y frontend (4200) en paralelo
```

No hay que copiar `.env.example`: Terraform genera el `.env` con secretos
aleatorios como parte de `task up`.

## Infraestructura

`infra/` es la única fuente de verdad — no hay `compose.yml`. Cuatro proveedores:
`kreuzwerker/docker` para contenedores, red y volúmenes; `cyrilgdn/postgresql`
para roles, bases, extensiones y permisos; `aminueza/minio` para el bucket y su
política de expiración; `hashicorp/random` para los secretos generados.

Todo el stack se describe en un único `map(object)` en `infra/variables.tf` y se
materializa con `for_each` sobre un módulo local, así que añadir un servicio es
añadir una entrada, no copiar un bloque de recursos.

```bash
task infra:plan   # qué cambiaría
task up           # aplicar
task down         # destruye contenedores, CONSERVA los datos
task nuke         # destruye todo, incluidos los volúmenes
```

`down` y `nuke` son distintos a propósito: los volúmenes se declaran en la raíz
y no dentro del módulo, precisamente para que el ciclo de vida de los datos no
esté atado al de los contenedores.

| Servicio | URL |
|---|---|
| Frontend | http://localhost:4200 |
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Consola MinIO | http://localhost:9001 |
| n8n | http://localhost:5678 |

`task --list` muestra todas las tareas disponibles.

## Estructura

```
backend/      Spring Boot 4.1 (Maven wrapper)
frontend/     Angular 22
infra/        init de Postgres, datos semilla
workflows/    workflows de n8n versionados en JSON
docs/adr/     decisiones de arquitectura
```

## Verificación

```bash
task verify     # lo mismo que corre en CI
```

Incluye tests unitarios, slices de MockMvc, integración con Testcontainers contra
Postgres y Redis reales, build y tests del frontend, y una comprobación de que el
cliente TypeScript generado sigue al día respecto al OpenAPI del backend — un
`git diff` no vacío falla el build. Esa última comprobación existe porque en el
proyecto de 2023 el frontend llamaba a `/categories/{nombre}` contra un backend que
sólo aceptaba `?id=`, y nada lo detectaba.

## Notas

- La API permite escritura con registro instantáneo y sin verificación de correo,
  por decisión de diseño: es un proyecto de demostración y la fricción de registro
  no aporta nada. La autorización sí es real — `ROLE_USER` sólo puede editar y
  borrar lo que creó, y eso se valida en el servidor, no ocultando botones.
- Los textos de interfaz y la documentación están en español; los identificadores,
  logs e infraestructura en inglés, siguiendo la convención del repositorio original.
