package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.domain.GastronomicCulture;
import co.edu.uniandes.culturas.domain.Slugs;
import co.edu.uniandes.culturas.repository.GastronomicCultureRepository;
import co.edu.uniandes.culturas.web.dto.CultureDtos;
import co.edu.uniandes.culturas.web.dto.PagedResponse;
import co.edu.uniandes.culturas.web.error.DomainRuleException;
import co.edu.uniandes.culturas.web.error.ResourceNotFoundException;
import co.edu.uniandes.culturas.web.mapper.CultureMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;

/**
 * Casos de uso sobre culturas gastronómicas.
 *
 * <p>Inyección por constructor, no por campo: el proyecto de 2023 usaba
 * {@code @Autowired} sobre campos privados en todos los servicios, lo que hacía
 * imposible instanciarlos en un test sin levantar el contexto de Spring y
 * dejaba todas las dependencias mutables.
 */
@Service
@Transactional(readOnly = true)
public class CultureService {

    private final GastronomicCultureRepository repository;
    private final CultureMapper mapper;

    public CultureService(GastronomicCultureRepository repository, CultureMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Listado paginado con los conteos de hijos resueltos en una sola consulta
     * agregada para toda la página.
     *
     * <p>Los endpoints {@code /all} del proyecto original hacían
     * {@code findAll()} y dejaban que ModelMapper recorriera las colecciones de
     * cada entidad, disparando una consulta por colección y por fila.
     */
    public PagedResponse<CultureDtos.Summary> list(Pageable pageable) {
        Page<GastronomicCulture> page = repository.findAllSummaries(pageable);

        if (page.isEmpty()) {
            return PagedResponse.from(page, culture -> mapper.toSummary(culture, 0, 0));
        }

        Map<Long, GastronomicCultureRepository.CultureCounts> counts =
                repository.countChildrenFor(page.getContent().stream().map(GastronomicCulture::getId).toList())
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                GastronomicCultureRepository.CultureCounts::getCultureId,
                                Function.identity()));

        return PagedResponse.from(page, culture -> {
            var c = counts.get(culture.getId());
            return mapper.toSummary(culture,
                    c == null ? 0 : c.getRecipeCount(),
                    c == null ? 0 : c.getCategoryCount());
        });
    }

    public CultureDtos.Detail findBySlug(String slug) {
        return repository.findDetailBySlug(slug)
                .map(mapper::toDetail)
                .orElseThrow(() -> new ResourceNotFoundException("cultura gastronómica", slug));
    }

    @Transactional
    public CultureDtos.Detail create(CultureDtos.Request request) {
        String slug = Slugs.of(request.name());

        // Comprobación previa para dar un 422 explicativo en el caso normal. No
        // es la garantía: bajo concurrencia dos peticiones pueden pasar por aquí
        // a la vez, y entonces manda la restricción UNIQUE de la base, que el
        // manejador global traduce a 409.
        if (repository.existsBySlug(slug)) {
            throw new DomainRuleException("culture-slug-duplicado",
                    "Ya existe una cultura cuyo nombre produce la dirección '%s'".formatted(slug));
        }

        GastronomicCulture culture = new GastronomicCulture();
        mapper.applyTo(request, culture);
        culture.setSlug(slug);

        return mapper.toDetail(repository.save(culture));
    }

    /**
     * Actualiza una cultura existente.
     *
     * <p>El arreglo estructural respecto a 2023: se carga la entidad
     * <strong>gestionada</strong> y el mapeador escribe sobre ella. Allí se
     * construía una entidad nueva desde el DTO plano, se le ponía el id y se
     * llamaba a {@code save()}; como esa entidad llegaba con las colecciones
     * vacías, cada actualización borraba recetas, categorías y países.
     *
     * <p>El slug no se recalcula al renombrar: es un identificador público y
     * cambiarlo rompería los enlaces que ya existan.
     */
    @Transactional
    public CultureDtos.Detail update(String slug, CultureDtos.Request request) {
        GastronomicCulture culture = repository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("cultura gastronómica", slug));

        mapper.applyTo(request, culture);
        return mapper.toDetail(culture);
    }

    /**
     * Borra una cultura.
     *
     * <p>Sin comprobación previa de hijos: el esquema declara ON DELETE CASCADE
     * hacia abajo, así que recetas y categorías se van con ella. Es un cambio
     * deliberado respecto a 2023, donde el servicio bloqueaba el borrado si
     * había cualquier hijo y no ofrecía forma de continuar.
     */
    @Transactional
    public void delete(String slug) {
        GastronomicCulture culture = repository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("cultura gastronómica", slug));
        repository.delete(culture);
    }
}
