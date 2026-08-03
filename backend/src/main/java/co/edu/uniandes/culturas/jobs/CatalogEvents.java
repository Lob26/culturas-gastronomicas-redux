package co.edu.uniandes.culturas.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publicación de cambios del catálogo.
 *
 * <p>Va por Redis y no por un simple bus en memoria, y ese es el punto: con dos
 * instancias detrás de un balanceador, un evento en memoria sólo lo ven los
 * navegadores conectados a <em>esa</em> instancia. Los demás se quedan
 * mirando una lista que nunca se actualiza y nada da error. Redis reparte el
 * evento a todas las instancias, y cada una lo reenvía a sus suscriptores.
 *
 * <p>El mensaje es deliberadamente pobre —tipo, slug y nombre— y no la entidad
 * serializada. Un evento gordo obliga a versionar su formato y tienta a que el
 * cliente lo use como fuente de verdad; con el slug, quien se entera pide el
 * dato fresco por la API si lo necesita.
 */
@Component
public class CatalogEvents {

    private static final Logger log = LoggerFactory.getLogger(CatalogEvents.class);

    public static final String CHANNEL = "culturas:catalogo";

    private final StringRedisTemplate redis;

    public CatalogEvents(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Anuncia un cambio.
     *
     * <p>No lanza nunca. Publicar es un efecto secundario del cambio, no parte
     * de él: si Redis está caído, la receta ya se creó y devolver un error al
     * usuario por eso sería mentirle sobre lo que ha pasado.
     */
    public void publish(String action, String type, String slug, String name) {
        try {
            // Formato de línea simple, separado por barras verticales. Es un
            // canal interno entre instancias del mismo servicio, así que no
            // necesita JSON ni un esquema: lo que cuenta es que sea imposible
            // de romper y trivial de leer en un `redis-cli monitor`.
            redis.convertAndSend(CHANNEL, "%s|%s|%s|%s".formatted(action, type, slug, name));
        } catch (RuntimeException e) {
            log.warn("No se pudo publicar el cambio del catálogo ({} {}): {}", action, slug, e.toString());
        }
    }
}
