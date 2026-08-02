package co.edu.uniandes.culturas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Un ingrediente de una receta, con cantidad y unidad.
 *
 * <p>Como {@link RecipeStep}, es parte de la receta y no una entidad con vida
 * propia, así que no extiende {@link BaseEntity}.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "ingredient",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ingredient_recipe_position",
                columnNames = {"recipe_id", "position"}))
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @Column(nullable = false)
    private Short position;

    @Column(nullable = false, length = 160)
    private String name;

    /**
     * BigDecimal y no double: «media cucharadita» es 0.5 y un binary floating
     * point no representa exactamente los decimales de las recetas. La columna
     * es NUMERIC(10,2).
     */
    @Column(precision = 10, scale = 2)
    private BigDecimal quantity;

    /** Unidad libre: g, ml, taza, cucharada. Null para «al gusto». */
    @Column(length = 32)
    private String unit;
}
