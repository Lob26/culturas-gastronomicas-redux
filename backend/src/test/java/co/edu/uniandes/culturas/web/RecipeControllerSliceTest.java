package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.service.RecipeService;
import co.edu.uniandes.culturas.web.error.DomainRuleException;
import co.edu.uniandes.culturas.web.error.GlobalExceptionHandler;
import co.edu.uniandes.culturas.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Corte de MVC del controlador de recetas: sin base de datos y sin contenedores.
 *
 * <p>El servicio es un doble, así que lo único que se ejerce es la capa web —
 * enlace de parámetros, validación del cuerpo, negociación de contenido y la
 * traducción de excepciones a RFC 9457—. Arranca en menos de un segundo, así
 * que estas comprobaciones se pueden multiplicar sin que la suite se resienta.
 *
 * <p><strong>Los filtros van desactivados</strong> ({@code addFilters = false})
 * y es deliberado: aquí se prueba MVC, no seguridad. Las reglas de autorización
 * se comprueban en ApiContractIT, contra la cadena de filtros de verdad; un
 * corte con la seguridad por defecto de Boot probaría una cadena que no es la
 * que corre en producción, y daría una falsa sensación de cobertura.
 */
@WebMvcTest(RecipeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class RecipeControllerSliceTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RecipeService service;

    private static final String VALID_BODY = """
            {"name":"Receta válida","description":"algo","cultureSlug":"cocina-italiana","steps":["uno"]}
            """;

    // ------------------------------------------------------------ validación --

    @Test
    @DisplayName("el nombre en blanco se rechaza antes de tocar el servicio")
    void blankNameIsRejected() throws Exception {
        mvc.perform(post("/api/v2/recetas").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   ","cultureSlug":"cocina-italiana","steps":["uno"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[*].campo").value(org.hamcrest.Matchers.hasItem("name")));

        // Lo importante de una validación de frontera: el servicio ni se entera.
        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("una receta sin pasos se rechaza")
    void stepsAreRequired() throws Exception {
        mvc.perform(post("/api/v2/recetas").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sin pasos","cultureSlug":"cocina-italiana","steps":[]}
                                """))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("un paso vacío dentro de la lista también se rechaza")
    void blankStepInsideListIsRejected() throws Exception {
        // La anotación va dentro del genérico: List<@NotBlank String>. Es fácil
        // de perder en un refactor y el síntoma sería una receta con un paso en
        // blanco que el modo cocina muestra como una pantalla vacía.
        mvc.perform(post("/api/v2/recetas").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Con hueco","cultureSlug":"cocina-italiana","steps":["uno","  "]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("los números fuera de rango se rechazan")
    void positiveNumbersAreEnforced() throws Exception {
        mvc.perform(post("/api/v2/recetas").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Tiempos raros","cultureSlug":"cocina-italiana","steps":["uno"],
                                 "prepTimeMinutes":-5,"servings":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("falta la cultura: 400 y no una receta huérfana")
    void cultureSlugIsRequired() throws Exception {
        mvc.perform(post("/api/v2/recetas").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sin cultura","steps":["uno"]}
                                """))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    // ------------------------------------------- traducción de excepciones --

    @Test
    @DisplayName("ResourceNotFoundException sale como 404 en problem+json")
    void notFoundIsTranslated() throws Exception {
        given(service.findBySlug(eq("fantasma"))).willThrow(new ResourceNotFoundException("receta", "fantasma"));

        mvc.perform(get("/api/v2/recetas/fantasma"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.recurso").value("receta"))
                .andExpect(jsonPath("$.identificador").value("fantasma"));
    }

    @Test
    @DisplayName("DomainRuleException sale como 422 con el nombre de la regla")
    void domainRuleIsTranslated() throws Exception {
        given(service.create(any()))
                .willThrow(new DomainRuleException("receta-slug-duplicado", "Ya existe una receta así"));

        mvc.perform(post("/api/v2/recetas").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.regla").value("receta-slug-duplicado"));
    }

    @Test
    @DisplayName("un fallo inesperado sale como 500 sin filtrar la traza")
    void unexpectedFailureDoesNotLeakInternals() throws Exception {
        given(service.findBySlug(any())).willThrow(new RuntimeException("NullPointerException en RecipeMapper línea 42"));

        String body = mvc.perform(get("/api/v2/recetas/loquesea"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        // El whitelabel de Spring filtraba detalles internos. El mensaje real
        // va al log, no a la respuesta.
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("RecipeMapper")
                .doesNotContain("NullPointerException");
    }

    @Test
    @DisplayName("borrar responde 204 sin cuerpo")
    void deleteReturnsNoContent() throws Exception {
        mvc.perform(delete("/api/v2/recetas/pasta-carbonara"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).delete("pasta-carbonara");
    }

    @Test
    @DisplayName("un método no permitido da 405, no 500")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(delete("/api/v2/recetas"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("un tipo de contenido que no es JSON da 415")
    void wrongContentTypeIsUnsupportedMediaType() throws Exception {
        mvc.perform(post("/api/v2/recetas").contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("un parámetro de paginación no numérico se ignora en silencio")
    void nonNumericPageFallsBackToDefault() throws Exception {
        // Comportamiento de Spring Data, no del proyecto: el resolutor de
        // Pageable descarta lo que no puede convertir y usa el valor por
        // defecto en lugar de fallar.
        //
        // Se fija con un test aunque no sea lo que uno esperaría, por dos
        // motivos: deja constancia de que es una decisión conocida y no un
        // descuido, y si una versión futura cambiara a devolver 400, este test
        // se pondría rojo y nos enteraríamos por aquí y no por un cliente.
        //
        // No se fuerza el 400 porque cambiar el resolutor afecta a todos los
        // listados del catálogo, y el precio —un cliente con un typo recibe la
        // primera página en vez de un error— es menor que el de tocar el
        // enlace de parámetros de toda la API.
        mvc.perform(get("/api/v2/recetas").param("page", "abc"))
                .andExpect(status().isOk());

        verify(service).list(org.mockito.ArgumentMatchers.argThat(
                pageable -> pageable.getPageNumber() == 0));
    }

    @Test
    @DisplayName("el servicio recibe el slug tal cual llega en la ruta")
    void pathVariableReachesService() throws Exception {
        willThrow(new ResourceNotFoundException("receta", "con-guiones-y-numeros-123"))
                .given(service).findBySlug("con-guiones-y-numeros-123");

        mvc.perform(get("/api/v2/recetas/con-guiones-y-numeros-123"))
                .andExpect(status().isNotFound());
    }
}
