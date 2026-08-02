package co.edu.uniandes.culturas.repository;

import co.edu.uniandes.culturas.domain.Recipe;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    Optional<Recipe> findBySlug(String slug);

    /**
     * Receta completa para la vista de detalle y el modo cocina.
     *
     * <p>Sólo se hace fetch de los pasos: son la colección que la pantalla
     * necesita entera y de forma ordenada. Ingredientes e imágenes se resuelven
     * en consultas aparte para no multiplicar filas — con tres colecciones en
     * una sola consulta, 6 pasos × 5 ingredientes × 2 imágenes darían 60 filas.
     */
    @Query("""
            SELECT r FROM Recipe r
            LEFT JOIN FETCH r.steps
            WHERE r.slug = :slug
            """)
    Optional<Recipe> findDetailBySlug(@Param("slug") String slug);

    @Query("""
            SELECT r FROM Recipe r
            JOIN FETCH r.culture
            WHERE r.culture.slug = :cultureSlug
            """)
    Page<Recipe> findByCultureSlug(@Param("cultureSlug") String cultureSlug, Pageable pageable);

    /**
     * Listado con la cultura resuelta en la misma consulta.
     *
     * <p>{@code JOIN FETCH} sobre un {@code @ManyToOne} no multiplica filas —
     * es una relación a-uno — así que aquí sí es la forma correcta de evitar
     * una consulta extra por receta al pintar el nombre de su cocina.
     */
    @Query(value = """
            SELECT r FROM Recipe r
            JOIN FETCH r.culture
            """,
            countQuery = "SELECT count(r) FROM Recipe r")
    Page<Recipe> findAllWithCulture(Pageable pageable);

    boolean existsBySlug(String slug);
}
