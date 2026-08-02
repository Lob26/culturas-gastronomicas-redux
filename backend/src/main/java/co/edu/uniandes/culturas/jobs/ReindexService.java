package co.edu.uniandes.culturas.jobs;

import co.edu.uniandes.culturas.repository.VectorRepository;
import co.edu.uniandes.culturas.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Calcula los vectores que faltan.
 *
 * <p><strong>Secuencial, y a propósito.</strong> El resto del proyecto reparte
 * el trabajo sobre hilos virtuales, pero esto no: la inferencia ONNX es CPU
 * pura y los hilos virtuales no crean paralelismo de CPU —se multiplexan sobre
 * el mismo pool portador—, así que lo único que aportarían aquí es sobrecarga
 * de planificación. ONNX Runtime ya paraleliza internamente cada inferencia
 * sobre los núcleos disponibles; lanzar N inferencias a la vez por encima de
 * eso las hace competir entre sí y sale más lento, no más rápido.
 *
 * <p>Es idempotente: sólo toca filas cuyo vector falta o quedó viejo respecto a
 * {@code updated_at}. Correrlo dos veces seguidas no hace nada la segunda vez,
 * que es lo que hace que se pueda programar cada noche sin pensarlo.
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final VectorRepository repository;
    private final EmbeddingService embeddings;

    /**
     * Un reindexado a la vez. Dos ejecuciones simultáneas no corromperían nada
     * —cada UPDATE es atómico y el resultado es el mismo vector— pero
     * duplicarían el gasto de CPU, y esa CPU es la misma que atiende las
     * consultas.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ReindexService(VectorRepository repository, EmbeddingService embeddings) {
        this.repository = repository;
        this.embeddings = embeddings;
    }

    public Result reindexPending() {
        if (!running.compareAndSet(false, true)) {
            return Result.alreadyRunning();
        }
        try {
            long start = System.nanoTime();

            int recipes = index(repository.recipesPendingEmbedding(), repository::storeRecipeEmbedding, "receta");
            int cultures = index(repository.culturesPendingEmbedding(), repository::storeCultureEmbedding, "cultura");

            long millis = (System.nanoTime() - start) / 1_000_000;
            log.info("Reindexado completo: {} recetas, {} culturas, {} ms", recipes, cultures, millis);
            return new Result(recipes, cultures, millis, false);
        } finally {
            running.set(false);
        }
    }

    /**
     * Cada fila se persiste en su propia transacción, la del {@code @Modifying}
     * del repositorio. Un lote único sería más rápido, pero si la fila 40 de 50
     * falla se pierde el trabajo de las 39 anteriores y el siguiente intento lo
     * repite entero. Con transacción por fila, un fallo cuesta una fila.
     */
    private int index(List<VectorRepository.Indexable> pending,
                      Persist persist,
                      String what) {
        int done = 0;
        for (VectorRepository.Indexable row : pending) {
            try {
                String vector = embeddings.embedDocument(row.getName(), row.getDescription(), row.getExtra());
                persist.store(row.getId(), vector);
                done++;
            } catch (RuntimeException e) {
                // Se registra y se sigue: una fila con texto raro no debe dejar
                // sin indexar al resto del catálogo. Al quedar su embedding
                // NULL, la próxima pasada vuelve a intentarlo sola.
                log.warn("No se pudo indexar la {} id={}: {}", what, row.getId(), e.toString());
            }
        }
        return done;
    }

    @FunctionalInterface
    private interface Persist {
        void store(Long id, String vector);
    }

    /** Resumen de una ejecución. */
    public record Result(int recipes, int cultures, long millis, boolean skipped) {
        static Result alreadyRunning() {
            return new Result(0, 0, 0, true);
        }
    }
}
