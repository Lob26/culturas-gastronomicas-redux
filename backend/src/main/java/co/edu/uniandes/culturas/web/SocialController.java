package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.domain.Favorite;
import co.edu.uniandes.culturas.service.SocialService;
import co.edu.uniandes.culturas.web.dto.SocialDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Valoraciones y recetario personal.
 *
 * <p>El usuario se toma del token y <strong>nunca</strong> del cuerpo o de la
 * ruta. Si el cliente pudiera indicar a nombre de quién valora, cualquiera
 * podría escribir valoraciones ajenas: la autorización estaría del lado
 * equivocado de la frontera.
 */
@RestController
@RequestMapping("/api/v2")
@Tag(name = "Social", description = "Valoraciones y recetario personal")
public class SocialController {

    private final SocialService service;

    public SocialController(SocialService service) {
        this.service = service;
    }

    @GetMapping("/recetas/{slug}/valoraciones")
    @Operation(summary = "Valoraciones de una receta")
    public List<SocialDtos.RatingView> ratings(
            @PathVariable String slug,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return service.listRatings(slug, pageable);
    }

    @PutMapping("/recetas/{slug}/valoraciones/mia")
    @Operation(summary = "Crea o actualiza tu valoración de una receta")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Valoración guardada"),
            @ApiResponse(responseCode = "401", description = "Hace falta identificarse"),
            @ApiResponse(responseCode = "404", description = "La receta no existe")
    })
    public SocialDtos.RatingView rate(@PathVariable String slug,
                                      @Valid @RequestBody SocialDtos.RatingRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return service.rate(slug, jwt.getSubject(), request);
    }

    @DeleteMapping("/recetas/{slug}/valoraciones/mia")
    @Operation(summary = "Retira tu valoración")
    public ResponseEntity<Void> unrate(@PathVariable String slug, @AuthenticationPrincipal Jwt jwt) {
        service.deleteRating(slug, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/favoritos/{tipo}/{id}")
    @Operation(summary = "Guarda o quita un elemento del recetario, según su estado actual")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado resultante del favorito"),
            @ApiResponse(responseCode = "401", description = "Hace falta identificarse")
    })
    public SocialDtos.ToggleResponse toggleFavorite(@PathVariable("tipo") Favorite.TargetType tipo,
                                                    @PathVariable Long id,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return new SocialDtos.ToggleResponse(service.toggleFavorite(jwt.getSubject(), tipo, id));
    }

    @GetMapping("/favoritos")
    @Operation(summary = "Tu recetario personal")
    public List<SocialDtos.FavoriteView> favorites(@AuthenticationPrincipal Jwt jwt) {
        return service.listFavorites(jwt.getSubject());
    }
}
