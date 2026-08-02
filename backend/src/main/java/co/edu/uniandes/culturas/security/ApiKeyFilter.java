package co.edu.uniandes.culturas.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Autentica clientes automatizados por cabecera {@code X-API-Key}.
 *
 * <p>Existe para n8n y para los scripts: ninguno de los dos debería pasar por
 * un formulario de acceso ni renovar tokens. Las peticiones del navegador usan
 * JWT, no esta clave.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private static final String AUTOMATION_PRINCIPAL = "automation";

    private final byte[] expectedKey;

    public ApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String presented = request.getHeader(HEADER);

        // Sólo se establece autenticación si no hay ya una: si el JWT o el
        // Basic resolvieron antes, esta cabecera no debe poder degradar ni
        // sustituir esa identidad.
        if (presented != null && SecurityContextHolder.getContext().getAuthentication() == null && matches(presented)) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    AUTOMATION_PRINCIPAL,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        chain.doFilter(request, response);
    }

    /**
     * Comparación en tiempo constante.
     *
     * <p>Un {@code equals} sobre cadenas corta en el primer carácter distinto,
     * y esa diferencia de tiempo permite reconstruir la clave carácter a
     * carácter. {@link MessageDigest#isEqual} recorre siempre ambos arreglos.
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey);
    }
}
