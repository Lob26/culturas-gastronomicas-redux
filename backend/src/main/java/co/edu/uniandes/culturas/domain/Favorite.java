package co.edu.uniandes.culturas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.Instant;

/**
 * Elemento guardado en el recetario personal.
 *
 * <p>Polimórfico a propósito: receta, restaurante y cultura se guardan igual, y
 * tener tres tablas idénticas no aportaría nada. El tipo va acotado por CHECK
 * en la base, así que no puede aparecer un valor que la aplicación no entienda.
 *
 * <p>No extiende {@link BaseEntity}: un favorito se crea y se borra, nunca se
 * edita, así que no necesita versión ni columnas de modificación.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "favorite",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_favorite",
                columnNames = {"user_id", "target_type", "target_id"}))
public class Favorite {

    public enum TargetType {
        RECIPE,
        RESTAURANT,
        CULTURE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
