package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.repository.SearchRepository;
import co.edu.uniandes.culturas.web.dto.SearchDtos;
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
 * <p>En la Fase 4 se añade un tercer carril, el semántico sobre pgvector, que
 * entra en la misma fusión sin cambiar nada de esto: RRF trabaja con
 * posiciones, así que sumar una lista más es sumar un argumento.
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

    private final SearchRepository repository;

    public SearchService(SearchRepository repository) {
        this.repository = repository;
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

        List<SearchRepository.SearchHit> fused = ReciprocalRankFusion.fuse(
                hit -> hit.getType() + ':' + hit.getSlug(),
                lexical,
                fuzzy);

        return fused.stream()
                .limit(limit)
                .map(hit -> new SearchDtos.Hit(
                        hit.getSlug(),
                        hit.getName(),
                        SearchDtos.HitType.valueOf(hit.getType())))
                .toList();
    }
}
