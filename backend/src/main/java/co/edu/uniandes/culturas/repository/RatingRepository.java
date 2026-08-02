package co.edu.uniandes.culturas.repository;

import co.edu.uniandes.culturas.domain.Rating;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
