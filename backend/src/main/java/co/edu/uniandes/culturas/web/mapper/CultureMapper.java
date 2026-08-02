package co.edu.uniandes.culturas.web.mapper;

import co.edu.uniandes.culturas.domain.Country;
import co.edu.uniandes.culturas.domain.GastronomicCulture;
import co.edu.uniandes.culturas.web.dto.CultureDtos;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * Conversión entre entidades de cultura y sus DTOs.
 *
 * <p>MapStruct genera el código en tiempo de compilación, así que un campo del
 * destino sin origen es un error de compilación
 * ({@code unmappedTargetPolicy=ERROR} en el pom) y no un null en producción. El
 * proyecto de 2023 usaba ModelMapper, que resuelve por reflexión en cada
 * petición y falla —o acierta por casualidad— en tiempo de ejecución.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface CultureMapper {

    /**
     * Vista de listado. Los conteos llegan como parámetros porque no viven en
     * la entidad: se resuelven en una única consulta agregada para toda la
     * página, no recorriendo las colecciones de cada fila.
     */
    @Mapping(target = "recipeCount", source = "recipeCount")
    @Mapping(target = "categoryCount", source = "categoryCount")
    CultureDtos.Summary toSummary(GastronomicCulture culture, long recipeCount, long categoryCount);

    CultureDtos.Detail toDetail(GastronomicCulture culture);

    @Mapping(target = "iso2", source = "iso2")
    CultureDtos.CountrySummary toCountrySummary(Country country);

    /**
     * Aplica los cambios sobre la entidad <strong>gestionada</strong>.
     *
     * <p>Éste es el arreglo del defecto más grave del proyecto de 2023: allí
     * {@code update()} construía una entidad nueva desde el DTO plano, le
     * asignaba el id y llamaba a {@code save()}. Como esa entidad venía con las
     * colecciones vacías, cada actualización borraba las recetas, las
     * categorías y los países de la cultura.
     *
     * <p>Al recibir la entidad con {@code @MappingTarget}, sólo se tocan los
     * campos escalares presentes en la petición; las asociaciones ni se miran.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "slug", ignore = true)
    @Mapping(target = "recipes", ignore = true)
    @Mapping(target = "categories", ignore = true)
    @Mapping(target = "countries", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void applyTo(CultureDtos.Request request, @MappingTarget GastronomicCulture culture);
}
