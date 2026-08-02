package co.edu.uniandes.culturas.web.mapper;

import co.edu.uniandes.culturas.domain.DishMultimedia;
import co.edu.uniandes.culturas.domain.Ingredient;
import co.edu.uniandes.culturas.domain.Recipe;
import co.edu.uniandes.culturas.domain.RecipeStep;
import co.edu.uniandes.culturas.web.dto.RecipeDtos;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RecipeMapper {

    @Mapping(target = "cultureName", source = "recipe.culture.name")
    @Mapping(target = "cultureSlug", source = "recipe.culture.slug")
    @Mapping(target = "imageUrl", source = "imageUrl")
    RecipeDtos.Summary toSummary(Recipe recipe, String imageUrl);

    @Mapping(target = "cultureName", source = "culture.name")
    @Mapping(target = "cultureSlug", source = "culture.slug")
    RecipeDtos.Detail toDetail(Recipe recipe);

    RecipeDtos.Step toStep(RecipeStep step);

    RecipeDtos.Ingredient toIngredient(Ingredient ingredient);

    /**
     * Las imágenes se exponen como lista de URLs y no como objetos: el cliente
     * sólo necesita la dirección, y los campos de diagnóstico del verificador
     * de enlaces ({@code lastCheckedAt}, {@code lastStatus}) son internos.
     */
    default String toUrl(DishMultimedia image) {
        return image == null ? null : image.getUrl();
    }
}
