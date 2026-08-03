# Workflows de n8n

Versionados como JSON para que el repositorio sea la fuente de verdad. La base
de datos de n8n es estado, no código: sin estos archivos, un `task nuke` se
llevaría los workflows y habría que redibujarlos a mano.

| Workflow | Disparador | Qué hace |
|---|---|---|
| `mantenimiento-nocturno.json` | Cron, 03:00 | Reindexa los embeddings pendientes y lanza el verificador de enlaces |
| `reindexar-al-cambiar-el-catalogo.json` | Webhook `POST /webhook/catalogo-cambiado` | Recalcula los vectores pendientes en cuanto cambia una receta |
| `respaldo-nocturno.json` | Cron 02:30 · Webhook `POST /webhook/respaldo-ahora` | Exporta el catálogo a JSON y lo sube a MinIO |
| `resumen-diario.json` | Cron 08:00 · Webhook `POST /webhook/resumen-ahora` | Compone el estado del sistema y marca la ejecución si hay algo que arreglar |

Los dos programados llevan además un webhook «bajo demanda». No es sólo
comodidad: `n8n execute` **no puede arrancar un workflow cuyo disparador es un
cron** —falla con «Missing node to start execution»— así que sin ese segundo
disparador no habría forma de probarlos sin esperar a la hora.

### Qué se alerta y qué sólo se informa

El resumen diario deja la ejecución **en rojo** únicamente si hay recetas sin
vector, que significa que el reindexado falló y la búsqueda semántica lleva
horas degradada sin que nadie se entere —ese carril degrada en silencio a
propósito—. Las imágenes rotas van en el informe pero no marcan nada: el
catálogo sembrado ya trae varias caídas, y alertar de todo lo que no está
perfecto deja el rojo encendido todos los días hasta que deja de significar
nada.

### Identificadores estables

Cada JSON lleva un `id` de nivel superior. Sin él, `n8n import:workflow` crea
un workflow nuevo en cada importación en vez de actualizar el existente: dos
`task n8n:import` seguidos dejaban ocho workflows donde había cuatro, con los
cron duplicados haciendo el trabajo dos veces.

## Por qué esto vive en n8n y no en `@Scheduled`

Son los dos trabajos que se benefician de tener reintentos, historial de
ejecuciones y una interfaz donde ver qué pasó. Un `@Scheduled` que falla a las
3 de la mañana deja una línea en un log que nadie lee. El resto de la lógica
periódica no está aquí: mover a n8n algo que no necesita historial sólo añade
una pieza más que puede estar caída.

## Desplegarlos

```bash
task n8n:import     # carga los JSON en n8n
task n8n:publish    # los activa y reinicia n8n
```

El reinicio no es una precaución: n8n avisa explícitamente de que los cambios
no surten efecto mientras está corriendo, y hasta reiniciarlo el webhook
responde 404.

Para exportar lo que se haya editado desde la interfaz y versionarlo:

```bash
task n8n:export
```

## La trampa de red en Windows

Los nodos HTTP llaman al backend, que corre en el anfitrión y no en un
contenedor. En Linux basta con `host.containers.internal`. **En Podman para
Windows no**: los contenedores viven dentro de una VM de WSL2, así que ese
nombre resuelve a la puerta de enlace de la VM (169.254.1.2) y no a Windows,
que es donde escucha la JVM.

El síntoma engaña. El webhook devuelve `500` con el cuerpo
`{"message":"Error in workflow"}` y nada más; el `ECONNREFUSED` sólo aparece
en `podman logs culturas-automation`.

`task up` detecta la dirección correcta y se la pasa a Terraform. Si se aplica
Terraform a mano, hay que pasarla también:

```bash
terraform -chdir=infra apply \
  -var="host_api_url=http://$(podman machine ssh "ip route | awk '/default/{print \$3}'"):8080"
```

## Acceso a variables de entorno

Las expresiones usan `$env.CULTURAS_API_URL` y `$env.CULTURAS_API_KEY`, que
Terraform inyecta en el contenedor. n8n bloquea `$env` por defecto, así que
`infra/locals.tf` pone `N8N_BLOCK_ENV_ACCESS_IN_NODE=false`.

Se hace así en lugar de guardar la clave en una credencial de n8n porque las
credenciales viven en su base de datos: quedarían fuera de Terraform, no se
podrían versionar junto a estos archivos y habría que recrearlas a mano
después de cada `task nuke`.
