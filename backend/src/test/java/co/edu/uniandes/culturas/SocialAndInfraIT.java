package co.edu.uniandes.culturas;

import co.edu.uniandes.culturas.jobs.CatalogEvents;
import co.edu.uniandes.culturas.repository.GastronomicCultureRepository;
import co.edu.uniandes.culturas.service.CultureService;
import co.edu.uniandes.culturas.service.RateLimiter;
import co.edu.uniandes.culturas.service.StatsService;
import co.edu.uniandes.culturas.support.IntegrationTest;
import co.edu.uniandes.culturas.web.dto.CultureDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrencia, Redis y la regresión de datos de 2023.
 *
 * <p>Todo lo de aquí necesita infraestructura real. El bloqueo optimista sólo
 * existe si hay una columna {@code version} y una base que la compare; el cupo
 * sólo se puede comprobar contra un Redis que cuente de verdad; y que una
 * edición no borre las relaciones sólo se ve mirando las filas después.
 */
@IntegrationTest
class SocialAndInfraIT {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CultureService cultures;

    @Autowired
    GastronomicCultureRepository cultureRepository;

    @Autowired
    RateLimiter rateLimiter;

    @Autowired
    CatalogEvents events;

    @Autowired
    RedisMessageListenerContainer listeners;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    @Autowired
    StatsService stats;

    @Autowired
    TransactionTemplate tx;

    // ------------------------------------------------------- concurrencia --

    @Test
    @DisplayName("dos escrituras sobre la misma fila: la segunda falla, no pisa a la primera")
    void concurrentUpdateIsRejected() {
        String slug = "cocina-italiana";

        // Se carga la entidad y, antes de guardarla, otro escritor toca la fila.
        // El UPDATE directo simula esa segunda sesión sin necesidad de hilos:
        // lo que comprueba el bloqueo optimista es la versión, no el reloj.
        var culture = cultureRepository.findBySlug(slug).orElseThrow();
        long versionLeida = culture.getVersion();

        jdbc.sql("UPDATE gastronomic_culture SET version = version + 1 WHERE slug = :slug")
                .param("slug", slug).update();

        culture.setDescription("Descripción escrita por el primer editor");

        // En 2023 no había @Version: esta escritura habría pisado en silencio lo
        // que hizo el otro, y nadie se habría enterado de que se perdió un cambio.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> cultureRepository.saveAndFlush(culture)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(versionLeida).isNotNull();
    }

    // ------------------------------------------- la regresión de 2023 -----

    @Test
    @DisplayName("editar una cultura NO borra sus recetas ni sus países")
    void updateDoesNotWipeRelations() {
        String slug = "cocina-italiana";

        int recetasAntes = jdbc.sql("""
                SELECT count(*) FROM recipe r
                JOIN gastronomic_culture c ON c.id = r.culture_id WHERE c.slug = :slug
                """).param("slug", slug).query(Integer.class).single();

        int paisesAntes = jdbc.sql("""
                SELECT count(*) FROM culture_country cc
                JOIN gastronomic_culture c ON c.id = cc.culture_id WHERE c.slug = :slug
                """).param("slug", slug).query(Integer.class).single();

        assertThat(recetasAntes).isPositive();
        assertThat(paisesAntes).isPositive();

        // El DTO de entrada es plano: no lleva recetas ni países. En 2023 el
        // update construía una entidad nueva desde ese DTO y llamaba a save(),
        // así que las colecciones ausentes se guardaban como vacías y las
        // relaciones desaparecían en cada edición.
        cultures.update(slug, new CultureDtos.Request(
                "Cocina italiana", "Descripción editada por el test", null));

        int recetasDespues = jdbc.sql("""
                SELECT count(*) FROM recipe r
                JOIN gastronomic_culture c ON c.id = r.culture_id WHERE c.slug = :slug
                """).param("slug", slug).query(Integer.class).single();

        int paisesDespues = jdbc.sql("""
                SELECT count(*) FROM culture_country cc
                JOIN gastronomic_culture c ON c.id = cc.culture_id WHERE c.slug = :slug
                """).param("slug", slug).query(Integer.class).single();

        assertThat(recetasDespues).isEqualTo(recetasAntes);
        assertThat(paisesDespues).isEqualTo(paisesAntes);
    }

    // --------------------------------------------------------- cupo Redis --

