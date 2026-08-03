package co.edu.uniandes.culturas;

import co.edu.uniandes.culturas.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El contrato HTTP, con la cadena de seguridad real.
 *
 * <p>Aquí no hay dobles de nada: la petición atraviesa los filtros de Spring
 * Security de verdad, los servicios reales y Postgres. Es donde se comprueban
 * las reglas de autorización, porque un {@code @WebMvcTest} con los filtros
 * desactivados diría que todo funciona sin haber ejecutado ni una de ellas.
 *
 * <p>El énfasis está en los caminos que fallan. Los que funcionan ya los cubre
 * el end-to-end; lo que rara vez se prueba —y es donde se esconden los fallos
 * de seguridad y los 500 evitables— es qué pasa cuando el cliente se equivoca.
 */
@IntegrationTest
class ApiContractIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    /** Registra un usuario nuevo y devuelve su token. */
    private String tokenForNewUser() throws Exception {
        String username = "it" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = mvc.perform(post("/api/v2/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","displayName":"Prueba","password":"clave-de-prueba-123"}
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asString();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ------------------------------------------------------------ lecturas --

    @Test
    @DisplayName("las lecturas del catálogo son públicas")
    void catalogReadsArePublic() throws Exception {
        mvc.perform(get("/api/v2/culturas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("un recurso que no existe da 404 en formato RFC 9457")
    void missingResourceIsProblemDetail() throws Exception {
        mvc.perform(get("/api/v2/culturas/no-existe-esta-cultura"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("cada respuesta lleva identificador de correlación")
    void everyResponseCarriesCorrelationId() throws Exception {
        mvc.perform(get("/api/v2/culturas").header("X-Request-Id", "traza-fija"))
                .andExpect(header().string("X-Request-Id", "traza-fija"));

        mvc.perform(get("/api/v2/culturas"))
                .andExpect(header().exists("X-Request-Id"));
    }

    // -------------------------------------------------------- autorización --

    @Test
    @DisplayName("escribir sin identidad da 401, no 403 ni 500")
    void writesRequireIdentity() throws Exception {
        mvc.perform(post("/api/v2/culturas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cocina inventada"}
                                """))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/v2/culturas/cocina-italiana"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un token inventado da 401 y no revienta el filtro")
    void garbageTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v2/favoritos").header("Authorization", "Bearer esto.no.es.un.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("el recetario exige identidad aunque sea un GET")
    void personalEndpointsAreNotPublicReads() throws Exception {
        // La trampa: la regla general abre todos los GET del catálogo. Sin una
        // regla previa, este endpoint recibiría un principal nulo.
        mvc.perform(get("/api/v2/favoritos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/buscar/recomendaciones")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/asistente/preguntar?q=hola")).andExpect(status().isUnauthorized());

        String token = tokenForNewUser();
        mvc.perform(get("/api/v2/favoritos").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("un usuario normal no puede lanzar trabajos de administración")
    void adminJobsRejectPlainUsers() throws Exception {
        String token = tokenForNewUser();

        mvc.perform(post("/api/v2/jobs/reindexar").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/jobs/respaldo").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("las métricas de actuator no son públicas")
    void actuatorMetricsAreProtected() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------- validación --

    @Test
    @DisplayName("la validación da 400 con el detalle por campo, no un 412")
    void validationReturnsFieldDetails() throws Exception {
        String token = tokenForNewUser();

        String body = mvc.perform(post("/api/v2/culturas")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();

        // El original mapeaba los fallos de validación a 412 Precondition
        // Failed y no decía qué campo estaba mal.
        JsonNode problem = json.readTree(body);
        assertThat(problem.get("errores")).isNotNull();
        assertThat(problem.get("errores").toString()).contains("name");
    }

    @Test
    @DisplayName("un JSON con campos desconocidos se rechaza, no se ignora")
    void unknownPropertiesAreRejected() throws Exception {
        String token = tokenForNewUser();

        // fail-on-unknown-properties: un cliente que manda «nombre» en vez de
        // «name» debe enterarse, no crear una cultura con el nombre vacío.
        mvc.perform(post("/api/v2/culturas")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cocina X","campoQueNoExiste":1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("un cuerpo que no es JSON da 400 y no 500")
    void malformedBodyIsClientError() throws Exception {
        String token = tokenForNewUser();

        mvc.perform(post("/api/v2/culturas")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{esto no es json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("crear algo que ya existe da 409 o 422, nunca un 500")
    void duplicateIsDomainErrorNotCrash() throws Exception {
        String token = tokenForNewUser();

        int status = mvc.perform(post("/api/v2/culturas")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cocina italiana"}
                                """))
                .andReturn().getResponse().getStatus();

        // En 2023 las ocho restricciones únicas salían como 500 sin explicación.
        assertThat(status).isIn(409, 422);
    }

    @Test
    @DisplayName("los límites del parámetro de búsqueda se validan")
    void searchLimitIsBounded() throws Exception {
        mvc.perform(get("/api/v2/buscar?q=arroz&limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/buscar?q=arroz&limit=999")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/buscar?limit=5")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/buscar?q=arroz&limit=5")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("la búsqueda aguanta entrada hostil sin romperse")
    void searchSurvivesHostileInput() throws Exception {
        // Con to_tsquery en vez de websearch_to_tsquery, cada una sería un error
        // de sintaxis y un 500.
        for (String q : new String[]{"\"sin cerrar", "arroz &", "a", "; DROP TABLE recipe;--", "%", "()"}) {
            mvc.perform(get("/api/v2/buscar").param("q", q).param("limit", "5"))
                    .andExpect(status().isOk());
        }

        // Y la tabla sigue ahí, que es la mitad que importa de la inyección.
        mvc.perform(get("/api/v2/recetas")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------- social --

    @Test
    @DisplayName("valorar es idempotente: repetir actualiza y no choca con la unicidad")
    void ratingIsUpsert() throws Exception {
        String token = tokenForNewUser();

        mvc.perform(put("/api/v2/recetas/pasta-carbonara/valoraciones/mia")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"score":5,"comment":"muy buena"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(5));

        mvc.perform(put("/api/v2/recetas/pasta-carbonara/valoraciones/mia")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"score":3,"comment":"me retracto"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(3));

        mvc.perform(delete("/api/v2/recetas/pasta-carbonara/valoraciones/mia")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("una puntuación fuera de rango se rechaza en la frontera")
    void ratingScoreIsValidatedAtTheBoundary() throws Exception {
        String token = tokenForNewUser();

        for (String score : new String[]{"0", "6", "-1"}) {
            mvc.perform(put("/api/v2/recetas/pasta-carbonara/valoraciones/mia")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"score\":%s}".formatted(score)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("valorar una receta que no existe da 404, no crea nada")
    void ratingUnknownRecipeIsNotFound() throws Exception {
        String token = tokenForNewUser();

        mvc.perform(put("/api/v2/recetas/no-existe/valoraciones/mia")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"score":4}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el registro rechaza usuario duplicado y contraseña corta")
    void registrationValidatesInput() throws Exception {
        String username = "dup" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String body = """
                {"username":"%s","displayName":"Prueba","password":"clave-de-prueba-123"}
                """.formatted(username);

        mvc.perform(post("/api/v2/auth/registro").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        int repeated = mvc.perform(post("/api/v2/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
        assertThat(repeated).isIn(409, 422);

        mvc.perform(post("/api/v2/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"otro-usuario-valido","displayName":"X","password":"corta"}
                                """))
                .andExpect(status().isBadRequest());

        // El patrón del nombre de usuario también se impone.
        mvc.perform(post("/api/v2/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"CON MAYUSCULAS Y ESPACIOS","displayName":"X","password":"clave-de-prueba-123"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("acceder con contraseña incorrecta da 401 y no dice cuál de los dos falló")
    void loginFailsClosed() throws Exception {
        mvc.perform(post("/api/v2/auth/acceso")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"no-existe-este-usuario","password":"loquesea1234"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
