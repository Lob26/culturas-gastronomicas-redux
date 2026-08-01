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
| Contenedores | Podman (Compose Spec estándar, funciona igual con Docker) |

## Requisitos

- **JDK 25** — `scoop install java/temurin25-jdk` (sin admin) o `winget install EclipseAdoptium.Temurin.25.JDK`
- **Node 22+**
- **Task** — `scoop install main/task`
- **Podman** o Docker. En Windows, Podman necesita WSL2:

  ```powershell
  wsl --install     # requiere admin + reinicio
  ```

  Podman delega `compose` en un proveedor externo; si no está instalado:
  `scoop install main/docker-compose`.

## Arranque

```bash
cp .env.example .env
task setup        # VM de Podman + stack + migraciones + datos de ejemplo
task dev          # backend (8080) y frontend (4200) en paralelo
```

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