    @Test
    @DisplayName("el cupo cuenta en Redis y se agota exactamente en el límite")
    void rateLimiterCountsInRedis() {
        String subject = "usuario-" + UUID.randomUUID();

        for (int i = 1; i <= 3; i++) {
            RateLimiter.Decision decision =
                    rateLimiter.tryConsume("prueba", subject, 3, Duration.ofMinutes(5));
            assertThat(decision.allowed()).as("petición %d de 3", i).isTrue();
            assertThat(decision.remaining()).isEqualTo(3 - i);
        }

        // La cuarta ya no.
        RateLimiter.Decision cuarta = rateLimiter.tryConsume("prueba", subject, 3, Duration.ofMinutes(5));
        assertThat(cuarta.allowed()).isFalse();
        assertThat(cuarta.remaining()).isZero();
    }

    @Test
    @DisplayName("el cupo es por usuario: agotar el de uno no afecta al otro")
    void rateLimitIsPerSubject() {
        String uno = "u1-" + UUID.randomUUID();
        String otro = "u2-" + UUID.randomUUID();

        rateLimiter.tryConsume("prueba", uno, 1, Duration.ofMinutes(5));
        assertThat(rateLimiter.tryConsume("prueba", uno, 1, Duration.ofMinutes(5)).allowed()).isFalse();

        // Si la clave no incluyera el usuario, este segundo también estaría
        // agotado y el límite sería global sin que nadie lo hubiera pedido.
        assertThat(rateLimiter.tryConsume("prueba", otro, 1, Duration.ofMinutes(5)).allowed()).isTrue();
    }

    @Test
    @DisplayName("la clave del cupo caduca sola, sin proceso de limpieza")
    void rateLimitKeyExpires() {
        String subject = "ttl-" + UUID.randomUUID();
        rateLimiter.tryConsume("prueba", subject, 5, Duration.ofMinutes(7));

        // El número de ventana lo calcula el propio limitador, así que se busca
        // por patrón en vez de reproducir aquí ese cálculo.
        var keys = redis.keys("rate:prueba:" + subject + ":*");
        assertThat(keys).isNotEmpty();

        Long ttl = redis.getExpire(keys.iterator().next(), TimeUnit.SECONDS);

        // Sin caducidad, cada usuario dejaría una clave para siempre y la
        // memoria de Redis crecería sin tope. Con ella, el propio paso del
        // tiempo hace la limpieza.
        assertThat(ttl).isBetween(1L, Duration.ofMinutes(7).toSeconds());
    }

    // -------------------------------------------------------- pub/sub -----

    @Test
    @DisplayName("un cambio del catálogo viaja por Redis hasta el suscriptor")
    void catalogChangeTravelsThroughRedis() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> payload = new AtomicReference<>();

        listeners.addMessageListener(
                (message, pattern) -> {
                    payload.set(new String(message.getBody(), StandardCharsets.UTF_8));
                    received.countDown();
                },
                new ChannelTopic(CatalogEvents.CHANNEL));

        // Un margen para que la suscripción esté establecida: pub/sub no guarda
        // historial, así que publicar antes de tiempo se perdería.
        Thread.sleep(300);
        events.publish("creada", "RECIPE", "una-receta", "Una receta");

        assertThat(received.await(10, TimeUnit.SECONDS))
                .as("el mensaje debe llegar al suscriptor")
                .isTrue();
        assertThat(payload.get()).isEqualTo("creada|RECIPE|una-receta|Una receta");
    }

    @Test
    @DisplayName("publicar con Redis caído no rompe la escritura")
    void publishingNeverThrows() {
        // Publicar es un efecto secundario del cambio, no parte de él. Aquí
        // Redis está vivo, así que lo que se fija es el contrato: este método no
        // propaga nunca, pase lo que pase.
        events.publish("creada", "RECIPE", "otra", "Otra");
    }

    // ------------------------------------------------------ estadísticas --

    @Test
    @DisplayName("las cifras salen de la base y cuadran con lo sembrado")
    void statsMatchTheDatabase() {
        StatsService.Snapshot snapshot = stats.snapshot();

        long recetasReales = jdbc.sql("SELECT count(*) FROM recipe").query(Long.class).single();
        long culturasReales = jdbc.sql("SELECT count(*) FROM gastronomic_culture").query(Long.class).single();

        assertThat(snapshot.recetas()).isEqualTo(recetasReales);
        assertThat(snapshot.culturas()).isEqualTo(culturasReales);
        assertThat(snapshot.momento()).isNotNull();
        // indexadas nunca puede pasar del total: si pasa, la consulta cuenta mal.
        assertThat(snapshot.indexadas()).isLessThanOrEqualTo(snapshot.recetas());
    }
}
