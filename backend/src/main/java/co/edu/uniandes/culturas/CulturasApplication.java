package co.edu.uniandes.culturas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * API del catálogo de Culturas Gastronómicas.
 *
 * <p>La auditoría JPA se habilita aquí y no en una clase de configuración aparte
 * porque {@code @EnableJpaAuditing} necesita un {@code AuditorAware} y el proyecto
 * sólo tiene uno; separarlo añadiría un archivo sin añadir claridad.
 */
@SpringBootApplication
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class CulturasApplication {

    public static void main(String[] args) {
        SpringApplication.run(CulturasApplication.class, args);
    }
}
