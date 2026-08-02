package co.edu.uniandes.culturas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * País donde se practica una cultura gastronómica y donde viven restaurantes.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "country",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_country_name", columnNames = "name"),
                @UniqueConstraint(name = "uq_country_iso2", columnNames = "iso2")
        })
public class Country extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    /**
     * ISO 3166-1 alfa-2. La columna lleva un CHECK que exige dos mayúsculas.
     *
     * <p>En 2023 esto era un {@link java.util.Locale}, que aceptaba cualquier
     * cosa: los datos semilla registraban Japón como {@code JA}, que no es un
     * código válido — el correcto es {@code JP}.
     */
    @Column(nullable = false, length = 2)
    private String iso2;

    @Column(length = 3)
    private String iso3;

    /** Lado inverso: la tabla de unión la gobierna {@link GastronomicCulture}. */
    @ToString.Exclude
    @ManyToMany(mappedBy = "countries", fetch = FetchType.LAZY)
    private List<GastronomicCulture> cultures = new ArrayList<>();

    /**
     * Sin cascada de borrado: en el esquema la FK es ON DELETE RESTRICT, así que
     * un país con restaurantes no se puede eliminar. Es la única de las cinco
     * reglas de borrado del proyecto original que valía la pena conservar.
     */
    @ToString.Exclude
    @OneToMany(mappedBy = "country", fetch = FetchType.LAZY)
    private List<Restaurant> restaurants = new ArrayList<>();

    public void addRestaurant(Restaurant restaurant) {
        restaurants.add(restaurant);
        restaurant.setCountry(this);
    }

    public void removeRestaurant(Restaurant restaurant) {
        restaurants.remove(restaurant);
        restaurant.setCountry(null);
    }
}
