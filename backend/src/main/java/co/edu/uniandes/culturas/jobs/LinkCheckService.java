package co.edu.uniandes.culturas.jobs;

import co.edu.uniandes.culturas.config.CulturasProperties;
import co.edu.uniandes.culturas.domain.DishMultimedia;
import co.edu.uniandes.culturas.repository.DishMultimediaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Verifica que las imágenes del catálogo siguen respondiendo.
 *
 * <p>Hace falta de verdad: los datos heredados de 2023 enlazaban en caliente a
 * sitios de terceros y varias de esas URLs ya no responden. Una de ellas está
 * además malformada —{@code https:https://...}— y se conserva a propósito como
 * fixture.
 *
 * <p>Es el sitio donde los hilos virtuales aportan algo real. El trabajo es
 * I/O: decenas de peticiones que pasan casi todo su tiempo esperando respuesta.
 * Con hilos de plataforma haría falta un pool dimensionado a mano; con hilos
 * virtuales cada petición tiene el suyo y el bloqueo no consume un hilo del
 * sistema operativo.
 *
 * <p>Lo que los hilos virtuales <strong>no</strong> hacen es limitar la
 * concurrencia: crear diez mil es barato, y sin un tope saldrían diez mil
 * peticiones a la vez y el cuello de botella pasaría a ser la red del equipo.
 * De ahí el semáforo.
 */
@Service
public class LinkCheckService {

    private static final Logger log = LoggerFactory.getLogger(LinkCheckService.class);

    private final DishMultimediaRepository repository;
    private final LinkCheckBroadcaster broadcaster;
    private final LinkCheckPersister persister;
    private final CulturasProperties properties;
    private final HttpClient httpClient;

    private final Map<String, LinkCheckJob> jobs = new ConcurrentHashMap<>();

    public LinkCheckService(DishMultimediaRepository repository,
                            LinkCheckBroadcaster broadcaster,
                            LinkCheckPersister persister,
                            CulturasProperties properties) {
        this.repository = repository;
        this.broadcaster = broadcaster;
        this.persister = persister;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.jobs().linkCheck().requestTimeout())
                // Muchas imágenes viven detrás de una redirección; sin seguirla
                // se contarían como rotas por un 301 perfectamente válido.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public LinkCheckJob find(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Lanza una comprobación y devuelve el trabajo inmediatamente.
     *
     * <p>La petición HTTP no espera al resultado: el cliente se suscribe al
     * flujo SSE con el id devuelto y va viendo el progreso.
     */
    @Transactional(readOnly = true)
    public LinkCheckJob start() {
        // Con la receta ya resuelta: los hilos virtuales corren fuera de esta
        // transacción, y allí una carga perezosa fallaría por sesión cerrada.
        List<DishMultimedia> images = repository.findAllWithRecipe();
        LinkCheckJob job = new LinkCheckJob(UUID.randomUUID().toString(), images.size());
        jobs.put(job.id(), job);

        Thread.ofVirtual()
                .name("link-check-" + job.id())
                .start(() -> run(job, images));

        return job;
    }

    private void run(LinkCheckJob job, List<DishMultimedia> images) {
        Semaphore permits = new Semaphore(properties.jobs().linkCheck().maxConcurrency());

        // try-with-resources sobre el ExecutorService: close() espera a que
        // terminen todas las tareas, así que al salir del bloque el trabajo
        // está realmente completo.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (DishMultimedia image : images) {
                executor.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        LinkCheckJob.Result result = check(image);
                        job.record(result);
                        broadcaster.publish(job.id(), result, job.checked(), job.total());
                    } finally {
                        permits.release();
                    }
                });
            }
        } catch (RuntimeException e) {
            job.fail(e.getMessage());
            broadcaster.finish(job.id(), job);
            log.error("El verificador de enlaces {} terminó con error", job.id(), e);
            return;
        }

        persister.persist(job.results());
        job.complete();
        broadcaster.finish(job.id(), job);
        log.info("Verificación {} terminada: {} de {} rotas", job.id(), job.broken(), job.total());
    }

    private LinkCheckJob.Result check(DishMultimedia image) {
        String recipeName = image.getRecipe().getName();
        try {
            HttpRequest request = HttpRequest.newBuilder(new URI(image.getUrl()))
                    // HEAD y no GET: sólo interesa el código de estado, y
                    // descargar la imagen entera multiplicaría el tráfico sin
                    // aportar nada.
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(properties.jobs().linkCheck().requestTimeout())
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return new LinkCheckJob.Result(image.getId(), image.getUrl(), response.statusCode(), recipeName);

        } catch (URISyntaxException | IllegalArgumentException e) {
            // Es el caso de la URL malformada de 2023: no llega a salir a la
            // red. Se marca con 0 para distinguirla de un fallo del servidor.
            return new LinkCheckJob.Result(image.getId(), image.getUrl(), 0, recipeName);
        } catch (IOException e) {
            // DNS que no resuelve, conexión rechazada, TLS inválido.
            return new LinkCheckJob.Result(image.getId(), image.getUrl(), 0, recipeName);
        } catch (InterruptedException e) {
            // Restaurar el flag es obligatorio: tragarse la interrupción deja
            // al hilo sin saber que le pidieron parar.
            Thread.currentThread().interrupt();
            return new LinkCheckJob.Result(image.getId(), image.getUrl(), 0, recipeName);
        }
    }

}
