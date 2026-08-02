package co.edu.uniandes.culturas.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Fusión de listas de resultados por Reciprocal Rank Fusion.
 *
 * <p>{@code score(d) = Σ 1/(k + rank_i(d))} con k = 60, el valor del artículo
 * original de Cormack (SIGIR 2009) y el que usan por defecto OpenSearch,
 * Elasticsearch y Azure AI Search.
 *
 * <p>La virtud de RRF es exactamente lo que hace falta aquí: opera sobre
 * <strong>posiciones, no sobre puntuaciones</strong>. El carril léxico devuelve
 * un {@code ts_rank} y el difuso una similitud de trigramas entre 0 y 1; son
 * magnitudes sin escala común, y normalizar una contra otra sería inventarse
 * una equivalencia. Las posiciones, en cambio, sí son comparables.
 *
 * <p>Spring AI no trae ninguna implementación de esto, así que va a mano.
 */
public final class ReciprocalRankFusion {

    /**
     * Constante de amortiguación. Al sumar 60 al rango, la diferencia entre el
     * puesto 1 y el 2 pesa poco más que la que hay entre el 10 y el 11: un
     * carril muy seguro de su primer resultado no puede imponerlo por sí solo.
     */
    private static final int K = 60;

    private ReciprocalRankFusion() {
    }

    /**
     * Fusiona varias listas ya ordenadas por relevancia.
     *
     * @param keyOf identidad estable del elemento; dos entradas con la misma
     *              clave en listas distintas son el mismo documento y suman
     * @param lists listas de entrada, cada una ordenada de más a menos relevante
     */
    @SafeVarargs
    public static <T> List<T> fuse(Function<T, String> keyOf, List<T>... lists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, T> byKey = new LinkedHashMap<>();

        for (List<T> list : lists) {
            for (int rank = 0; rank < list.size(); rank++) {
                T item = list.get(rank);
                String key = keyOf.apply(item);
                // rank + 1 porque la fórmula cuenta posiciones desde 1; con
                // base 0 el primer elemento tendría un peso desproporcionado.
                scores.merge(key, 1.0 / (K + rank + 1), Double::sum);
                byKey.putIfAbsent(key, item);
            }
        }

        List<Map.Entry<String, Double>> ordered = new ArrayList<>(scores.entrySet());
        ordered.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed());

        return ordered.stream().map(entry -> byKey.get(entry.getKey())).toList();
    }
}
