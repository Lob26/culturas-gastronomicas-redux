package co.edu.uniandes.culturas.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Infraestructura efímera para los tests de integración.
 *
 * <p>Postgres es el de verdad, con la misma imagen que usa
 * {@code infra/variables.tf}: si aquí se probara contra un Postgres normal, las
 * migraciones que crean columnas {@code vector} e índices HNSW no correrían, y
 * justamente lo que se quiere comprobar es que el esquema completo se aplica.
 *
 * <p>Los contenedores se arrancan <strong>una vez por JVM</strong> y no se
 * paran: se declaran {@code static} y sin {@code @Container}, de modo que
 * Testcontainers los reutiliza mientras dure la suite y Ryuk los recoge al
 * final. Levantarlos y tirarlos por clase multiplicaría el tiempo por el número
 * de clases sin comprobar nada más.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainers {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("docker.io/pgvector/pgvector:0.8.6-pg18-trixie")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("culturas")
            .withUsername("culturas")
            .withPassword("culturas")
            // Las extensiones las crea Terraform en producción, con un rol
            // superusuario que la aplicación no tiene. Aquí las crea el script
            // de arranque, porque sin la extensión `vector` la migración V5
            // falla y no arrancaría ni el contexto.
            .withInitScript("db/test-extensions.sql");

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("docker.io/library/redis:8.10-alpine"))
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * {@code DynamicPropertyRegistrar} y no {@code @DynamicPropertySource}: el
     * segundo exige un método estático en la propia clase de test, lo que
     * obligaría a repetirlo en todas. Como bean, se registra una vez y vale
     * para todo el contexto.
     */
    @Bean
    DynamicPropertyRegistrar containerProperties() {
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        };
    }

    /**
     * Embeddings deterministas, sin descargar nada.
     *
     * <p>El modelo real son 118 MB de ONNX que se bajan de HuggingFace al
     * arrancar. Meter eso en la suite la haría depender de la red y de un
     * tercero para comprobar cosas que no tienen que ver con la calidad de los
     * vectores, y además la volvería lenta.
     *
     * <p>El vector se deriva del texto con un CRC32, así que es
     * <strong>determinista y distinto para textos distintos</strong>: dos
     * documentos iguales dan el mismo vector y dos distintos dan vectores
     * distintos, que es todo lo que necesitan los tests de indexado y de
     * vecindad. Lo que NO se puede comprobar con esto es que los resultados
     * semánticos tengan sentido —eso lo cubre el end-to-end, contra el modelo
     * de verdad—.
     */
    @Bean
    @Primary
    EmbeddingModel stubEmbeddingModel() {
        return new EmbeddingModel() {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = new ArrayList<>();
                List<String> instructions = request.getInstructions();
                for (int i = 0; i < instructions.size(); i++) {
                    embeddings.add(new Embedding(vectorFor(instructions.get(i)), i));
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                return vectorFor(document.getText());
            }

            @Override
            public int dimensions() {
                return 384;
            }
        };
    }

    /** Vector normalizado de 384 dimensiones derivado del texto. */
    private static float[] vectorFor(String text) {
        CRC32 crc = new CRC32();
        crc.update(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
        long seed = crc.getValue();

        float[] vector = new float[384];
        double norm = 0;
        for (int i = 0; i < vector.length; i++) {
            // Congruencial lineal sencillo: reproducible y sin dependencias.
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            vector[i] = ((seed >>> 33) % 2000) / 1000f - 1f;
            norm += (double) vector[i] * vector[i];
        }

        // Normalizado porque la consulta usa distancia coseno: con vectores sin
        // normalizar los valores siguen siendo válidos, pero comparar magnitudes
        // deja de significar lo mismo que en producción.
        float length = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= length;
        }
        return vector;
    }
}
