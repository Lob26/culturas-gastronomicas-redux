package co.edu.uniandes.culturas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;

/**
 * Configuración propia de la aplicación, tipada y validada al arrancar.
 *
 * <p>Records anidados en lugar de {@code @Value} disperso por las clases: un
 * error de nombre en una propiedad falla al iniciar y no en la primera petición
 * que la necesite.
 */
@ConfigurationProperties(prefix = "culturas")
public record CulturasProperties(
        Media media,
        Security security,
        Jobs jobs,
        Assistant assistant
) {

    /**
     * Respuestas en lenguaje natural sobre el catálogo.
     *
     * <p>La clave es una credencial externa: no la genera Terraform y puede no
     * estar. Es opcional a propósito —{@link #enabled()} lo decide en un solo
     * sitio— para que la aplicación arranque igual sin ella y sólo ese endpoint
     * responda 503. Hacerla obligatoria convertiría una función accesoria en un
     * requisito para que el catálogo funcione.
     */
    public record Assistant(
            String apiKey,
            @DefaultValue("claude-opus-5") String model,
            @DefaultValue("6") int contextSize
    ) {

        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Media(
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey
    ) {
    }

    public record Security(
            String jwtSecret,
            @DefaultValue("12h") Duration jwtTtl,
            String apiKey,
            @DefaultValue({"http://localhost:4200"}) List<String> allowedOrigins
    ) {

        /**
         * Clave HMAC de exactamente 32 bytes.
         *
         * <p>HS256 exige al menos 256 bits; una cadena más corta hace que
         * Nimbus lance al construir el codificador. En vez de obligar a que la
         * propiedad tenga la longitud justa, se deriva con SHA-256, que además
         * evita que una clave larga se trunque de forma arbitraria.
         */
        public byte[] jwtSecretBytes() {
            if (jwtSecret == null || jwtSecret.isBlank()) {
                throw new IllegalStateException(
                        "Falta culturas.security.jwt-secret. Terraform lo genera y lo escribe en .env.");
            }
            try {
                return MessageDigest.getInstance("SHA-256")
                        .digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                // SHA-256 es obligatorio en toda JVM; si falta, algo está muy roto.
                throw new IllegalStateException("Esta JVM no ofrece SHA-256", e);
            }
        }
    }

    public record Jobs(LinkCheck linkCheck) {

        public record LinkCheck(
                @DefaultValue("32") int maxConcurrency,
                @DefaultValue("8s") Duration requestTimeout
        ) {
        }
    }
}
