package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.jobs.CatalogFeedBroadcaster;
import co.edu.uniandes.culturas.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;

/**
 * Flujos en vivo.
 *
 * <p>Dos: los cambios del catálogo, que llegan por Redis desde cualquier
 * instancia, y las cifras del catálogo, que se consultan cada pocos segundos.
 *
 * <p>Ambos son públicos. Lo que emiten ya es público —qué recetas hay y cuántas
 * son— así que exigir identidad no protegería nada y sí impediría enseñar el
 * catálogo actualizándose solo a quien todavía no ha entrado.
 */
@RestController
@RequestMapping("/api/v2/feed")
@Tag(name = "Flujos", description = "Cambios del catálogo y cifras en vivo por SSE")
public class FeedController {

    private static final Logger log = LoggerFactory.getLogger(FeedController.class);

    /** Cada cuánto se vuelve a consultar y emitir el recuento. */
    private static final long STATS_PERIOD_MS = 5_000L;

    /**
     * Cuánto vive el flujo de cifras.
     *
     * <p>Acotado y no infinito: es un panel, y un panel olvidado en una pestaña
     * consultaría la base cada cinco segundos indefinidamente. Al cerrarse, el
     * navegador reconecta solo si sigue interesado.
     */
    private static final long STATS_TIMEOUT_MS = 10 * 60 * 1000L;

    private final CatalogFeedBroadcaster catalog;
    private final StatsService stats;

    public FeedController(CatalogFeedBroadcaster catalog, StatsService stats) {
        this.catalog = catalog;
        this.stats = stats;
    }

    @GetMapping(value = "/catalogo", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Avisa cuando se crea, edita o borra algo del catálogo")
    public SseEmitter catalogFeed() {
        return catalog.subscribe();
    }

    @GetMapping(value = "/estadisticas", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Cifras del catálogo, actualizadas cada pocos segundos")
    public SseEmitter statsFeed() {
        SseEmitter emitter = new SseEmitter(STATS_TIMEOUT_MS);

        // Hilo virtual: el bucle está dormido casi todo el tiempo y despierta
        // para una consulta corta. Es espera, no cálculo.
        Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
            try {
                while (true) {
                    emitter.send(SseEmitter.event().name("estadisticas").data(stats.snapshot()));
                    Thread.sleep(STATS_PERIOD_MS);
                }
            } catch (IOException | IllegalStateException e) {
                // El cliente cerró. Es la única señal que da la API de Servlet.
                emitter.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("Se cortó el flujo de estadísticas", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
