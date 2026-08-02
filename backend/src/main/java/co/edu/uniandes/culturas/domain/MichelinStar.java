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

import java.time.LocalDate;

/**
 * Una estrella Michelin concedida a un restaurante en una fecha.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "michelin_star",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_star_restaurant_date",
                columnNames = {"restaurant_id", "acquired"}))
public class MichelinStar extends BaseEntity {

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    /**
     * LocalDate y no {@link java.util.Date}, que es lo que usaba el modelo de
     * 2023: aquello arrastraba hora y zona horaria para un dato que sólo tiene
     * fecha, y provocaba desfases de un día al serializar.
     */
    @Column(nullable = false)
    private LocalDate acquired;
}
