package co.edu.uniandes.culturas.repository;

import co.edu.uniandes.culturas.domain.Recipe;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Acceso a las columnas {@code vector}.
 *
 * <p>Todo nativo: pgvector aporta un tipo y unos operadores —{@code <=>}— que
 * JPQL no conoce. El vector viaja como texto y se convierte con un CAST
 * explícito; sin el CAST, Postgres ve un parámetro de tipo desconocido y falla
 * al resolver el operador.
 */
public interface VectorRepository extends JpaRepository<Recipe, Long> {

    // ------------------------------------------------------------- indexado --

    /**
     * Recetas cuyo vector falta o quedó viejo.
     *
     * <p>{@code COALESCE(updated_at, created_at)} porque updated_at es nulo
     * hasta la primera modificación: comparar contra NULL daría falso y una
     * receta recién creada nunca se reindexaría.
     */
    /**
     * <p>El texto indexado es <strong>nombre, descripción y cultura</strong>.
     * No los pasos, y esto se midió antes de decidirlo: los pasos son el 90 %
     * del texto de una receta (unos 700 caracteres frente a 75 de nombre más
     * descripción), y son justo la parte que todas las recetas comparten
     * —cortar, calentar, mezclar, reservar—. Al mediar el modelo sobre todos
     * los tokens, esa masa de lenguaje procedimental genérico ahoga lo que
     * distingue a un plato de otro: con los pasos dentro, Tacos al Pastor y
     * Pasta Carbonara quedaban a distancia coseno 0,296, más cerca entre sí que
     * Sushi de cualquier otra cosa.
     *
     * <p>No se pierde nada: el carril léxico ya indexa los pasos en su propia
     * rama sobre {@code recipe_step.search_vector}. Cada carril cubre lo que
     * sabe cubrir, que es el motivo de que haya tres.
     *
     * <p>La cultura sí entra, y aporta: es la señal que permite que «plato
     * japonés crudo» encuentre el sushi aunque su descripción no diga «japonés»
     * por ninguna parte.
     */
    @Query(value = """
            SELECT r.id          AS id,
                   r.name        AS name,
                   r.description AS description,
                   c.name        AS extra
            FROM recipe r JOIN gastronomic_culture c ON c.id = r.culture_id
            WHERE r.embedding IS NULL
               OR r.embedded_at < COALESCE(r.updated_at, r.created_at)
            """, nativeQuery = true)
    List<Indexable> recipesPendingEmbedding();

    @Query(value = """
            SELECT c.id          AS id,
                   c.name        AS name,
                   c.description AS description,
                   NULL          AS extra
            FROM gastronomic_culture c
            WHERE c.embedding IS NULL
               OR c.embedded_at < COALESCE(c.updated_at, c.created_at)
            """, nativeQuery = true)
    List<Indexable> culturesPendingEmbedding();

