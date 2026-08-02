package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.domain.GastronomicCulture;
import co.edu.uniandes.culturas.domain.Ingredient;
import co.edu.uniandes.culturas.domain.Recipe;
import co.edu.uniandes.culturas.domain.RecipeStep;
import co.edu.uniandes.culturas.domain.Slugs;
import co.edu.uniandes.culturas.repository.GastronomicCultureRepository;
import co.edu.uniandes.culturas.repository.RecipeRepository;
import co.edu.uniandes.culturas.web.dto.PagedResponse;
import co.edu.uniandes.culturas.web.dto.RecipeDtos;
import co.edu.uniandes.culturas.web.error.DomainRuleException;
import co.edu.uniandes.culturas.web.error.ResourceNotFoundException;
import co.edu.uniandes.culturas.web.mapper.RecipeMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class RecipeService {

    /**
     * Detecta duraciones en el texto de un paso: «20 minutos», «2 horas»,
     * «cocer 20-25 minutos». Con un rango se toma el extremo superior, que es
     * el que evita levantar la tapa antes de tiempo.
     */
    private static final Pattern DURATION = Pattern.compile(
            "(\\d+)\\s*(?:a|-|–)?\\s*(\\d+)?\\s*(minutos?|horas?|segundos?)",
            Pattern.CASE_INSENSITIVE);

    private final RecipeRepository repository;
    private final GastronomicCultureRepository cultureRepository;
    private final RecipeMapper mapper;

    public RecipeService(RecipeRepository repository,
                         GastronomicCultureRepository cultureRepository,
                         RecipeMapper mapper) {
        this.repository = repository;
        this.cultureRepository = cultureRepository;
        this.mapper = mapper;
    }

    public PagedResponse<RecipeDtos.Summary> list(Pageable pageable) {
        return withCovers(repository.findAllWithCulture(pageable));
    }

    public PagedResponse<RecipeDtos.Summary> listByCulture(String cultureSlug, Pageable pageable) {
        if (!cultureRepository.existsBySlug(cultureSlug)) {
            throw new ResourceNotFoundException("cultura gastronómica", cultureSlug);
        }
        return withCovers(repository.findByCultureSlug(cultureSlug, pageable));
    }

    /** Resuelve la portada de toda la página en una consulta, no una por fila. */
    private PagedResponse<RecipeDtos.Summary> withCovers(Page<Recipe> page) {
        if (page.isEmpty()) {
            return PagedResponse.from(page, recipe -> mapper.toSummary(recipe, null));
        }
        Map<Long, String> covers = repository
                .findCoversFor(page.getContent().stream().map(Recipe::getId).toList())
                .stream()
                .collect(Collectors.toMap(RecipeRepository.RecipeCover::getRecipeId,
                        RecipeRepository.RecipeCover::getUrl));

        return PagedResponse.from(page, recipe -> mapper.toSummary(recipe, covers.get(recipe.getId())));
    }

    /**
     * Detalle completo.
     *
     * <p>Tres consultas en lugar de una con tres {@code JOIN FETCH}: unir las
     * tres colecciones multiplicaría filas (6 pasos × 5 ingredientes × 2
     * imágenes = 60 filas para hidratar 13 objetos). Al ejecutarlas por
     * separado dentro de la misma transacción, Hibernate va poblando la misma
     * instancia del contexto de persistencia.
     */
    public RecipeDtos.Detail findBySlug(String slug) {
        Recipe recipe = repository.findDetailBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("receta", slug));
        repository.loadIngredients(recipe.getId());
        repository.loadImages(recipe.getId());
        return mapper.toDetail(recipe);
    }

    @Transactional
    public RecipeDtos.Detail create(RecipeDtos.Request request) {
        GastronomicCulture culture = cultureRepository.findBySlug(request.cultureSlug())
                .orElseThrow(() -> new ResourceNotFoundException("cultura gastronómica", request.cultureSlug()));

        String slug = Slugs.of(request.name());
        if (repository.existsBySlug(slug)) {
            throw new DomainRuleException("receta-slug-duplicado",
                    "Ya existe una receta cuyo nombre produce la dirección '%s'".formatted(slug));
        }

        Recipe recipe = new Recipe();
        recipe.setName(request.name());
        recipe.setSlug(slug);
        recipe.setDescription(request.description());
        recipe.setPrepTimeMinutes(request.prepTimeMinutes());
        recipe.setServings(request.servings());
        recipe.setDifficulty(request.difficulty());
        culture.addRecipe(recipe);

        applyStepsAndIngredients(recipe, request);
        return mapper.toDetail(repository.save(recipe));
    }

    @Transactional
    public RecipeDtos.Detail update(String slug, RecipeDtos.Request request) {
        Recipe recipe = repository.findDetailBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("receta", slug));
        repository.loadIngredients(recipe.getId());

        recipe.setName(request.name());
        recipe.setDescription(request.description());
        recipe.setPrepTimeMinutes(request.prepTimeMinutes());
        recipe.setServings(request.servings());
        recipe.setDifficulty(request.difficulty());

        // Pasos e ingredientes se reemplazan en bloque: son partes de la
        // receta, no entidades con vida propia, y no tienen identidad estable
        // que el cliente pueda referenciar. orphanRemoval borra los anteriores.
        recipe.getSteps().clear();
        recipe.getIngredients().clear();
        applyStepsAndIngredients(recipe, request);

        repository.loadImages(recipe.getId());
        return mapper.toDetail(recipe);
    }

    @Transactional
    public void delete(String slug) {
        Recipe recipe = repository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("receta", slug));
        repository.delete(recipe);
    }

    private void applyStepsAndIngredients(Recipe recipe, RecipeDtos.Request request) {
        // Las posiciones las asigna el servidor a partir del orden recibido:
        // dejar que las numere el cliente permitiría huecos y duplicados, que
        // la restricción UNIQUE (receta, posición) rechazaría con un 409 poco
        // explicativo.
        request.steps().forEach(text -> {
            RecipeStep step = new RecipeStep();
            step.setInstruction(text);
            step.setDurationSeconds(parseDuration(text));
            recipe.addStep(step);
        });

        if (request.ingredients() != null) {
            request.ingredients().forEach(item -> {
                Ingredient ingredient = new Ingredient();
                ingredient.setName(item.name());
                ingredient.setQuantity(item.quantity());
                ingredient.setUnit(item.unit());
                recipe.addIngredient(ingredient);
            });
        }
    }

    /**
     * Extrae la duración mencionada en un paso, en segundos, o null si no
     * menciona ninguna — que es el caso más frecuente.
     */
    static Integer parseDuration(String instruction) {
        Matcher matcher = DURATION.matcher(instruction);
        if (!matcher.find()) {
            return null;
        }
        // Con un rango («20 a 25 minutos») se usa el extremo superior.
        String upper = matcher.group(2) != null ? matcher.group(2) : matcher.group(1);
        int amount = Integer.parseInt(upper);
        String unit = matcher.group(3).toLowerCase();

        int seconds = unit.startsWith("hora") ? amount * 3600
                : unit.startsWith("minuto") ? amount * 60
                : amount;

        // Un «2» suelto en «2 cucharadas» no llega aquí porque el patrón exige
        // la unidad temporal, pero sí conviene descartar duraciones absurdas.
        return seconds > 0 && seconds <= 24 * 3600 ? seconds : null;
    }
}
