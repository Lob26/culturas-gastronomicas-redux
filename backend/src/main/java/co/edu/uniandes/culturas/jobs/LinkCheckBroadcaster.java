package co.edu.uniandes.culturas.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reparte los eventos de un trabajo a quien esté suscrito por SSE.
 *
 * <p>{@link SseEmitter} no es seguro para uso concurrente y aquí escriben
 * muchos hilos virtuales a la vez, de modo que cada envío se sincroniza sobre
 * el emisor. Sin eso, dos hilos pueden intercalar sus escrituras y producir un
 * flujo SSE corrupto, que el navegador descarta sin decir por qué.
 */
@Component
public class LinkCheckBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LinkCheckBroadcaster.class);

    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String jobId) {
        // Sin caducidad: la cierra explícitamente finish(). Con el valor por
        // defecto de Spring, un trabajo largo vería cortado su flujo a mitad.
        SseEmitter emitter = new SseEmitter(0L);

        List<SseEmitter> list = subscribers.computeIfAbsent(jobId, key -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        // La API de Servlet no avisa de que el cliente se ha ido: sólo se
        // descubre al fallar una escritura. onError es lo más parecido a una
        // notificación de desconexión que hay.
        emitter.onError(error -> list.remove(emitter));

        return emitter;
    }

    public void publish(String jobId, LinkCheckJob.Result result, int checked, int total) {
        send(jobId, "progreso", Map.of(
                "url", result.url(),
                "receta", result.recipeName(),
                "estado", result.status(),
                "rota", result.broken(),
                "comprobadas", checked,
                "total", total));
    }

    public void finish(String jobId, LinkCheckJob job) {
        send(jobId, "fin", Map.of(
                "estado", job.status().name(),
                "comprobadas", job.checked(),
                "rotas", job.broken(),
                "total", job.total()));

        List<SseEmitter> list = subscribers.remove(jobId);
        if (list != null) {
            list.forEach(SseEmitter::complete);
        }
    }

    private void send(String jobId, String eventName, Object payload) {
        List<SseEmitter> list = subscribers.get(jobId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name(eventName).data(payload));
                }
            } catch (IOException | IllegalStateException e) {
                // El cliente cerró la pestaña. No es un error del servidor: se
                // retira el emisor y se sigue con los demás.
                list.remove(emitter);
                log.debug("Suscriptor de {} desconectado: {}", jobId, e.getMessage());
            }
        }
    }
}
