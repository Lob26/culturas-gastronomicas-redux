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

/**
 * Un paso de preparación dentro de una receta.
 *
 * <p>No extiende {@link BaseEntity} a propósito: es una parte de la receta, no
 * una entidad con vida propia. No necesita auditoría ni versión porque nunca se
 * modifica de forma independiente — sólo a través de la receta que lo posee, que
 * sí lleva su propio {@code @Version}.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "recipe_step",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_step_recipe_position",
                columnNames = {"recipe_id", "position"}))
public class RecipeStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    /** Empieza en 1. Lo asigna {@link Recipe#addStep}. */
    @Column(nullable = false)
    private Short position;

    @Column(nullable = false, columnDefinition = "text")
    private String instruction;

    /**
     * Duración detectada en el texto del paso («cocer 20-25 minutos»), que
     * alimenta los temporizadores del modo cocina. Null cuando el paso no
     * menciona ningún tiempo, que es el caso más frecuente.
     */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;
}
