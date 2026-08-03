package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.repository.VectorRepository;
import co.edu.uniandes.culturas.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Preguntas en lenguaje natural sobre el catálogo.
 *
 * <p>La respuesta llega por Server-Sent Events en tres tipos de evento:
 * {@code fuentes} primero —para que la interfaz pinte de dónde va a salir la
 * respuesta antes de que exista—, luego {@code texto} por fragmentos, y
 * {@code fin} al terminar. Un error a mitad viaja como {@code error} y no como
 * un código HTTP, porque para entonces la respuesta ya empezó con un 200 y las
 * cabeceras están enviadas.
 */
@RestController
@RequestMapping("/api/v2/asistente")
@Validated
@Tag(name = "Asistente", description = "Respuestas fundamentadas en el catálogo")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    /** Sin tope, una conexión colgada dejaría el hilo ocupado indefinidamente. */
    private static final long TIMEOUT_MS = 120_000L;

    private final RagService rag;

    public AssistantController(RagService rag) {
        this.rag = rag;
    }

    @GetMapping(value = "/preguntar", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Responde una pregunta citando las recetas y culturas en que se basa")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Flujo de eventos con la respuesta"),
            @ApiResponse(responseCode = "401", description = "Hace falta identificarse"),
            @ApiResponse(responseCode = "503", description = "El asistente no está configurado")
    })
    public SseEmitter ask(
            @Parameter(description = "Pregunta en lenguaje natural", example = "¿Qué lleva la carbonara?")
            @RequestParam @NotBlank @Size(min = 3, max = 500) String q) {

        // Se comprueba antes de abrir el flujo: mientras esto siga siendo una
        // petición normal, un 503 es un 503. Una vez emitido el primer evento,
        // el estado ya es 200 y lo único que queda es un evento de error que el
        // cliente tiene que saber interpretar.
        if (!rag.enabled()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "El asistente no está configurado: falta ANTHROPIC_API_KEY.");
        }

        List<VectorRepository.Document> sources = rag.retrieve(q);
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        if (sources.isEmpty()) {
            // Sin fuentes no se pregunta al modelo. Hacerlo gastaría una llamada
            // para obtener, con suerte, un «no lo sé», y con menos suerte una
            // respuesta inventada que es justo lo que este diseño evita.
            complete(emitter, "No encontré nada en el catálogo que responda a eso.");
            return emitter;
        }

        // Hilo virtual: esto es espera de red de principio a fin, que es
        // exactamente para lo que sirven. Fuera del hilo de la petición porque
        // generar tarda segundos y bloquearlo dejaría el contenedor sin hilos
        // con unas pocas preguntas simultáneas.
        Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("fuentes").data(sources.stream()
                        .map(doc -> Map.of(
                                "slug", doc.getSlug(),
                                "nombre", doc.getName(),
                                "tipo", doc.getType()))
                        .toList()));

                rag.answer(q, sources, chunk -> {
                    try {
                        emitter.send(SseEmitter.event().name("texto").data(chunk));
                    } catch (IOException e) {
                        // El cliente cerró la pestaña. La API de Servlet no avisa
                        // de la desconexión de otra forma, así que este fallo al
                        // escribir ES la señal. Se envuelve para romper el bucle
                        // del stream, que no admite excepciones comprobadas.
                        throw new ClientGone(e);
                    }
                });

                emitter.send(SseEmitter.event().name("fin").data(""));
                emitter.complete();
            } catch (ClientGone e) {
                log.debug("El cliente se desconectó mientras se respondía");
                emitter.complete();
            } catch (Exception e) {
                log.warn("Falló la generación de la respuesta", e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("No se pudo completar la respuesta."));
                } catch (IOException ignored) {
                    // Ya no hay a quién contárselo.
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    private void complete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("fuentes").data(List.of()));
            emitter.send(SseEmitter.event().name("texto").data(message));
            emitter.send(SseEmitter.event().name("fin").data(""));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /** Marca de desconexión del cliente; no es un fallo que haya que registrar. */
    private static final class ClientGone extends RuntimeException {
        ClientGone(Throwable cause) {
            super(cause);
        }
    }
}
