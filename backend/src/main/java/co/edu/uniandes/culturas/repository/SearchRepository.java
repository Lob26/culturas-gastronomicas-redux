package co.edu.uniandes.culturas.repository;

import co.edu.uniandes.culturas.domain.Recipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Consultas de búsqueda.
 *
 * <p>Nativas y no JPQL: {@code tsvector}, {@code websearch_to_tsquery},
 * {@code ts_rank} y el operador de similitud de trigramas no tienen equivalente
 * en JPQL. Las columnas están indexadas con GIN, así que el planificador puede
 * usarlas.
 */
public interface SearchRepository extends JpaRepository<Recipe, Long> {

    /**
     * Búsqueda por lexemas, ordenada por relevancia.
     *
     * <p>{@code websearch_to_tsquery} y no {@code to_tsquery}: acepta texto
     * libre tal cual lo escribe una persona —comillas, «or», guiones— sin
     * lanzar error de sintaxis. Con {@code to_tsquery}, una consulta como
     * {@code arroz & } o unas comillas sin cerrar reventarían la petición.
     */
    @Query(value = """
            SELECT r.slug          AS slug,
                   r.name          AS name,
                   'RECIPE'        AS type,
                   ts_rank(r.search_vector, websearch_to_tsquery('spanish', :query)) AS score
            FROM recipe r
            WHERE r.search_vector @@ websearch_to_tsquery('spanish', :query)
            UNION ALL
            SELECT DISTINCT ON (r.slug)
                   r.slug, r.name, 'RECIPE',
                   ts_rank(s.search_vector, websearch_to_tsquery('spanish', :query)) * 0.5
            FROM recipe_step s JOIN recipe r ON r.id = s.recipe_id
            WHERE s.search_vector @@ websearch_to_tsquery('spanish', :query)
            UNION ALL
            SELECT c.slug, c.name, 'CULTURE',
                   ts_rank(c.search_vector, websearch_to_tsquery('spanish', :query))
            FROM gastronomic_culture c
            WHERE c.search_vector @@ websearch_to_tsquery('spanish', :query)
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchHit> searchLexical(@Param("query") String query, @Param("limit") int limit);

    /**
     * Búsqueda por similitud de trigramas: tolera erratas y acentos.
     *
     * <p>{@code unaccent} se aplica a los dos lados en la consulta porque no es
     * inmutable y no puede formar parte del índice. Con un catálogo de este
     * tamaño el coste es irrelevante y la corrección se mantiene.
     */
    @Query(value = """
            SELECT r.slug   AS slug,
                   r.name   AS name,
                   'RECIPE' AS type,
                   similarity(unaccent(lower(r.name)), unaccent(lower(:query))) AS score
            FROM recipe r
            WHERE similarity(unaccent(lower(r.name)), unaccent(lower(:query))) > 0.15
            UNION ALL
            SELECT c.slug, c.name, 'CULTURE',
                   similarity(unaccent(lower(c.name)), unaccent(lower(:query)))
            FROM gastronomic_culture c
            WHERE similarity(unaccent(lower(c.name)), unaccent(lower(:query))) > 0.15
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchHit> searchFuzzy(@Param("query") String query, @Param("limit") int limit);

    /** Proyección de sólo lectura compartida por ambos carriles. */
    interface SearchHit {
        String getSlug();

        String getName();

        String getType();

        double getScore();
    }
}
