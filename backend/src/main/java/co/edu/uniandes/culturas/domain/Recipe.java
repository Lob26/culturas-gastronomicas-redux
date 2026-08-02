package co.edu.uniandes.culturas.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
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
 * Una receta de una cultura gastronómica.
 *
 * <p>El cambio de fondo respecto a 2023 es que las instrucciones ya no son un
 * {@code @Lob String} de ~1500 caracteres que se cargaba entero en cada listado,
 * sino una lista ordenada de {@link RecipeStep}. Así estaba en el UML original;
 * el código nunca lo implementó. Es también lo que hace posible el modo cocina.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "recipe",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_recipe_name_culture", columnNames = {"name", "culture_id"}),
                @UniqueConstraint(name = "uq_recipe_slug", columnNames = "slug")
        })
public class Recipe extends BaseEntity {

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 180)
    private String slug;

    @Column(length = 2000)
    private String description;

    @Column(name = "prep_time_minutes")
    private Integer prepTimeMinutes;

    private Short servings;

    /**
     * STRING y no ORDINAL: con ORDINAL, insertar un valor nuevo en medio del
     * enum reinterpreta silenciosamente todas las filas ya guardadas. La columna
     * lleva además un CHECK con estos mismos tres valores.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Difficulty difficulty;

    /*
     * Agregados de valoración, desnormalizados para poder ordenar por nota sin
     * un GROUP BY en cada listado. Los mantiene un trigger de la base, no la
     * aplicación: así no pueden quedar desfasados porque una ruta de escritura
     * se olvide de refrescarlos. Por eso son de sólo lectura desde JPA
     * (insertable/updatable = false): escribirlos desde aquí competiría con el
     * trigger y ganaría el último en llegar.
     */
    @Column(name = "rating_average", precision = 3, scale = 2, insertable = false, updatable = false)
    private java.math.BigDecimal ratingAverage;

    @Column(name = "rating_count", insertable = false, updatable = false)
    private Integer ratingCount;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "culture_id", nullable = false)
    private GastronomicCulture culture;

    /**
     * Los pasos se ordenan por {@code position} en la consulta, no por el orden
     * de inserción. {@code @OrderBy} evita depender de cómo devuelva las filas
     * la base de datos, que sin ORDER BY explícito no garantiza nada.
     */
    @ToString.Exclude
    @OrderBy("position ASC")
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<RecipeStep> steps = new ArrayList<>();

    @ToString.Exclude
    @OrderBy("position ASC")
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Ingredient> ingredients = new ArrayList<>();

    @ToString.Exclude
    @OrderBy("position ASC")
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DishMultimedia> images = new ArrayList<>();

    /** Lado propietario de la relación N:M con restaurantes. */
    @ToString.Exclude
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "recipe_restaurant",
            joinColumns = @JoinColumn(name = "recipe_id"),
            inverseJoinColumns = @JoinColumn(name = "restaurant_id"))
    private List<Restaurant> restaurants = new ArrayList<>();

    // --- Gestión de asociaciones -------------------------------------------

    /** Añade el paso al final, calculando su posición. */
    public void addStep(RecipeStep step) {
        step.setPosition((short) (steps.size() + 1));
        steps.add(step);
        step.setRecipe(this);
    }

    public void addIngredient(Ingredient ingredient) {
        ingredient.setPosition((short) (ingredients.size() + 1));
        ingredients.add(ingredient);
        ingredient.setRecipe(this);
    }

    public void addImage(DishMultimedia image) {
        image.setPosition((short) (images.size() + 1));
        images.add(image);
        image.setRecipe(this);
    }

    public void addRestaurant(Restaurant restaurant) {
        if (!restaurants.contains(restaurant)) {
            restaurants.add(restaurant);
            restaurant.getRecipes().add(this);
        }
    }

    public void removeRestaurant(Restaurant restaurant) {
        restaurants.remove(restaurant);
        restaurant.getRecipes().remove(this);
    }
}
