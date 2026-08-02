package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.repository.SearchRepository;
import co.edu.uniandes.culturas.repository.VectorRepository;
import co.edu.uniandes.culturas.web.dto.SearchDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Búsqueda híbrida: dos carriles fusionados con Reciprocal Rank Fusion.
 *
 * <p>Léxico y difuso resuelven cosas distintas y ninguno basta solo. El léxico
 * acierta con «arroz con leche» porque comparte lexemas; el difuso encuentra
 * «carbonara» cuando alguien escribe «carbonarra», donde el léxico no devuelve
 * nada porque no hay raíz en común. Fusionarlos evita tener que elegir.
 *
 * <p>El tercer carril es el semántico, sobre pgvector. Encuentra lo que los
 * otros dos no pueden encontrar por construcción: los dos comparan cadenas —el
 * léxico exige compartir lexemas, el de trigramas exige compartir letras— así
 * que ninguno relaciona «algo picante con maíz» con un texto que habla de
 * chiles y tortillas, porque no tienen un carácter en común.
 *
 * <p>Entrar en la fusión no cambió nada de lo anterior: RRF opera sobre
 * posiciones, no sobre puntuaciones, así que añadir una lista es añadir un
 * argumento. Esto es justo lo que hace que no haya que normalizar ts_rank
 * (escala abierta) contra similitud coseno (0..1) contra similitud de trigramas
 * (0..1): tres magnitudes que no significan lo mismo y cuya media ponderada no
 * significaría nada.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    /**
     * Cuántos resultados pide cada carril antes de fusionar. Se piden más de
     * los que se devuelven porque un documento que sale décimo en un carril
     * puede subir mucho al sumarle su posición en el otro.
     */
    private static final int PER_LANE = 50;

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final SearchRepository repository;
    private final VectorRepository vectors;
    private final EmbeddingService embeddings;

    public SearchService(SearchRepository repository,
                         VectorRepository vectors,
                         EmbeddingService embeddings) {
        this.repository = repository;
        this.vectors = vectors;
        this.embeddings = embeddings;
    }

    public List<SearchDtos.Hit> search(String query, int limit) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() < 2) {
            // Con un solo carácter, los trigramas devuelven prácticamente todo
            // el catálogo y el resultado no significa nada.
            return List.of();
        }

        List<SearchRepository.SearchHit> lexical = repository.searchLexical(trimmed, PER_LANE);
        List<SearchRepository.SearchHit> fuzzy = repository.searchFuzzy(trimmed, PER_LANE);
        List<SearchRepository.SearchHit> semantic = semanticLane(trimmed);

        List<SearchRepository.SearchHit> fused = ReciprocalRankFusion.fuse(
                hit -> hit.getType() + ':' + hit.getSlug(),
                lexical,
                fuzzy,
                semantic);

        return fused.stream()
                .limit(limit)
                .map(SearchService::toHit)
                .toList();
    }

    /**
     * El carril semántico degrada, no rompe.
     *
     * <p>Los vectores los calcula un trabajo aparte, así que hay un intervalo
     * real —catálogo recién sembrado, receta recién creada— en el que no
     * existen todavía. En ese intervalo la respuesta correcta es una búsqueda
     * algo peor, no un 500: los otros dos carriles siguen respondiendo y el
     * usuario no percibe nada roto.
     */
    private List<SearchRepository.SearchHit> semanticLane(String query) {
        try {
            return vectors.searchSemantic(embeddings.embedAsVectorLiteral(query), PER_LANE);
        } catch (RuntimeException e) {
            log.warn("Carril semántico no disponible, se sigue con léxico y difuso: {}", e.toString());
            return List.of();
        }
    }

    /** Recetas parecidas a una dada, por vecindad de vectores. */
    public List<SearchDtos.Hit> similarTo(String slug, int limit) {
        return vectors.similarTo(slug, limit).stream().map(SearchService::toHit).toList();
    }

    static SearchDtos.Hit toHit(SearchRepository.SearchHit hit) {
        return new SearchDtos.Hit(
                hit.getSlug(),
                hit.getName(),
                SearchDtos.HitType.valueOf(hit.getType()));
    }
}
