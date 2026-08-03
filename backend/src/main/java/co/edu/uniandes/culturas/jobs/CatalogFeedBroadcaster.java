package co.edu.uniandes.culturas.jobs;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reenvía a los navegadores lo que llega por Redis.
 *
 * <p>Un emisor por pestaña abierta, en una lista copy-on-write: se escribe
 * pocas veces —al abrir o cerrar una pestaña— y se recorre en cada evento, que
 * es exactamente el reparto de lecturas y escrituras para el que existe esa
 * estructura. Un ArrayList sincronizado bloquearía a todos los suscriptores en
 * cada mensaje.
 */
@Component
public class CatalogFeedBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(CatalogFeedBroadcaster.class);

    /** Sin tope, un cliente olvidado mantendría su emisor vivo para siempre. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * Cada cuánto se manda un latido.
     *
     * <p>La API de Servlet no avisa de que un cliente se ha desconectado: sólo
     * se descubre al intentar escribirle. Sin latidos, una pestaña cerrada deja
     * su emisor ocupando memoria hasta que caduque, y además los proxies suelen
     * cortar conexiones inactivas al minuto o dos.
     */
    private static final long HEARTBEAT_SECONDS = 25;

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "catalog-feed-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public CatalogFeedBroadcaster() {
        heartbeats.scheduleAtFixedRate(this::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        subscribers.add(emitter);

        // Los tres desenganches: fin normal, caducidad y error. Faltando
        // cualquiera de ellos la lista crece sin parar, que es la fuga de
        // memoria clásica de SSE.
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));

        try {
            // Un evento inmediato hace que el navegador dé por establecida la
            // conexión; sin él, algunos clientes se quedan esperando cabeceras.
            emitter.send(SseEmitter.event().name("conectado").data("ok"));
        } catch (IOException e) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    /** Lo llama el contenedor de escucha de Redis. */
    public void onMessage(String message) {
        String[] parts = message.split("\\|", 4);
        if (parts.length < 4) {
            log.warn("Mensaje del canal del catálogo con formato inesperado: {}", message);
            return;
        }
        broadcast("cambio", java.util.Map.of(
                "accion", parts[0],
                "tipo", parts[1],
                "slug", parts[2],
                "nombre", parts[3]));
    }

    private void broadcast(String event, Object payload) {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (IOException | IllegalStateException e) {
                // El cliente se fue. Escribir es la única forma de enterarse.
                subscribers.remove(emitter);
                emitter.complete();
            }
        }
    }

    private void beat() {
        // Comentario SSE (línea que empieza por ':'): mantiene viva la conexión
        // sin que el cliente reciba un evento que tenga que interpretar.
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().comment("latido"));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(emitter);
                emitter.complete();
            }
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
        subscribers.forEach(SseEmitter::complete);
        subscribers.clear();
    }

    /**
     * Suscripción al canal de Redis.
     *
     * <p>Se registra sobre el contenedor que ya autoconfigura Spring Boot en
     * lugar de crear uno propio. Tener dos contenedores funciona —lo hacía—
     * pero significa dos conexiones dedicadas a pub/sub para escuchar un único
     * canal, y además deja dos beans del mismo tipo, de modo que cualquier
     * inyección por tipo pasa a ser ambigua. Lo descubrió SocialAndInfraIT al
     * pedir el contenedor: «expected single matching bean but found 2».
     *
     * <p>Va aquí y no en una clase de configuración aparte para que quien lea
     * esta clase vea de un vistazo de dónde salen los mensajes que reparte.
     */
    @Component
    static class Subscription {

        Subscription(RedisMessageListenerContainer container, CatalogFeedBroadcaster broadcaster) {
            MessageListenerAdapter adapter = new MessageListenerAdapter(broadcaster, "onMessage");

            // IMPRESCINDIBLE, y falla en silencio si falta: MessageListenerAdapter
            // deserializa por defecto con JdkSerializationRedisSerializer,
            // mientras que StringRedisTemplate publica texto plano en UTF-8. Sin
            // esta línea el mensaje no se puede deserializar, `onMessage(String)`
            // no llega a invocarse nunca y NO se registra ningún error: la
            // suscripción aparece activa en `redis-cli pubsub channels`, el
            // publicador no se queja, y simplemente no pasa nada.
            adapter.setSerializer(new StringRedisSerializer());
            adapter.afterPropertiesSet();

            container.addMessageListener(adapter, new ChannelTopic(CatalogEvents.CHANNEL));
        }
    }
}
