package co.edu.uniandes.culturas.repository;

import co.edu.uniandes.culturas.domain.GastronomicCulture;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GastronomicCultureRepository extends JpaRepository<GastronomicCulture, Long> {

    Optional<GastronomicCulture> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * Detalle de una cultura con sus países ya cargados.
     *
     * <p>Se trae una sola colección por consulta a propósito. Añadir un segundo
     * {@code LEFT JOIN FETCH} sobre otra colección produciría un producto
     * cartesiano: 5 países × 12 recetas serían 60 filas para hidratar 17
     * objetos. Las recetas y las categorías se piden por separado, cada una
     * paginada desde su propio endpoint.
     */
    @Query("""
            SELECT c FROM GastronomicCulture c
            LEFT JOIN FETCH c.countries
            WHERE c.slug = :slug
            """)
    Optional<GastronomicCulture> findDetailBySlug(@Param("slug") String slug);

    /**
     * Listado paginado.
     *
     * <p>{@code @EntityGraph} vacío es deliberado: fuerza a que no se cargue
     * ninguna asociación. Los endpoints de listado del proyecto de 2023 hacían
     * {@code findAll()} y luego ModelMapper recorría cada entidad hacia un
     * DetailDTO, disparando una consulta por colección y por fila — el N+1 que
     * open-in-view=true mantenía oculto.
     */
    @EntityGraph(attributePaths = {})
    @Query("SELECT c FROM GastronomicCulture c")
    Page<GastronomicCulture> findAllSummaries(Pageable pageable);

    /** Conteos por cultura, en una sola consulta en vez de una por fila. */
    @Query("""
            SELECT c.id AS cultureId,
                   (SELECT count(r) FROM Recipe r WHERE r.culture = c)             AS recipeCount,
                   (SELECT count(g) FROM GastronomicCategory g WHERE g.culture = c) AS categoryCount
            FROM GastronomicCulture c
            WHERE c.id IN :ids
            """)
    java.util.List<CultureCounts> countChildrenFor(@Param("ids") java.util.Collection<Long> ids);

    /** Proyección de sólo lectura para {@link #countChildrenFor}. */
    interface CultureCounts {
        Long getCultureId();

        long getRecipeCount();

        long getCategoryCount();
    }
}
