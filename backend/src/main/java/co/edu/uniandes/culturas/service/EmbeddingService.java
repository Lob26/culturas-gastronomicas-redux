package co.edu.uniandes.culturas.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Traduce texto a vectores.
 *
 * <p>La inferencia corre en proceso sobre ONNX Runtime: no hay servicio que
 * levantar, ni clave que rotar, ni una llamada de red por cada consulta que
 * escriba un usuario. Un modelo alojado daría vectores algo mejores a cambio de
 * convertir la búsqueda en algo que se cae cuando se cae un tercero, y de meter
 * latencia de red en la ruta caliente.
 *
 * <p>El vector se guarda en su forma textual —{@code [0.12,-0.03,...]}— porque
 * es la representación de entrada de pgvector y evita tener que registrar un
 * tipo Hibernate propio para un tipo que ninguna entidad mapea. La alternativa
 * (un {@code UserType} con su {@code SqlTypeDescriptor}) son unas ochenta líneas
 * para que JPA sepa leer una columna que nadie lee vía JPA.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /**
     * Debe coincidir con {@code vector(384)} en V5__vector_search.sql.
     *
     * <p>Se comprueba contra el modelo al arrancar en lugar de confiar en que
     * quien cambie el modelo se acuerde de la migración: un desajuste aquí no
     * da un error claro, da un {@code ERROR: expected 384 dimensions, not 768}
     * en la primera escritura, que es mucho más tarde y mucho menos evidente.
     */
    public static final int DIMENSIONS = 384;

    /**
     * Tope de caracteres del texto que se embebe.
     *
     * <p>El modelo trunca a 128 tokens por su cuenta, así que mandarle una
     * receta entera es gastar CPU en texto que se descarta. Recortar antes deja
     * explícito qué parte del documento representa el vector: nombre,
     * descripción y el principio de los pasos.
     */
    private static final int MAX_CHARS = 1_000;

    private final EmbeddingModel model;

    public EmbeddingService(EmbeddingModel model) {
        this.model = model;

        int actual = model.dimensions();
        if (actual != DIMENSIONS) {
            throw new IllegalStateException(
                    "El modelo de embeddings produce %d dimensiones y la columna vector es de %d. "
                            .formatted(actual, DIMENSIONS)
                            + "Cambiar de modelo exige una migración que altere el tipo de la columna.");
        }
        log.info("Modelo de embeddings listo: {} dimensiones", actual);
    }

    /** Vector de una consulta de usuario, en la forma textual de pgvector. */
    public String embedAsVectorLiteral(String text) {
        return toVectorLiteral(model.embed(truncate(text)));
    }

    /**
     * Compone el texto que representa a un documento.
     *
     * <p>Las partes se separan con punto porque el modelo está entrenado sobre
     * frases: concatenar con espacios produce una sola frase gigante y difusa,
     * y el vector resultante se parece a todo un poco.
     */
    public static String documentText(String name, String description, String extra) {
        StringBuilder sb = new StringBuilder(name);
        if (description != null && !description.isBlank()) {
            sb.append(". ").append(description);
        }
        if (extra != null && !extra.isBlank()) {
            sb.append(". ").append(extra);
        }
        return sb.toString();
    }

    private String truncate(String text) {
        String clean = text == null ? "" : text.strip();
        return clean.length() <= MAX_CHARS ? clean : clean.substring(0, MAX_CHARS);
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /** Embebe un documento del catálogo y devuelve el literal listo para pgvector. */
    public String embedDocument(String name, String description, String extra) {
        return embedAsVectorLiteral(documentText(name, description, extra));
    }
}
