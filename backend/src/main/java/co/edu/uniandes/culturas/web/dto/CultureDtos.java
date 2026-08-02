package co.edu.uniandes.culturas.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.util.List;

/**
 * DTOs de cultura gastronómica.
 *
 * <p>Agrupados en una clase contenedora porque son records pequeños y muy
 * acoplados entre sí; tenerlos juntos evita ocho archivos de diez líneas.
 *
 * <p>Records y no clases con getters: son inmutables por construcción, así que
 * no pueden sufrir el problema del proyecto de 2023, donde el DTO plano que
 * llegaba por HTTP se convertía en entidad y se guardaba tal cual, borrando las
 * relaciones que no venían en el cuerpo.
 */
public final class CultureDtos {

    private CultureDtos() {
    }

    /** Vista de listado: sin colecciones, con conteos. */
    @Schema(name = "CultureSummary", description = "Cultura gastronómica en un listado")
    public record Summary(
            Long id,
            String name,
            @Schema(description = "Identificador estable para URLs", example = "cocina-italiana")
            String slug,
            String description,
            String imageUrl,
            @Schema(description = "Número de recetas de esta cultura", example = "12")
            long recipeCount,
            @Schema(description = "Número de categorías de esta cultura", example = "4")
            long categoryCount
    ) {
    }

    /** Vista de detalle: añade los países, que es una colección pequeña y acotada. */
    @Schema(name = "CultureDetail", description = "Detalle de una cultura gastronómica")
    public record Detail(
            Long id,
            String name,
            String slug,
            String description,
            String imageUrl,
            @Schema(description = "Países donde se practica esta cocina")
            List<CountrySummary> countries
    ) {
    }

    @Schema(name = "CountrySummary")
    public record CountrySummary(
            Long id,
            String name,
            @Schema(description = "ISO 3166-1 alfa-2", example = "IT")
            String iso2
    ) {
    }

    /**
     * Cuerpo de creación y actualización.
     *
     * <p>El slug no aparece: lo deriva el servidor a partir del nombre. Dejarlo
     * al cliente permitiría que dos culturas compartieran URL o que el slug
     * dejara de corresponderse con el nombre.
     */
    @Schema(name = "CultureRequest", description = "Datos para crear o actualizar una cultura")
    public record Request(
            @NotBlank(message = "el nombre es obligatorio")
            @Size(max = 120, message = "el nombre no puede pasar de 120 caracteres")
            String name,

            @Size(max = 2000, message = "la descripción no puede pasar de 2000 caracteres")
            String description,

            @URL(message = "la imagen debe ser una URL válida")
            @Size(max = 1000, message = "la URL no puede pasar de 1000 caracteres")
            String imageUrl
    ) {
    }
}
