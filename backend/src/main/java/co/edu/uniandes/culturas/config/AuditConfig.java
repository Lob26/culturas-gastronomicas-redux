package co.edu.uniandes.culturas.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Resuelve quién está haciendo la escritura para rellenar {@code createdBy} y
 * {@code updatedBy}.
 */
@Configuration
public class AuditConfig {

    /**
     * Devuelve el login autenticado, o vacío cuando no hay nadie detrás de la
     * operación.
     *
     * <p>Vacío es el caso normal en tres situaciones legítimas: los datos
     * semilla, las migraciones y los trabajos programados. Por eso las columnas
     * de auditoría son anulables y esto no lanza una excepción.
     *
     * <p>Hasta la Fase 3 no hay autenticación, así que siempre devuelve vacío;
     * el contrato ya queda fijado para que añadir seguridad no obligue a tocar
     * ninguna entidad.
     */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return Optional.empty();
            }
            // El usuario anónimo de Spring Security cuenta como "sin autor":
            // registrar "anonymousUser" en cada fila no aporta nada.
            if ("anonymousUser".equals(auth.getPrincipal())) {
                return Optional.empty();
            }
            return Optional.ofNullable(auth.getName());
        };
    }
}
