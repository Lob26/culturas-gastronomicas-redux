package co.edu.uniandes.culturas.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** DTOs de búsqueda. */
public final class SearchDtos {

    private SearchDtos() {
    }

    @Schema(name = "SearchHitType")
    public enum HitType {
        RECIPE,
        CULTURE
    }

    @Schema(name = "SearchHit", description = "Resultado de búsqueda, ya fusionado entre carriles")
    public record Hit(
            @Schema(description = "Slug del recurso, para construir su enlace", example = "pasta-carbonara")
            String slug,
            String name,
            HitType type
    ) {
    }
}
