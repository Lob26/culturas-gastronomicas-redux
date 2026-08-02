package co.edu.uniandes.culturas;

import co.edu.uniandes.culturas.config.CulturasProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(CulturasProperties.class)
public class CulturasApplication {

    public static void main(String[] args) {
        SpringApplication.run(CulturasApplication.class, args);
    }
}
