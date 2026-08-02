package co.edu.uniandes.culturas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Valoración de una receta, de 1 a 5, con comentario opcional.
 *
 * <p>Una por persona y receta. Lo garantiza la restricción UNIQUE de la tabla y
 * no una comprobación previa en el servicio: entre el SELECT y el INSERT cabe
 * otra petición del mismo usuario.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "rating",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rating_user_recipe",
                columnNames = {"user_id", "recipe_id"}))
public class Rating extends BaseEntity {

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private Short score;

    @Column(length = 2000)
    private String comment;
}
