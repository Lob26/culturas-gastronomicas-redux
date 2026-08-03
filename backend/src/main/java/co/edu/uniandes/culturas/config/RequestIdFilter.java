package co.edu.uniandes.culturas.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Identificador de correlación por petición.
 *
 * <p>Sin esto, depurar un fallo en producción consiste en buscar por marca de
 * tiempo entre las líneas de todas las peticiones simultáneas. Con esto, una
 * sola clave recupera exactamente las líneas de la petición que falló.
 *
 * <p>Se respeta el {@code X-Request-Id} que venga de fuera para que el
 * identificador cruce servicios —si un día n8n o un proxy lo generan, el rastro
 * sigue siendo uno solo— y se genera cuando no viene. Se devuelve siempre en la
 * respuesta: es lo que permite que un usuario que reporta un error pegue el
 * identificador y se encuentre su caso al primer intento.
 *
 * <p>Va el primero de la cadena ({@code HIGHEST_PRECEDENCE}) para que también
 * lleven identificador las líneas que emite la propia cadena de seguridad, que
 * es donde nacen los 401 y 403 que más tarde hay que explicar.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** Clave en el MDC. El formato ECS de Spring Boot vuelca el MDC entero. */
    private static final String MDC_KEY = "requestId";

    /**
     * Tope de longitud de un identificador ajeno.
     *
     * <p>El valor entra en cada línea de log, así que aceptar una cabecera de
     * tamaño arbitrario deja que un tercero infle los ficheros de log a
     * voluntad. Se recorta en vez de rechazar: la petición es válida, lo único
     * dudoso es la etiqueta.
     */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Imprescindible: el hilo vuelve al pool —o el hilo virtual muere—
            // pero el MDC es un ThreadLocal heredable. Sin limpiar, una petición
            // posterior podría escribir sus líneas con el identificador de otra,
            // que es peor que no tener identificador: miente.
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitize(String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return UUID.randomUUID().toString();
        }
        // Sólo caracteres seguros: un identificador ajeno acaba dentro de una
        // línea de log, y admitir saltos de línea permitiría inyectar entradas
        // falsas en el registro (log forging).
        String clean = incoming.replaceAll("[^A-Za-z0-9._-]", "");
        if (clean.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return clean.length() > MAX_LENGTH ? clean.substring(0, MAX_LENGTH) : clean;
    }
}
