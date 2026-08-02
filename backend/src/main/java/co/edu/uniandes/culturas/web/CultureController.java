package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.service.CultureService;
import co.edu.uniandes.culturas.web.dto.CultureDtos;
import co.edu.uniandes.culturas.web.dto.PagedResponse;
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

/**
 * Culturas gastronómicas.
 *
 * <p>El recurso se identifica por slug en la ruta, no por id en un parámetro de
 * consulta. En 2023 era {@code GET /culture?id=5}, {@code PUT /culture?id=5} y
 * {@code DELETE /culture?id=5}: no era REST, complicaba el cacheo y hacía que
 * el mismo path sirviera para todo.
 */
@RestController
@RequestMapping("/api/v2/culturas")
@Tag(name = "Culturas", description = "Cocinas del mundo recogidas en el catálogo")
public class CultureController {

    private final CultureService service;
    private final co.edu.uniandes.culturas.service.RecipeService recipeService;

    public CultureController(CultureService service,
                             co.edu.uniandes.culturas.service.RecipeService recipeService) {
        this.service = service;
        this.recipeService = recipeService;
    }

    @GetMapping
    @Operation(summary = "Lista las culturas gastronómicas, paginadas")
    public PagedResponse<CultureDtos.Summary> list(
            @ParameterObject
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Detalle de una cultura, con los países donde se practica")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Encontrada"),
            @ApiResponse(responseCode = "404", description = "No existe una cultura con ese slug")
    })
    public CultureDtos.Detail detail(
            @Parameter(description = "Slug de la cultura", example = "cocina-italiana")
            @PathVariable String slug) {
        return service.findBySlug(slug);
    }

    /**
     * Devuelve 201 con cabecera {@code Location}, que es lo que un cliente
     * necesita para saber dónde quedó el recurso. Los controladores de 2023
     * devolvían el cuerpo sin Location, y algunos usaban POST con 200 o PUT con
     * 201 para la misma operación de crear.
     */
    @PostMapping
    @Operation(summary = "Crea una cultura gastronómica")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Creada"),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "422", description = "El nombre ya produce una dirección existente")
    })
    public ResponseEntity<CultureDtos.Detail> create(@Valid @RequestBody CultureDtos.Request request) {
        CultureDtos.Detail created = service.create(request);
        URI location = UriComponentsBuilder.fromPath("/api/v2/culturas/{slug}")
                .buildAndExpand(created.slug())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{slug}")
    @Operation(summary = "Actualiza una cultura sin tocar sus relaciones")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Actualizada"),
            @ApiResponse(responseCode = "404", description = "No existe una cultura con ese slug"),
            @ApiResponse(responseCode = "409", description = "Otro usuario la modificó primero")
    })
    public CultureDtos.Detail update(@PathVariable String slug,
                                     @Valid @RequestBody CultureDtos.Request request) {
        return service.update(slug, request);
    }

    /**
     * Recetas de una cultura, como sub-recurso.
     *
     * <p>Paginado igual que el listado general. En 2023 el equivalente era
     * {@code GET /culture/{id}/recipe/all}, que devolvía la colección completa
     * y además ni siquiera se llamaba desde el frontend.
     */
    @GetMapping("/{slug}/recetas")
    @Operation(summary = "Recetas de una cultura, paginadas")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listado"),
            @ApiResponse(responseCode = "404", description = "No existe una cultura con ese slug")
    })
    public PagedResponse<co.edu.uniandes.culturas.web.dto.RecipeDtos.Summary> recipes(
            @PathVariable String slug,
            @ParameterObject
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return recipeService.listByCulture(slug, pageable);
    }

    @DeleteMapping("/{slug}")
    @Operation(summary = "Borra una cultura y, en cascada, sus recetas y categorías")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Borrada"),
            @ApiResponse(responseCode = "404", description = "No existe una cultura con ese slug")
    })
    public ResponseEntity<Void> delete(@PathVariable String slug) {
        service.delete(slug);
        return ResponseEntity.noContent().build();
    }
}