    /**
     * Escribe el vector sin tocar {@code version}.
     *
     * <p>Deliberadamente un UPDATE nativo y no una entidad gestionada: el
     * embedding es metadato derivado, no un cambio del documento. Pasarlo por
     * JPA incrementaría @Version y haría que un reindexado nocturno reventara
     * con OptimisticLockException cualquier edición que un usuario tuviera
     * abierta, por una columna que ese usuario no editó.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE recipe
            SET embedding = CAST(:vector AS vector), embedded_at = now()
            WHERE id = :id
            """, nativeQuery = true)
    int storeRecipeEmbedding(@Param("id") Long id, @Param("vector") String vector);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE gastronomic_culture
            SET embedding = CAST(:vector AS vector), embedded_at = now()
            WHERE id = :id
            """, nativeQuery = true)
    int storeCultureEmbedding(@Param("id") Long id, @Param("vector") String vector);

    // -------------------------------------------------------------- consulta --

    /**
     * Carril semántico de la búsqueda.
     *
     * <p>Cada rama lleva su propio ORDER BY y su propio LIMIT, entre paréntesis.
     * No es estilo: es lo que permite que cada índice HNSW sirva su rama. Un
     * único ORDER BY sobre el UNION obligaría a materializar las dos tablas
     * enteras y ordenarlas en memoria, y los índices no se tocarían.
     *
     * <p>Se ordena por la distancia cruda ({@code <=>}) y el parecido
     * ({@code 1 - distancia}) se calcula fuera: el índice sólo se usa cuando la
     * expresión ordenada es literalmente la del operador.
     */
    @Query(value = """
            SELECT u.slug AS slug, u.name AS name, u.type AS type, 1 - u.dist AS score
            FROM (
                (SELECT r.slug AS slug, r.name AS name, 'RECIPE' AS type,
                        r.embedding <=> CAST(:vector AS vector) AS dist
                 FROM recipe r
                 WHERE r.embedding IS NOT NULL
                 ORDER BY r.embedding <=> CAST(:vector AS vector)
                 LIMIT :limit)
                UNION ALL
                (SELECT c.slug, c.name, 'CULTURE',
                        c.embedding <=> CAST(:vector AS vector)
                 FROM gastronomic_culture c
                 WHERE c.embedding IS NOT NULL
                 ORDER BY c.embedding <=> CAST(:vector AS vector)
                 LIMIT :limit)
            ) u
            ORDER BY u.dist
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchRepository.SearchHit> searchSemantic(@Param("vector") String vector,
                                                    @Param("limit") int limit);

    /**
     * Vecinos de una receta: «recetas parecidas».
     *
     * <p>El vector semilla se lee en un subselect en vez de traerlo a Java y
     * mandarlo de vuelta: son 384 flotantes que ya están en la fila de al lado.
     */
    @Query(value = """
            SELECT r.slug AS slug, r.name AS name, 'RECIPE' AS type,
                   1 - (r.embedding <=> seed.embedding) AS score
            FROM recipe r, (SELECT embedding FROM recipe WHERE slug = :slug) seed
            WHERE r.embedding IS NOT NULL
              AND seed.embedding IS NOT NULL
              AND r.slug <> :slug
            ORDER BY r.embedding <=> seed.embedding
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchRepository.SearchHit> similarTo(@Param("slug") String slug, @Param("limit") int limit);

    /**
     * Vecinos de una receta que el usuario valoró bien, excluyendo lo que ya
     * valoró.
     *
     * <p>Recomendar algo que ya puntuaste no es una recomendación. La exclusión
     * va en SQL y no filtrando después en Java porque, filtrando después, pedir
     * 10 puede devolver 2.
     */
    @Query(value = """
            SELECT r.slug AS slug, r.name AS name, 'RECIPE' AS type,
                   1 - (r.embedding <=> seed.embedding) AS score
            FROM recipe r, (SELECT embedding FROM recipe WHERE id = :seedId) seed
            WHERE r.embedding IS NOT NULL
              AND seed.embedding IS NOT NULL
              AND r.id <> :seedId
              AND NOT EXISTS (SELECT 1 FROM rating rt
                              WHERE rt.recipe_id = r.id AND rt.user_id = :userId)
            ORDER BY r.embedding <=> seed.embedding
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchRepository.SearchHit> neighboursOf(@Param("seedId") Long seedId,
                                                  @Param("userId") Long userId,
                                                  @Param("limit") int limit);

    /**
     * Reserva para arranque en frío: lo mejor valorado que el usuario no conoce.
     *
     * <p>Un usuario sin valoraciones no tiene semillas, y devolverle una lista
     * vacía es peor que devolverle lo que le gusta a todo el mundo.
     */
    @Query(value = """
            SELECT r.slug AS slug, r.name AS name, 'RECIPE' AS type,
                   r.rating_average AS score
            FROM recipe r
            WHERE NOT EXISTS (SELECT 1 FROM rating rt
                              WHERE rt.recipe_id = r.id AND rt.user_id = :userId)
            ORDER BY r.rating_count DESC, r.rating_average DESC, r.id
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchRepository.SearchHit> mostPopular(@Param("userId") Long userId,
                                                 @Param("limit") int limit);

    /** Proyección para el trabajo de indexado. */
    interface Indexable {
        Long getId();

        String getName();

        String getDescription();

        /** Texto adicional del documento: los pasos, en el caso de una receta. */
        String getExtra();
    }
}
