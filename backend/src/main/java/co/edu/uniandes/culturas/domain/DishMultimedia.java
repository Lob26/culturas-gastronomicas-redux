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

import java.time.Instant;

/**
 * Imagen de un plato.
 *
 * <p>Sí extiende {@link BaseEntity}, al contrario que {@link RecipeStep} e
 * {@link Ingredient}: el verificador de enlaces de la Fase 5 actualiza estas
 * filas de forma independiente de la receta, así que necesita versión y
 * auditoría propias.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "dish_multimedia",
        uniqueConstraints = @UniqueConstraint(name = "uq_multimedia_url", columnNames = "url"))
public class DishMultimedia extends BaseEntity {

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(nullable = false)
    private Short position = 1;

    /** Última vez que el verificador comprobó la URL. Null si nunca se revisó. */
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /**
     * Último código HTTP observado. Los datos de 2023 traen URLs muertas y una
     * literalmente malformada: {@code https:https://...}.
     */
    @Column(name = "last_status")
    private Short lastStatus;

    /** Una imagen se considera rota si ya se comprobó y no respondió 2xx. */
    public boolean isBroken() {
        return lastStatus != null && (lastStatus < 200 || lastStatus >= 300);
    }
}
