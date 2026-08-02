package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.service.RecommendationService;
import co.edu.uniandes.culturas.service.SearchService;
import co.edu.uniandes.culturas.web.dto.SearchDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Búsqueda en el catálogo.
 *
 * <p>Esto es lo que en 2023 era un botón que abría un aviso cuyo cuerpo decía
 * «TO-DO».
 */
@RestController
@RequestMapping("/api/v2/buscar")
@Validated
@Tag(name = "Búsqueda", description = "Búsqueda híbrida sobre recetas y culturas")
public class SearchController {

    private final SearchService service;
    private final RecommendationService recommendations;

    public SearchController(SearchService service, RecommendationService recommendations) {
        this.service = service;
        this.recommendations = recommendations;
    }

    @GetMapping
    @Operation(summary = "Busca recetas y culturas fusionando los carriles léxico, difuso y semántico")
    public List<SearchDtos.Hit> search(
            @Parameter(description = "Texto libre. Admite comillas y «or» sin romperse.", example = "carbonara")
            @RequestParam String q,

            @Parameter(description = "Máximo de resultados")
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return service.search(q, limit);
    }

    @GetMapping("/similares/{slug}")
    @Operation(summary = "Recetas parecidas a una dada, por vecindad de vectores")
    public List<SearchDtos.Hit> similar(
            @PathVariable String slug,
            @RequestParam(defaultValue = "6") @Min(1) @Max(50) int limit) {
        return service.similarTo(slug, limit);
    }

    /**
     * Recomendaciones personales.
     *
     * <p>El usuario sale del token. Aceptarlo por parámetro convertiría esto en
     * un endpoint para leer el perfil de gustos de cualquiera.
     */
    @GetMapping("/recomendaciones")
    @Operation(summary = "Recomendado para ti, a partir de lo que has valorado alto")
    public RecommendationService.Recommendations recommended(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return recommendations.forUser(jwt.getSubject(), limit);
    }
}
