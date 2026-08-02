package co.edu.uniandes.culturas.repository;

import co.edu.uniandes.culturas.domain.Rating;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RatingRepository extends JpaRepository<Rating, Long> {

    @Query("""
            SELECT r FROM Rating r
            JOIN FETCH r.user
            WHERE r.recipe.slug = :slug
            """)
    Page<Rating> findByRecipeSlug(@Param("slug") String slug, Pageable pageable);

    @Query("""
            SELECT r FROM Rating r
            WHERE r.recipe.slug = :slug AND r.user.username = :username
            """)
    Optional<Rating> findMine(@Param("slug") String slug, @Param("username") String username);

    /**
     * Semillas para recomendar: lo que este usuario puntuó alto.
     *
     * <p>Sólo 4 y 5. Una valoración de 3 no dice «quiero más de esto», y una de
     * 1 dice lo contrario; usarlas como semilla llevaría la recomendación
     * justamente hacia donde no debe ir.
     */
    @Query("""
            SELECT r.recipe.id FROM Rating r
            WHERE r.user.username = :username AND r.score >= 4
            ORDER BY r.score DESC, r.id DESC
            """)
    List<Long> topRatedRecipeIds(@Param("username") String username, Pageable pageable);
}
