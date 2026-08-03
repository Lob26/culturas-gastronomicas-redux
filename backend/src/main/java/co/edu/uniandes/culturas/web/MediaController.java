package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.domain.DishMultimedia;
import co.edu.uniandes.culturas.domain.Recipe;
import co.edu.uniandes.culturas.repository.DishMultimediaRepository;
import co.edu.uniandes.culturas.repository.RecipeRepository;
import co.edu.uniandes.culturas.service.MediaService;
import co.edu.uniandes.culturas.web.error.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Imágenes de las recetas.
 *
 * <p>El identificador que se guarda es la clave del objeto, no una URL: las URL
 * firmadas caducan, así que persistir una dejaría enlaces muertos en cuanto
 * pasara una hora. Se firma al leer.
 */
@RestController
@RequestMapping("/api/v2/recetas/{slug}/imagenes")
@Tag(name = "Imágenes", description = "Subida de imágenes al almacén de objetos")
public class MediaController {

    /**
     * Prefijo que distingue una clave del almacén de una URL externa.
     *
     * <p>La misma columna guarda las dos cosas: las imágenes sembradas son
     * enlaces a sitios ajenos y las subidas son objetos propios. Un prefijo
     * explícito evita adivinar por la forma de la cadena, que es justo el tipo
     * de heurística que falla el día que aparece una URL rara.
     */
    public static final String STORAGE_PREFIX = "minio://";

    private final MediaService media;
    private final RecipeRepository recipes;
    private final DishMultimediaRepository images;

    public MediaController(MediaService media,
                           RecipeRepository recipes,
                           DishMultimediaRepository images) {
        this.media = media;
        this.recipes = recipes;
        this.images = images;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Sube una imagen y la asocia a la receta")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Imagen guardada"),
            @ApiResponse(responseCode = "401", description = "Hace falta identificarse"),
            @ApiResponse(responseCode = "404", description = "La receta no existe"),
            @ApiResponse(responseCode = "422", description = "Formato no admitido o archivo vacío")
    })
    @Transactional
    public Map<String, String> upload(@PathVariable String slug,
                                      @RequestParam("archivo") MultipartFile archivo) {
        Recipe recipe = recipes.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("receta", slug));

        // Primero al almacén y luego a la base. Si falla la subida no queda una
        // fila apuntando a nada; si falla la base queda un objeto huérfano, que
        // es el fallo barato — la política de expiración del bucket, declarada
        // en Terraform, se encarga de ellos.
        String key = media.upload(archivo);

        DishMultimedia image = new DishMultimedia();
        image.setRecipe(recipe);
        image.setUrl(STORAGE_PREFIX + key);
        image.setPosition((short) (recipe.getImages().size() + 1));
        images.save(image);

        return Map.of("clave", key, "url", media.presignedUrl(key));
    }

    /**
     * Firma una URL de lectura.
     *
     * <p>Endpoint aparte y no una URL fija en el detalle de la receta porque la
     * firma caduca: incrustarla en la respuesta del catálogo la haría inservible
     * en cuanto esa respuesta se cacheara más de una hora.
     */
    @GetMapping("/{clave}/url")
    @Operation(summary = "Devuelve una URL temporal de lectura para una imagen subida")
    public Map<String, String> url(@PathVariable String slug, @PathVariable String clave) {
        return Map.of("url", media.presignedUrl("recetas/" + clave));
    }
}
