package co.edu.uniandes.culturas.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Restaurante donde se sirven recetas del catálogo.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "restaurant",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_restaurant_name_city_country",
                columnNames = {"name", "city", "country_id"}))
public class Restaurant extends BaseEntity {

    /** Número máximo de estrellas Michelin, según el UML de 2023. */
    public static final int MAX_MICHELIN_STARS = 3;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 120)
    private String city;

    @Column(length = 200)
    private String contact;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    /** Lado inverso: la tabla de unión la gobierna {@link Recipe}. */
    @ToString.Exclude
    @ManyToMany(mappedBy = "restaurants", fetch = FetchType.LAZY)
    private List<Recipe> recipes = new ArrayList<>();

    @ToString.Exclude
    @OrderBy("acquired ASC")
    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MichelinStar> stars = new ArrayList<>();

    /**
     * Añade una estrella respetando el máximo de {@value #MAX_MICHELIN_STARS}.
     *
     * <p>Es una comprobación temprana para dar un error claro, no la garantía:
     * quien realmente impone la regla es un CONSTRAINT TRIGGER diferido en la
     * base de datos, de modo que ninguna ruta de escritura pueda saltársela.
     * En 2023 la regla estaba en el UML y no se comprobaba en ningún sitio.
     *
     * @throws IllegalStateException si el restaurante ya tiene tres estrellas
     */
    public void addStar(MichelinStar star) {
        if (stars.size() >= MAX_MICHELIN_STARS) {
            throw new IllegalStateException(
                    "El restaurante '%s' ya tiene el máximo de %d estrellas Michelin"
                            .formatted(name, MAX_MICHELIN_STARS));
        }
        stars.add(star);
        star.setRestaurant(this);
    }

    public void removeStar(MichelinStar star) {
        stars.remove(star);
        star.setRestaurant(null);
    }
}
