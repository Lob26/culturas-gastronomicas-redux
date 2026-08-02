package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.repository.AppUserRepository;
import co.edu.uniandes.culturas.repository.RatingRepository;
import co.edu.uniandes.culturas.repository.SearchRepository;
import co.edu.uniandes.culturas.repository.VectorRepository;
import co.edu.uniandes.culturas.web.dto.SearchDtos;
import co.edu.uniandes.culturas.web.error.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * «Recomendado para ti».
 *
 * <p>KNN desde <em>cada</em> una de las recetas que el usuario puntuó alto, y
 * las listas resultantes fusionadas con RRF. La alternativa habitual —promediar
 * los vectores del usuario en un centroide y buscar sus vecinos— es más barata
 * y está mal: el centroide de a quien le gustan los curris tailandeses y la
 * repostería nórdica cae en el punto medio del espacio vectorial, que no se
 * parece a ninguna de las dos cosas. Recomendaría algo templado y genérico que
 * el usuario no ha pedido nunca.
 *
 * <p>Con KNN por semilla, cada gusto conserva su propia vecindad y RRF premia
 * lo que aparece cerca de varios: una receta que sale décima desde dos semillas
 * distintas termina por delante de una que sale tercera desde una sola. Y
 * reutiliza la fusión que ya existía para la búsqueda, sin código nuevo.
 */
@Service
@Transactional(readOnly = true)
public class RecommendationService {

    /**
     * Cuántas semillas se toman. Más semillas es más diversidad y más consultas;
     * pasado un punto lo que se añade son gustos marginales que sólo ensucian
     * el resultado.
     */
    private static final int SEEDS = 5;

    /** Vecinos por semilla, antes de fusionar. */
    private static final int PER_SEED = 20;

    private final RatingRepository ratings;
    private final AppUserRepository users;
    private final VectorRepository vectors;

    public RecommendationService(RatingRepository ratings,
                                 AppUserRepository users,
                                 VectorRepository vectors) {
        this.ratings = ratings;
        this.users = users;
        this.vectors = vectors;
    }

    public Recommendations forUser(String username, int limit) {
        Long userId = users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", username))
                .getId();

        List<Long> seeds = ratings.topRatedRecipeIds(username, PageRequest.of(0, SEEDS));
        if (seeds.isEmpty()) {
            return popular(userId, limit);
        }

        List<List<SearchRepository.SearchHit>> lanes = new ArrayList<>(seeds.size());
        for (Long seed : seeds) {
            lanes.add(vectors.neighboursOf(seed, userId, PER_SEED));
        }

        List<SearchRepository.SearchHit> fused = ReciprocalRankFusion.fuse(
                hit -> hit.getType() + ':' + hit.getSlug(),
                lanes);

        // Puede quedar vacío aunque haya semillas: si el usuario ya valoró todo
        // lo que tiene vector, no quedan vecinos que no conozca. La reserva
        // popular tampoco devolverá nada entonces, y eso es correcto —no hay
        // nada que recomendar—, pero la decisión se toma con datos, no antes.
        if (fused.isEmpty()) {
            return popular(userId, limit);
        }

        List<SearchDtos.Hit> hits = fused.stream().limit(limit).map(SearchService::toHit).toList();
        return new Recommendations(hits, Basis.PERSONAL, seeds.size());
    }

    private Recommendations popular(Long userId, int limit) {
        List<SearchDtos.Hit> hits = vectors.mostPopular(userId, limit).stream()
                .map(SearchService::toHit)
                .toList();
        return new Recommendations(hits, Basis.POPULAR, 0);
    }

    /**
     * En qué se basó la recomendación.
     *
     * <p>Va en la respuesta a propósito. Sin esto, el cliente no puede
     * distinguir «esto se parece a lo que te gustó» de «esto le gusta a todo el
     * mundo porque aún no sabemos nada de ti», y son dos mensajes distintos
     * para el usuario.
     */
    public enum Basis {
        PERSONAL, POPULAR
    }

    public record Recommendations(List<SearchDtos.Hit> results, Basis basis, int seeds) {
    }
}
