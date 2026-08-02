package co.edu.uniandes.culturas.jobs;

import co.edu.uniandes.culturas.repository.DishMultimediaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Guarda el diagnóstico del verificador de enlaces.
 *
 * <p>Es una clase aparte y no un método de {@link LinkCheckService} por un
 * motivo concreto: {@code @Transactional} se implementa con un proxy, y una
 * llamada desde otro método de la <strong>misma</strong> instancia no pasa por
 * él. El método se ejecutaría sin transacción, sin error y sin aviso — de los
 * fallos más silenciosos que tiene Spring.
 */
@Service
public class LinkCheckPersister {

    private final DishMultimediaRepository repository;

    public LinkCheckPersister(DishMultimediaRepository repository) {
        this.repository = repository;
    }

    /**
     * Escribe todos los resultados en una sola transacción.
     *
     * <p>Hacerlo desde cada tarea abriría una transacción por imagen y
     * multiplicaría las conexiones tomadas del pool. El fan-out es de I/O de
     * red; la escritura se centraliza.
     */
    @Transactional
    public void persist(List<LinkCheckJob.Result> results) {
        if (results.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Map<Long, LinkCheckJob.Result> byId = results.stream()
                .collect(Collectors.toMap(LinkCheckJob.Result::imageId, result -> result, (a, b) -> a));

        repository.findAllById(byId.keySet()).forEach(image -> {
            LinkCheckJob.Result result = byId.get(image.getId());
            image.setLastCheckedAt(now);
            image.setLastStatus((short) result.status());
        });
    }
}
