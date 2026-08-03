package co.edu.uniandes.culturas.web;

import co.edu.uniandes.culturas.config.CulturasProperties;
import co.edu.uniandes.culturas.service.RagService;
import co.edu.uniandes.culturas.service.RateLimiter;
import co.edu.uniandes.culturas.web.error.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los caminos por los que el asistente <em>no</em> responde.
 *
 * <p>El camino feliz necesita un modelo generando y lo cubre el end-to-end. Lo
 * que se comprueba aquí es lo otro: qué pasa cuando Ollama está apagado, cuando
 * el usuario agota su cupo y cuando la pregunta no vale. Son ramas baratas de
 * probar con dobles y caras de reproducir a mano.
 */
/*
 * Aquí los filtros van ACTIVADOS, al revés que en el resto de los cortes.
 *
 * Este controlador recibe el usuario con @AuthenticationPrincipal Jwt, y ese
 * resolutor lee del contexto de seguridad. Con los filtros desactivados el
 * contexto no se instala, así que el resolutor intenta construir un Jwt vacío y
 * revienta con «tokenValue cannot be empty» — un 500 que parece un fallo del
 * controlador y es del montaje del test.
 *
 * Con los filtros puestos, el postprocesador jwt() de spring-security-test deja
 * una autenticación real en el contexto y todo encaja. La cadena que se aplica
 * es la de por defecto de Boot y no la del proyecto: para lo que se prueba aquí
 * —qué responde el endpoint según el estado del asistente— da igual, y las
 * reglas de autorización de verdad están en ApiContractIT.
 */
/*
 * El cupo se fija por propiedad y NO se sustituye CulturasProperties por un
 * doble. Dos razones: el bean real ya lo registra @EnableConfigurationProperties
 * de la clase de aplicación —añadir otro deja dos del mismo tipo y la inyección
 * falla por ambigüedad— y, sobre todo, AssistantController lee
 * `properties.limits()` en su CONSTRUCTOR, que corre al crear el contexto, antes
 * que cualquier @BeforeEach. Con un mock, ese campo se quedaba a null y todo lo
 * que pasaba del 503 moría en un NPE que salía como 500.
 */
@WebMvcTest(AssistantController.class)
@TestPropertySource(properties = "culturas.limits.assistant-per-hour=10")
@Import({GlobalExceptionHandler.class, AssistantControllerSliceTest.WebSecurityForSlice.class})
class AssistantControllerSliceTest {

    /**
     * Registra la integración de Spring Security con MVC.
     *
     * <p>Sin {@code @EnableWebSecurity} no se registra
     * {@code AuthenticationPrincipalArgumentResolver}, así que MVC no reconoce
     * {@code @AuthenticationPrincipal Jwt} y lo trata como un atributo de
     * modelo: intenta <em>construir</em> un Jwt vacío y falla con
     * «tokenValue cannot be empty». El síntoma es un 500 en todos los casos,
     * que parece del controlador y es del montaje.
     *
     * <p>La cadena deja pasar todo porque aquí no se prueba autorización: eso
     * es de ApiContractIT, contra la cadena real.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class WebSecurityForSlice {

        @Bean
        SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }

    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RagService rag;

    @MockitoBean
    RateLimiter rateLimiter;

    @Test
    @DisplayName("sin Ollama en marcha: 503 en problem+json, no 500")
    void unavailableWhenOllamaIsDown() throws Exception {
        given(rag.enabled()).willReturn(true);
        given(rag.reachable()).willReturn(false);

        mvc.perform(get("/api/v2/asistente/preguntar").param("q", "¿qué lleva la carbonara?")
                        .with(jwt().jwt(builder -> builder.subject("alguien"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        // Y no se cobra cupo por algo que no se pudo servir.
        verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("sin modelo configurado: 503 y no se consulta el catálogo")
    void unavailableWhenNoModelConfigured() throws Exception {
        given(rag.enabled()).willReturn(false);

        mvc.perform(get("/api/v2/asistente/preguntar").param("q", "una pregunta cualquiera")
                        .with(jwt().jwt(builder -> builder.subject("alguien"))))
                .andExpect(status().isServiceUnavailable());

        // Recuperar documentos cuesta una búsqueda completa: no tiene sentido
        // pagarla para después no poder generar nada.
        verify(rag, never()).retrieve(anyString());
    }

    @Test
    @DisplayName("cupo agotado: 429 con Retry-After, no 403")
    void quotaExhaustedIsTooManyRequests() throws Exception {
        given(rag.enabled()).willReturn(true);
        given(rag.reachable()).willReturn(true);
        given(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(new RateLimiter.Decision(false, 0, Duration.ofHours(1)));

        mvc.perform(get("/api/v2/asistente/preguntar").param("q", "otra pregunta")
                        .with(jwt().jwt(builder -> builder.subject("alguien"))))
                // 429 y no 403: un 403 diría «no puedes», y lo cierto es
                // «ahora no». La diferencia es la que permite reintentar.
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"));

        verify(rag, never()).retrieve(anyString());
    }

    @Test
    @DisplayName("sin fuentes no se llama al modelo: se responde que no hay nada")
    void noSourcesMeansNoGeneration() throws Exception {
        given(rag.enabled()).willReturn(true);
        given(rag.reachable()).willReturn(true);
        given(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(new RateLimiter.Decision(true, 9, Duration.ofHours(1)));
        given(rag.retrieve(anyString())).willReturn(List.of());

        mvc.perform(get("/api/v2/asistente/preguntar").param("q", "algo que no está en el catálogo")
                        .with(jwt().jwt(builder -> builder.subject("alguien"))))
                .andExpect(status().isOk());

        // Preguntarle al modelo sin fuentes gastaría una generación para
        // obtener, con suerte, un «no lo sé», y con menos suerte una respuesta
        // inventada: justo lo que este diseño evita.
        verify(rag, never()).answer(anyString(), any(), any());
    }

    @Test
    @DisplayName("una pregunta demasiado corta o vacía se rechaza con 400")
    void questionIsValidated() throws Exception {
        given(rag.enabled()).willReturn(true);
        given(rag.reachable()).willReturn(true);

        mvc.perform(get("/api/v2/asistente/preguntar").param("q", "a")
                        .with(jwt().jwt(builder -> builder.subject("alguien"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores").exists());

        mvc.perform(get("/api/v2/asistente/preguntar").param("q", "   ")
                        .with(jwt().jwt(builder -> builder.subject("alguien"))))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v2/asistente/preguntar")
                        .with(jwt().jwt(builder -> builder.subject("alguien"))))
                .andExpect(status().isBadRequest());
    }
}
