package co.edu.uniandes.culturas.web.dto;

import co.edu.uniandes.culturas.domain.Difficulty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** DTOs de receta, incluidos los pasos que alimentan el modo cocina. */
public final class RecipeDtos {

    private RecipeDtos() {
    }

    @Schema(name = "RecipeSummary", description = "Receta en un listado")
    public record Summary(
            Long id,
            String name,
            String slug,
            String description,
            @Schema(description = "Nombre de la cocina a la que pertenece", example = "Cocina italiana")
            String cultureName,
            String cultureSlug,
            Integer prepTimeMinutes,
            Short servings,
            Difficulty difficulty,
            @Schema(description = "Primera imagen del plato, si tiene")
            String imageUrl
    ) {
    }

    @Schema(name = "RecipeDetail", description = "Receta completa, con pasos e ingredientes")
    public record Detail(
            Long id,
            String name,
            String slug,
            String description,
            String cultureName,
            String cultureSlug,
            Integer prepTimeMinutes,
            Short servings,
            Difficulty difficulty,
            @Schema(description = "Pasos ordenados por posición")
            List<Step> steps,
            List<Ingredient> ingredients,
            List<String> images
    ) {
    }

    @Schema(name = "RecipeStep")
    public record Step(
            @Schema(description = "Posición dentro de la receta, empezando en 1", example = "3")
            short position,
            String instruction,
            @Schema(description = "Duración detectada en el texto, para el temporizador del modo cocina. Null si el paso no menciona tiempo.", example = "1350")
            Integer durationSeconds
    ) {
    }

    @Schema(name = "RecipeIngredient")
    public record Ingredient(
            short position,
            String name,
            @Schema(description = "Cantidad. Null cuando es 'al gusto'.", example = "200")
            BigDecimal quantity,
            @Schema(description = "Unidad libre: g, ml, taza, cucharada", example = "g")
            String unit
    ) {
    }

    // --- Escritura ---------------------------------------------------------

    @Schema(name = "RecipeRequest", description = "Datos para crear o actualizar una receta")
    public record Request(
            @NotBlank(message = "el nombre es obligatorio")
            @Size(max = 160, message = "el nombre no puede pasar de 160 caracteres")
            String name,

            @Size(max = 2000, message = "la descripción no puede pasar de 2000 caracteres")
            String description,

            @NotBlank(message = "hay que indicar a qué cultura pertenece la receta")
            @Schema(description = "Slug de la cultura", example = "cocina-italiana")
            String cultureSlug,

            @Positive(message = "el tiempo de preparación debe ser mayor que cero")
            Integer prepTimeMinutes,

            @Positive(message = "las raciones deben ser mayores que cero")
            Short servings,

            Difficulty difficulty,

            /*
             * Los pasos llegan como lista y el servidor asigna las posiciones a
             * partir del orden recibido. Dejar que el cliente numere permitiría
             * huecos y duplicados, que la restricción UNIQUE (receta, posición)
             * rechazaría con un 409 poco explicativo.
             */
            @NotEmpty(message = "la receta necesita al menos un paso")
            @Size(max = 60, message = "una receta no puede tener más de 60 pasos")
            List<@NotBlank(message = "ningún paso puede estar vacío") String> steps,

            @Valid
            @Size(max = 60, message = "una receta no puede tener más de 60 ingredientes")
            List<IngredientRequest> ingredients
    ) {
    }

    @Schema(name = "RecipeIngredientRequest")
    public record IngredientRequest(
            @NotBlank(message = "el ingrediente necesita nombre")
            @Size(max = 160)
            String name,

            @Positive(message = "la cantidad debe ser mayor que cero")
            BigDecimal quantity,

            @Size(max = 32)
            String unit
    ) {
    }
}
