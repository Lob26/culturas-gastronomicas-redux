package co.edu.uniandes.culturas.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    private static final Function<String, String> IDENTITY = value -> value;

    @Test
    @DisplayName("un documento presente en los dos carriles supera al que sólo está en uno")
    void suma_las_posiciones_de_ambos_carriles() {
        // "b" es segundo en ambas listas; "a" y "c" son primeros pero sólo en
        // una cada uno. Al sumar 1/(60+2) dos veces, "b" queda por delante.
        List<String> lexical = List.of("a", "b");
        List<String> fuzzy = List.of("c", "b");

        assertThat(ReciprocalRankFusion.fuse(IDENTITY, lexical, fuzzy))
                .first().isEqualTo("b");
    }

    @Test
    @DisplayName("con un solo carril conserva el orden original")
    void preserva_el_orden_con_una_sola_lista() {
        List<String> only = List.of("primero", "segundo", "tercero");

        assertThat(ReciprocalRankFusion.fuse(IDENTITY, only))
                .containsExactly("primero", "segundo", "tercero");
    }

    @Test
    @DisplayName("no duplica documentos que aparecen en varias listas")
    void deduplica_por_clave() {
        assertThat(ReciprocalRankFusion.fuse(IDENTITY, List.of("x", "y"), List.of("y", "x")))
                .containsExactlyInAnyOrder("x", "y")
                .hasSize(2);
    }

    @Test
    @DisplayName("la amortiguación evita que un carril imponga su primer resultado")
    void la_constante_amortigua_las_primeras_posiciones() {
        // "solo" es primero en un carril y no aparece en el otro.
        // "comun" es tercero y cuarto, pero suma en los dos.
        //   solo  = 1/61                 = 0.01639
        //   comun = 1/63 + 1/64          = 0.03149
        // Sin la constante K, 1/1 aplastaría cualquier suma de posiciones
        // bajas y el carril más seguro decidiría por sí solo.
        List<String> lane1 = List.of("solo", "relleno1", "comun");
        List<String> lane2 = List.of("relleno2", "relleno3", "relleno4", "comun");

        assertThat(ReciprocalRankFusion.fuse(IDENTITY, lane1, lane2))
                .first().isEqualTo("comun");
    }

    @Test
    void tolera_listas_vacias() {
        assertThat(ReciprocalRankFusion.fuse(IDENTITY, List.<String>of(), List.of("único")))
                .containsExactly("único");

        assertThat(ReciprocalRankFusion.fuse(IDENTITY, List.<String>of(), List.<String>of()))
                .isEmpty();
    }
}
