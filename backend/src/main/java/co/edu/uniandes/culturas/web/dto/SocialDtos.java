package co.edu.uniandes.culturas.web.dto;

import co.edu.uniandes.culturas.domain.Favorite;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class SocialDtos {

    private SocialDtos() {
    }

    @Schema(name = "RatingRequest")
    public record RatingRequest(
            @NotNull(message = "la puntuación es obligatoria")
            @Min(value = 1, message = "la puntuación mínima es 1")
            @Max(value = 5, message = "la puntuación máxima es 5")
            Short score,

            @Size(max = 2000, message = "el comentario no puede pasar de 2000 caracteres")
            String comment
    ) {
    }

    @Schema(name = "RatingView")
    public record RatingView(
            short score,
            String comment,
            @Schema(description = "Nombre visible de quien valoró", example = "Pedro Lobato")
            String author,
            Instant createdAt
    ) {
    }

    @Schema(name = "FavoriteView")
    public record FavoriteView(
            Favorite.TargetType targetType,
            Long targetId,
            Instant createdAt
    ) {
    }

    @Schema(name = "FavoriteToggleResponse")
    public record ToggleResponse(
            @Schema(description = "true si quedó guardado, false si se quitó")
            boolean favorito
    ) {
    }
}
