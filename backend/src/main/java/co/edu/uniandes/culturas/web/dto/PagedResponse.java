package co.edu.uniandes.culturas.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Envoltorio de paginación con forma estable.
 *
 * <p>Existe en lugar de serializar {@code Page} directamente porque
 * {@code PageImpl} no tiene un contrato JSON estable: Spring avisa de ello y su
 * forma ha cambiado entre versiones. Como el cliente TypeScript se genera desde
 * este OpenAPI, un cambio de forma en una actualización de Spring rompería el
 * frontend en silencio.
 *
 * <p>El proyecto de 2023 no paginaba nada: cada endpoint {@code /all} hacía
 * {@code findAll()} y serializaba la tabla entera con su grafo de detalle.
 */
@Schema(description = "Página de resultados")
public record PagedResponse<T>(
        @Schema(description = "Elementos de esta página")
        List<T> content,

        @Schema(description = "Número de página, empezando en 0", example = "0")
        int page,

        @Schema(description = "Tamaño de página solicitado", example = "20")
        int size,

        @Schema(description = "Total de elementos en todas las páginas", example = "42")
        long totalElements,

        @Schema(description = "Total de páginas", example = "3")
        int totalPages,

        @Schema(description = "Si existe una página siguiente")
        boolean hasNext
) {

    /** Convierte una {@link Page} de entidades aplicando un mapeador por elemento. */
    public static <E, T> PagedResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PagedResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
