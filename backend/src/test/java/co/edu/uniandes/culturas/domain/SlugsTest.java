package co.edu.uniandes.culturas.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlugsTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
            "Cocina italiana,        cocina-italiana",
            "Pasta Carbonara,        pasta-carbonara",
            "  Bandeja  Paisa  ,     bandeja-paisa",
            "Tacos al Pastor (2024), tacos-al-pastor-2024",
    })
    void deriva_slugs_legibles(String input, String expected) {
        assertThat(Slugs.of(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
            "Japón,            japon",
            "Cocina española,  cocina-espanola",
            "Crème brûlée,     creme-brulee",
            "Ñoquis,           noquis",
    })
    @DisplayName("los acentos y la eñe se reducen a ASCII en vez de desaparecer")
    void normaliza_acentos(String input, String expected) {
        // Sin la normalización NFD previa, descartar los caracteres no ASCII
        // borraría la letra entera: "Japón" quedaría en "japn".
        assertThat(Slugs.of(input)).isEqualTo(expected);
    }

    @Test
    void no_deja_guiones_en_los_extremos() {
        assertThat(Slugs.of("¡Bandeja paisa!")).isEqualTo("bandeja-paisa");
        assertThat(Slugs.of("--- prueba ---")).isEqualTo("prueba");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!!!", "¿?¡!"})
    @DisplayName("rechaza lo que no produce ningún carácter utilizable")
    void rechaza_entradas_sin_contenido(String input) {
        assertThatThrownBy(() -> Slugs.of(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechaza_null() {
        assertThatThrownBy(() -> Slugs.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
