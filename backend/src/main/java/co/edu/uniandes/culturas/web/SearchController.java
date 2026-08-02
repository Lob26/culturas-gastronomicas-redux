package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.service.SearchService;
import co.edu.uniandes.culturas.web.dto.SearchDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/buscar")
@Validated
@Tag(name = "Búsqueda", description = "Búsqueda híbrida sobre recetas y culturas")
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Busca recetas y culturas combinando coincidencia léxica y por similitud")
    public List<SearchDtos.Hit> search(
            @Parameter(description = "Texto libre. Admite comillas y «or» sin romperse.", example = "carbonara")
            @RequestParam String q,

            @Parameter(description = "Máximo de resultados")
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return service.search(q, limit);
    }
}
