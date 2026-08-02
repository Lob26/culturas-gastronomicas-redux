package co.edu.uniandes.culturas.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
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
 * Una cocina del mundo: italiana, japonesa, colombiana.
 *
 * <p>Raíz del agregado. Posee sus recetas y categorías, y comparte países con
 * otras culturas.
 */
@Entity
@Getter
@Setter
// Las colecciones llevan @ToString.Exclude: recorrerlas dispararía la carga
// perezosa desde cualquier log, y en una relación bidireccional además
// terminaría en StackOverflowError.
@ToString
@Table(
        name = "gastronomic_culture",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_culture_name", columnNames = "name"),
                @UniqueConstraint(name = "uq_culture_slug", columnNames = "slug")
        })
public class GastronomicCulture extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    /** Identificador estable para URLs; no cambia aunque se corrija el nombre. */
    @Column(nullable = false, length = 140)
    private String slug;

    @Column(length = 2000)
    private String description;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    // `mappedBy` es lo que faltaba en el modelo de 2023. Sin él Hibernate genera
    // una tabla intermedia ADEMÁS de la columna FK de Recipe.culture, y cada
    // asociación acaba guardada por duplicado en dos sitios que divergen.
    @ToString.Exclude
    @OneToMany(mappedBy = "culture", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Recipe> recipes = new ArrayList<>();

    @ToString.Exclude
    @OneToMany(mappedBy = "culture", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<GastronomicCategory> categories = new ArrayList<>();

    /**
     * Lado propietario de la relación N:M con países. Sin cascada: los países
     * existen con independencia de las culturas que los reclamen, y borrar una
     * cultura no debe llevarse Italia por delante.
     */
    @ToString.Exclude
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "culture_country",
            joinColumns = @JoinColumn(name = "culture_id"),
            inverseJoinColumns = @JoinColumn(name = "country_id"))
    private List<Country> countries = new ArrayList<>();

    // --- Gestión de asociaciones -------------------------------------------
    //
    // Los servicios de 2023 tocaban una sola punta de la relación, así que el
    // grafo en memoria quedaba inconsistente con lo que se acababa persistiendo.
    // Uno de ellos incluso tenía la condición invertida
    // (`if (contains(x)) add(x)`), de modo que la asociación no se creaba nunca.
    // Con estos métodos, mantener las dos puntas deja de ser opcional.

    public void addRecipe(Recipe recipe) {
        recipes.add(recipe);
        recipe.setCulture(this);
    }

    public void removeRecipe(Recipe recipe) {
        recipes.remove(recipe);
        recipe.setCulture(null);
    }

    public void addCategory(GastronomicCategory category) {
        categories.add(category);
        category.setCulture(this);
    }

    public void removeCategory(GastronomicCategory category) {
        categories.remove(category);
        category.setCulture(null);
    }

    public void addCountry(Country country) {
        if (!countries.contains(country)) {
            countries.add(country);
            country.getCultures().add(this);
        }
    }

    public void removeCountry(Country country) {
        countries.remove(country);
        country.getCultures().remove(this);
    }
}
