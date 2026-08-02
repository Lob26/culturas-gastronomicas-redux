package co.edu.uniandes.culturas.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extracción de duraciones del texto de un paso, que alimenta los
 * temporizadores del modo cocina.
 *
 * <p>Lógica pura: no necesita contexto de Spring ni base de datos. Ese es el
 * efecto de inyectar por constructor — en 2023 todos los servicios usaban
 * {@code @Autowired} sobre campos privados y no había forma de probar nada sin
 * levantar la aplicación entera.
 */
class RecipeDurationTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}s")
    @CsvSource({
            "'Cocer 20 minutos',                          1200",
            "'Reposar 2 horas',                           7200",
            "'Dejar 30 segundos',                           30",
            "'Hornear 45 minutos a 180 grados',           2700",
    })
    void extrae_duraciones_simples(String instruction, int expected) {
        assertThat(RecipeService.parseDuration(instruction)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}s")
    @CsvSource({
            "'Cocer de 20 a 25 minutos',                  1500",
            "'Asar 10-12 minutos girando de vez en cuando', 720",
            "'Sofreír 1 o 2 minutos hasta que huela',      120",
    })
    @DisplayName("en un rango toma el extremo superior")
    void usa_el_extremo_superior_en_rangos(String instruction, int expected) {
        // El extremo superior es el que evita levantar la tapa antes de tiempo:
        // un temporizador que suena a los 10 de un «10-12 minutos» hace que la
        // persona interrumpa una cocción que aún no ha terminado.
        assertThat(RecipeService.parseDuration(instruction)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Salpimentar al gusto",
            "Añadir 2 cucharadas de aceite",
            "Servir con 4 rebanadas de pan",
            "Precalentar el horno a 180 grados",
    })
    @DisplayName("devuelve null cuando el paso no menciona tiempo")
    void ignora_cantidades_que_no_son_tiempo(String instruction) {
        // «2 cucharadas» y «180 grados» llevan número pero no unidad temporal.
        assertThat(RecipeService.parseDuration(instruction)).isNull();
    }

    @Test
    @DisplayName("descarta duraciones absurdas en lugar de programarlas")
    void descarta_duraciones_fuera_de_rango() {
        assertThat(RecipeService.parseDuration("Curar 200 horas")).isNull();
    }

    @Test
    void toma_la_primera_duracion_cuando_hay_varias() {
        assertThat(RecipeService.parseDuration("Cocer 10 minutos y luego reposar 5 minutos"))
                .isEqualTo(600);
    }
}
