package co.edu.uniandes.culturas.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estado de una ejecución del verificador de enlaces.
 *
 * <p>Vive en memoria: el trabajo dura segundos y su resultado útil se persiste
 * en {@code dish_multimedia}. Guardar además el progreso en la base sería
 * escribir mucho para un dato que caduca en cuanto termina.
 */
public final class LinkCheckJob {

    public enum Status {
        RUNNING,
        DONE,
        FAILED
    }

    public record Result(Long imageId, String url, int status, String recipeName) {
        public boolean broken() {
            return status < 200 || status >= 300;
        }
    }

    private final String id;
    private final int total;
    private final Instant startedAt = Instant.now();
    private final AtomicInteger checked = new AtomicInteger();
    private final AtomicInteger broken = new AtomicInteger();
    private final Queue<Result> results = new ConcurrentLinkedQueue<>();

    private volatile Status status = Status.RUNNING;
    private volatile String error;

    public LinkCheckJob(String id, int total) {
        this.id = id;
        this.total = total;
    }

    public void record(Result result) {
        results.add(result);
        checked.incrementAndGet();
        if (result.broken()) {
            broken.incrementAndGet();
        }
    }

    public void complete() {
        this.status = Status.DONE;
    }

    public void fail(String message) {
        this.error = message;
        this.status = Status.FAILED;
    }

    public String id() {
        return id;
    }

    public int total() {
        return total;
    }

    public int checked() {
        return checked.get();
    }

    public int broken() {
        return broken.get();
    }

    public Status status() {
        return status;
    }

    public String error() {
        return error;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public List<Result> results() {
        return List.copyOf(results);
    }
}
