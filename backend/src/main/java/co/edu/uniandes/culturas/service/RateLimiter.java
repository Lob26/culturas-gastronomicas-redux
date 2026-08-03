package co.edu.uniandes.culturas.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Límite de peticiones por usuario, contado en Redis.
 *
 * <p>En Redis y no en memoria porque un contador en el proceso se reinicia con
 * cada despliegue y no se comparte entre instancias: con dos réplicas detrás de
 * un balanceador, un límite local es en realidad el doble del que se anunció.
 *
 * <p>Es una <strong>ventana fija</strong>, no deslizante. La fija admite una
 * ráfaga a caballo entre dos ventanas —hasta el doble del límite en un instante
 * concreto— y a cambio cuesta una operación y una clave. La deslizante exige
 * guardar la marca de cada petición y podarlas en cada consulta. Para lo que
 * protege esto —que nadie encadene generaciones de un modelo local— el peor
 * caso de la ventana fija es perfectamente aceptable, y decirlo es mejor que
 * llamarlo «rate limiting» sin más.
 */
@Service
public class RateLimiter {

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Consume una unidad del cupo.
     *
     * @return cuántas quedan, o vacío si se agotó
     */
    public Decision tryConsume(String bucket, String subject, int limit, Duration window) {
        // La clave incluye la ventana actual, así que caduca sola al cambiar de
        // ventana: no hace falta ningún proceso de limpieza.
        long slot = System.currentTimeMillis() / window.toMillis();
        String key = "rate:%s:%s:%d".formatted(bucket, subject, slot);

        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // Redis caído. Se deja pasar a propósito: este límite protege un
            // recurso caro, no la integridad de nada, y convertir una caída de
            // la caché en una caída del servicio es cambiar un problema por
            // otro mayor. El fallo se nota igualmente porque el contador no
            // avanza.
            return new Decision(true, limit, window);
        }

        if (count == 1L) {
            // Sólo la primera petición de la ventana fija la caducidad. Hacerlo
            // en todas reiniciaría el reloj con cada llamada y la clave no
            // expiraría nunca mientras hubiera tráfico.
            redis.expire(key, window);
        }

        long remaining = limit - count;
        return new Decision(remaining >= 0, Math.max(0, remaining), window);
    }

    /**
     * @param allowed   si la petición puede seguir
     * @param remaining cuántas quedan en esta ventana
     * @param window    duración de la ventana, para la cabecera Retry-After
     */
    public record Decision(boolean allowed, long remaining, Duration window) {
    }
}
