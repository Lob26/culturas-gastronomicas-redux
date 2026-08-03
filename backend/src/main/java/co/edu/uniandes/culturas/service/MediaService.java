package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.config.CulturasProperties;
import co.edu.uniandes.culturas.web.error.DomainRuleException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.MinioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Imágenes en MinIO.
 *
 * <p>El catálogo de 2023 enlazaba imágenes a sitios ajenos y hoy cinco de las
 * seis están rotas —lo demuestra el verificador de enlaces—. Poder subirlas es
 * lo que rompe esa dependencia: lo que se sirve deja de depender de que un
 * tercero mantenga una URL viva.
 *
 * <p>Las descargas van por <strong>URL prefirmada</strong> y no a través del
 * backend. Hacer de intermediario obligaría a que cada byte de cada imagen
 * atravesara la JVM, ocupando un hilo durante toda la transferencia para no
 * aportar nada: la firma ya autoriza, y con ella el cliente habla directamente
 * con el almacén.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /**
     * Tipos admitidos.
     *
     * <p>Lista blanca y no negra: enumerar lo prohibido siempre deja fuera algo,
     * y aquí «algo» puede ser un SVG, que el navegador ejecuta como documento y
     * convierte una subida de imagen en XSS almacenado.
     */
    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp", "image/avif");

    /** Cuánto vive una URL firmada. */
    private static final int URL_TTL_MINUTES = 60;

    private final MinioClient client;
    private final String bucket;

    public MediaService(MinioClient client, CulturasProperties properties) {
        this.client = client;
        this.bucket = properties.media().bucket();
    }

    /**
     * Guarda el fichero y devuelve su clave.
     *
     * <p>Se devuelve la clave y no una URL porque la URL caduca: guardarla en la
     * base dejaría enlaces muertos a la hora. La URL se firma al leer.
     */
    public String upload(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED.contains(contentType)) {
            throw new DomainRuleException("imagen-formato-no-admitido",
                    "Formato no admitido: %s. Se aceptan JPEG, PNG, WebP y AVIF."
                            .formatted(contentType == null ? "desconocido" : contentType));
        }
        if (file.isEmpty()) {
            throw new DomainRuleException("imagen-vacia", "El archivo está vacío.");
        }

        // Nombre generado y NO el que trae el fichero. El nombre original es
        // entrada del usuario: puede traer «../», caracteres de control o
        // colisionar con otro. Se conserva sólo la extensión derivada del tipo.
        String key = "recetas/%s.%s".formatted(UUID.randomUUID(), extensionFor(contentType));

        try (InputStream stream = file.getInputStream()) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    // El tercer argumento es el tamaño de parte: -1 deja que el
                    // SDK lo elija. Los tres son Long en MinIO 9, no long.
                    .stream(stream, file.getSize(), -1L)
                    .contentType(contentType)
                    .build());
        } catch (MinioException | IOException e) {
            // Se envuelve con contexto: el mensaje de MinIO por sí solo no dice
            // qué se estaba subiendo ni a dónde.
            throw new IllegalStateException("No se pudo guardar la imagen en el almacén", e);
        }

        log.info("Imagen guardada en el almacén: bucket={} clave={} tipo={} bytes={}",
                bucket, key, contentType, file.getSize());
        return key;
    }

    /** URL temporal de lectura para una clave. */
    public String presignedUrl(String key) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET)
                    .bucket(bucket)
                    .object(key)
                    .expiry(URL_TTL_MINUTES, TimeUnit.MINUTES)
                    .build());
        } catch (MinioException e) {
            throw new IllegalStateException("No se pudo firmar la URL de " + key, e);
        }
    }

    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (MinioException e) {
            throw new IllegalStateException("No se pudo borrar " + key, e);
        }
    }

    /**
     * Sube contenido ya en memoria, para lo que no viene de una petición
     * —los respaldos, por ejemplo—.
     */
    public String put(String key, byte[] content, String contentType) {
        try (InputStream stream = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(stream, (long) content.length, -1L)
                    .contentType(contentType)
                    .build());
            return key;
        } catch (MinioException | IOException e) {
            throw new IllegalStateException("No se pudo guardar " + key, e);
        }
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/avif" -> "avif";
            default -> "jpg";
        };
    }
}
