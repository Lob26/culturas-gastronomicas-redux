package co.edu.uniandes.culturas.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cliente de MinIO.
 *
 * <p>Un único bean para toda la aplicación: {@code MinioClient} mantiene un
 * pool de conexiones de OkHttp, así que construirlo por petición abriría un pool
 * nuevo cada vez — el error de rendimiento clásico con clientes HTTP.
 *
 * <p>El bucket y su política de expiración los crea Terraform
 * (proveedor {@code aminueza/minio}), no este código. Que la aplicación creara
 * su propio bucket al arrancar significaría que la infraestructura depende de
 * que el servicio haya corrido antes, y entonces Terraform dejaría de describir
 * el estado real.
 */
@Configuration
public class MinioConfig {

    @Bean
    MinioClient minioClient(CulturasProperties properties) {
        CulturasProperties.Media media = properties.media();
        return MinioClient.builder()
                .endpoint(media.endpoint())
                .credentials(media.accessKey(), media.secretKey())
                .build();
    }
}
