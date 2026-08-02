package co.edu.uniandes.culturas.repository;

import co.edu.uniandes.culturas.domain.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    @Query("SELECT f FROM Favorite f WHERE f.user.username = :username ORDER BY f.createdAt DESC")
    List<Favorite> findMine(@Param("username") String username);

    Optional<Favorite> findByUserUsernameAndTargetTypeAndTargetId(
            String username, Favorite.TargetType targetType, Long targetId);

    /**
     * Borrado directo en vez de leer y luego eliminar.
     *
     * <p>{@code @Modifying} con {@code clearAutomatically} porque, si no, la
     * entidad borrada seguiría en el contexto de persistencia y una lectura
     * posterior en la misma transacción la devolvería como si existiera.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            DELETE FROM Favorite f
            WHERE f.user.username = :username
              AND f.targetType = :targetType
              AND f.targetId = :targetId
            """)
    int removeMine(@Param("username") String username,
                   @Param("targetType") Favorite.TargetType targetType,
                   @Param("targetId") Long targetId);
}
