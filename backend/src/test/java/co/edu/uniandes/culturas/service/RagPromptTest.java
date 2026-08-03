package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.repository.VectorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprueba la mitad de la generación que se puede comprobar sin red.
 *
 * <p>Es también la mitad que importa: si las fuentes llegan mal numeradas o
 * incompletas, no hay prompt que arregle la respuesta. Un fallo aquí produce
 * una cita que apunta al documento equivocado, que es peor que no citar —el
 * usuario comprueba la fuente, ve que no dice eso, y no sabe si el error está
 * en la respuesta o en el enlace.
 */
class RagPromptTest {

    /** Implementación mínima de la proyección; no hace falta ni Spring ni base. */
    private record Doc(String slug, String name, String type, String text)
            implements VectorRepository.Document {

        @Override
        public String getSlug() {
            return slug;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public String getText() {
            return text;
        }
    }

    @Test
    @DisplayName("las fuentes se numeran desde 1 y en el orden recibido")
    void numbersSourcesFromOne() {
        String prompt = RagService.buildPrompt("¿Qué lleva la carbonara?", List.of(
                new Doc("pasta-carbonara", "Pasta Carbonara", "RECIPE", "Huevo, queso curado y guanciale."),
                new Doc("cocina-italiana", "Cocina italiana", "CULTURE", "Pocos ingredientes de calidad.")));

        // Desde 1 y no desde 0: al modelo se le pide citar «[1]», y una lista
        // que empieza en [0] produce citas desplazadas una posición.
        assertThat(prompt).contains("[1] Pasta Carbonara");
        assertThat(prompt).contains("[2] Cocina italiana");
        assertThat(prompt.indexOf("[1]")).isLessThan(prompt.indexOf("[2]"));

        assertThat(prompt).contains("guanciale");
        assertThat(prompt).endsWith("Pregunta: ¿Qué lleva la carbonara?");
    }

    @Test
    @DisplayName("un documento sin descripción no rompe el prompt")
    void handlesNullText() {
        String prompt = RagService.buildPrompt("¿Y esto?", List.of(
                new Doc("sin-datos", "Sin datos", "CULTURE", null)));

        // El texto puede ser nulo: `description` es opcional en el esquema. Sin
        // este cuidado, concatenar produciría la cadena "null" y el modelo la
        // leería como contenido de la fuente.
        assertThat(prompt).contains("[1] Sin datos");
        assertThat(prompt).doesNotContain("null");
    }
}
