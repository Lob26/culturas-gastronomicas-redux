package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.jobs.BackupService;
import co.edu.uniandes.culturas.jobs.LinkCheckBroadcaster;
import co.edu.uniandes.culturas.jobs.LinkCheckJob;
import co.edu.uniandes.culturas.jobs.LinkCheckService;
import co.edu.uniandes.culturas.jobs.ReindexService;
import co.edu.uniandes.culturas.service.StatsService;
import co.edu.uniandes.culturas.web.error.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Trabajos de mantenimiento del catálogo.
 *
 * <p>Arrancar uno devuelve su identificador de inmediato; el progreso se sigue
 * por Server-Sent Events. Devolver el resultado en la misma petición
 * significaría mantenerla abierta durante toda la comprobación.
 */
@RestController
@RequestMapping("/api/v2/jobs")
@Tag(name = "Trabajos", description = "Verificación de enlaces con progreso en vivo")
public class JobController {

    private final LinkCheckService service;
    private final LinkCheckBroadcaster broadcaster;
    private final ReindexService reindex;
    private final BackupService backups;
    private final StatsService statsService;

    public JobController(LinkCheckService service,
                         LinkCheckBroadcaster broadcaster,
                         ReindexService reindex,
                         BackupService backups,
                         StatsService statsService) {
        this.service = service;
        this.broadcaster = broadcaster;
        this.reindex = reindex;
        this.backups = backups;
        this.statsService = statsService;
    }

    /**
     * Recalcula los vectores que falten.
     *
     * <p>Síncrono, al contrario que el verificador de enlaces: aquel abre
     * cientos de conexiones y tarda lo que tarde el sitio más lento de
     * internet, mientras que esto es CPU local sobre las filas pendientes y
     * termina en segundos. Montar el aparato de SSE alrededor sería complejidad
     * por simetría.
     *
     * <p>Es idempotente, así que no necesita protección contra reintentos: la
     * segunda llamada seguida no encuentra nada pendiente y no hace nada.
     */
    /**
     * Respaldo lógico del catálogo a MinIO.
     *
     * <p>Sólo ADMIN: el volcado contiene la tabla de usuarios, así que dejarlo
     * a cualquiera identificado sería regalar el censo.
     */
    @PostMapping("/respaldo")
    @Operation(summary = "Exporta el catálogo a JSON y lo guarda en el almacén de objetos")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> backup() {
        BackupService.Result result = backups.run();
        return Map.of(
                "clave", result.key(),
                "tablas", result.tables(),
                "filas", result.rows(),
                "bytes", result.bytes(),
                "ms", result.millis());
    }

    /** Cifras del catálogo. Las mismas que emite el flujo en vivo, de una vez. */
    @GetMapping("/estadisticas")
    @Operation(summary = "Instantánea de las cifras del catálogo")
    public StatsService.Snapshot stats() {
        return statsService.snapshot();
    }

    @PostMapping("/reindexar")
    @Operation(summary = "Calcula los embeddings pendientes del catálogo")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reindex() {
        ReindexService.Result result = reindex.reindexPending();
        return Map.of(
                "recetas", result.recipes(),
                "culturas", result.cultures(),
                "ms", result.millis(),
                "omitido", result.skipped());
    }

    @PostMapping("/verificar-enlaces")
    @Operation(summary = "Comprueba todas las imágenes del catálogo y devuelve el id del trabajo")
    public Map<String, Object> start() {
        LinkCheckJob job = service.start();
        return Map.of(
                "jobId", job.id(),
                "total", job.total(),
                "stream", "/api/v2/jobs/%s/stream".formatted(job.id()));
    }

    /**
     * Flujo de progreso.
     *
     * <p>Se declara {@code TEXT_EVENT_STREAM_VALUE} explícitamente para que la
     * negociación de contenido no acabe eligiendo JSON, en cuyo caso el cliente
     * recibiría el objeto entero al final en lugar de eventos según ocurren.
     */
    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Sigue el progreso de un trabajo por Server-Sent Events")
    public SseEmitter stream(@PathVariable String jobId) {
        if (service.find(jobId) == null) {
            throw new ResourceNotFoundException("trabajo", jobId);
        }
        return broadcaster.subscribe(jobId);
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Estado final de un trabajo, para quien no siguió el flujo")
    public Map<String, Object> status(@PathVariable String jobId) {
        LinkCheckJob job = service.find(jobId);
        if (job == null) {
            throw new ResourceNotFoundException("trabajo", jobId);
        }
        return Map.of(
                "jobId", job.id(),
                "estado", job.status().name(),
                "total", job.total(),
                "comprobadas", job.checked(),
                "rotas", job.broken(),
                "iniciado", job.startedAt(),
                "resultados", job.results().stream()
                        .filter(LinkCheckJob.Result::broken)
                        .map(result -> Map.of(
                                "url", result.url(),
                                "receta", result.recipeName(),
                                "estado", result.status()))
                        .toList());
    }
}
