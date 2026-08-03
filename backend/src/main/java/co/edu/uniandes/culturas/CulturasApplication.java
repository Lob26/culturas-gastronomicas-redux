package co.edu.uniandes.culturas;

import co.edu.uniandes.culturas.config.CulturasProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * API del catálogo de Culturas Gastronómicas.
 *
 * <p>{@code @EnableJpaAuditing} vive en {@link co.edu.uniandes.culturas.config.AuditConfig}
 * y no aquí. Estaba en esta clase, junto al resto, hasta que los tests de corte
 * lo sacaron a la luz: {@code @WebMvcTest} localiza la clase
 * {@code @SpringBootConfiguration} y aplica sus anotaciones, así que habilitar
 * la auditoría aquí arrastraba JPA a un test que no tiene base de datos y
 * fallaba con «JPA metamodel must not be empty» — un error que no menciona ni
 * la auditoría ni el controlador que se estaba probando.
 */
@SpringBootApplication
@EnableConfigurationProperties(CulturasProperties.class)
public class CulturasApplication {

    public static void main(String[] args) {
        SpringApplication.run(CulturasApplication.class, args);
    }
}
