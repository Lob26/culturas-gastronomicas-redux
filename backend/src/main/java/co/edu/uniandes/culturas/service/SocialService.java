package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.domain.AppUser;
import co.edu.uniandes.culturas.domain.Favorite;
import co.edu.uniandes.culturas.domain.Rating;
import co.edu.uniandes.culturas.domain.Recipe;
import co.edu.uniandes.culturas.repository.AppUserRepository;
import co.edu.uniandes.culturas.repository.FavoriteRepository;
import co.edu.uniandes.culturas.repository.RatingRepository;
import co.edu.uniandes.culturas.repository.RecipeRepository;
import co.edu.uniandes.culturas.web.dto.SocialDtos;
import co.edu.uniandes.culturas.web.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Valoraciones y recetario personal.
 *
 * <p>Estas son las funciones que hacen que la identidad sirva para algo: sin
 * ellas, registrarse sólo permitiría escribir en un catálogo compartido.
 */
@Service
@Transactional(readOnly = true)
public class SocialService {

    private final RatingRepository ratings;
    private final FavoriteRepository favorites;
    private final RecipeRepository recipes;
    private final AppUserRepository users;

    public SocialService(RatingRepository ratings,
                         FavoriteRepository favorites,
                         RecipeRepository recipes,
                         AppUserRepository users) {
        this.ratings = ratings;
        this.favorites = favorites;
        this.recipes = recipes;
        this.users = users;
    }

    /**
     * Crea o actualiza la valoración de quien llama.
     *
     * <p>Se comprueba primero si ya existe, en lugar de insertar siempre: la
     * restricción UNIQUE convertiría una segunda valoración en un 409, cuando
     * lo natural es que cambiar de opinión funcione. La restricción sigue ahí
     * como red de seguridad para el caso concurrente.
     */
    @Transactional
    public SocialDtos.RatingView rate(String recipeSlug, String username, SocialDtos.RatingRequest request) {
        Recipe recipe = recipes.findBySlug(recipeSlug)
                .orElseThrow(() -> new ResourceNotFoundException("receta", recipeSlug));
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("usuario", username));

        Rating rating = ratings.findMine(recipeSlug, username).orElseGet(() -> {
            Rating fresh = new Rating();
            fresh.setRecipe(recipe);
            fresh.setUser(user);
            return fresh;
        });

        rating.setScore(request.score());
        rating.setComment(request.comment());

        return toView(ratings.save(rating));
    }

    @Transactional
    public void deleteRating(String recipeSlug, String username) {
        Rating rating = ratings.findMine(recipeSlug, username)
                .orElseThrow(() -> new ResourceNotFoundException("valoración", recipeSlug));
        ratings.delete(rating);
    }

    public List<SocialDtos.RatingView> listRatings(String recipeSlug, org.springframework.data.domain.Pageable pageable) {
        return ratings.findByRecipeSlug(recipeSlug, pageable).map(this::toView).getContent();
    }

    /**
     * Alterna el favorito y devuelve el estado resultante.
     *
     * <p>Una sola operación en vez de POST y DELETE separados: la interfaz es
     * un botón que conmuta, y con dos endpoints el cliente tendría que saber el
     * estado actual antes de decidir a cuál llamar — con la carrera que eso
     * implica si hay dos pestañas abiertas.
     */
    @Transactional
    public boolean toggleFavorite(String username, Favorite.TargetType type, Long targetId) {
        int removed = favorites.removeMine(username, type, targetId);
        if (removed > 0) {
            return false;
        }

        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("usuario", username));

        Favorite favorite = new Favorite();
        favorite.setUser(user);
        favorite.setTargetType(type);
        favorite.setTargetId(targetId);
        favorites.save(favorite);
        return true;
    }

    public List<SocialDtos.FavoriteView> listFavorites(String username) {
        return favorites.findMine(username).stream()
                .map(favorite -> new SocialDtos.FavoriteView(
                        favorite.getTargetType(),
                        favorite.getTargetId(),
                        favorite.getCreatedAt()))
                .toList();
    }

    private SocialDtos.RatingView toView(Rating rating) {
        return new SocialDtos.RatingView(
                rating.getScore(),
                rating.getComment(),
                rating.getUser().getDisplayName(),
                rating.getCreatedAt());
    }
}
