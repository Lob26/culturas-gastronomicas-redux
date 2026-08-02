package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.jobs.LinkCheckBroadcaster;
import co.edu.uniandes.culturas.jobs.LinkCheckJob;
import co.edu.uniandes.culturas.jobs.LinkCheckService;
import co.edu.uniandes.culturas.web.error.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/v1/jobs")
@Tag(name = "Trabajos", description = "Verificación de enlaces con progreso en vivo")
public class JobController {

    private final LinkCheckService service;
    private final LinkCheckBroadcaster broadcaster;

    public JobController(LinkCheckService service, LinkCheckBroadcaster broadcaster) {
        this.service = service;
        this.broadcaster = broadcaster;
    }

    @PostMapping("/verificar-enlaces")
    @Operation(summary = "Comprueba todas las imágenes del catálogo y devuelve el id del trabajo")
    public Map<String, Object> start() {
        LinkCheckJob job = service.start();
        return Map.of(
                "jobId", job.id(),
                "total", job.total(),
                "stream", "/api/v1/jobs/%s/stream".formatted(job.id()));
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
