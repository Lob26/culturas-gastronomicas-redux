package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.service.RecipeService;
import co.edu.uniandes.culturas.web.dto.PagedResponse;
import co.edu.uniandes.culturas.web.dto.RecipeDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v2/recetas")
@Tag(name = "Recetas", description = "Recetas del catálogo, con pasos e ingredientes")
public class RecipeController {

    private final RecipeService service;

    public RecipeController(RecipeService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Lista recetas, paginadas")
    public PagedResponse<RecipeDtos.Summary> list(
            @ParameterObject
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Receta completa, con pasos ordenados e ingredientes")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Encontrada"),
            @ApiResponse(responseCode = "404", description = "No existe una receta con ese slug")
    })
    public RecipeDtos.Detail detail(
            @Parameter(description = "Slug de la receta", example = "pasta-carbonara")
            @PathVariable String slug) {
        return service.findBySlug(slug);
    }

    @PostMapping
    @Operation(summary = "Crea una receta con sus pasos")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Creada"),
            @ApiResponse(responseCode = "401", description = "Hace falta identificarse"),
            @ApiResponse(responseCode = "404", description = "La cultura indicada no existe"),
            @ApiResponse(responseCode = "422", description = "El nombre ya produce una dirección existente")
    })
    public ResponseEntity<RecipeDtos.Detail> create(@Valid @RequestBody RecipeDtos.Request request) {
        RecipeDtos.Detail created = service.create(request);
        URI location = UriComponentsBuilder.fromPath("/api/v2/recetas/{slug}")
                .buildAndExpand(created.slug())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{slug}")
    @Operation(summary = "Actualiza una receta y reemplaza sus pasos e ingredientes")
    public RecipeDtos.Detail update(@PathVariable String slug,
                                    @Valid @RequestBody RecipeDtos.Request request) {
        return service.update(slug, request);
    }

    @DeleteMapping("/{slug}")
    @Operation(summary = "Borra una receta")
    @ApiResponses(@ApiResponse(responseCode = "204", description = "Borrada"))
    public ResponseEntity<Void> delete(@PathVariable String slug) {
        service.delete(slug);
        return ResponseEntity.noContent().build();
    }
}
