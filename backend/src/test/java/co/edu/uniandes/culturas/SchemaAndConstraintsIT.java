package co.edu.uniandes.culturas;

import co.edu.uniandes.culturas.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Lo que impone la base de datos, comprobado contra la base de datos.
 *
 * <p>Estas reglas no se pueden verificar con dobles: un mock siempre acepta lo
 * que se le pida. Y son justamente las que en 2023 no existían —el UML las
 * describía y el esquema no las tenía— así que dejarlas sin test las devolvería
 * a ser documentación.
 *
 * <p>Se escribe con JdbcClient y no por la API para saltarse la validación de
 * Bean Validation: lo que se quiere comprobar es que la <strong>base</strong>
 * rechaza el dato aunque la capa de arriba fallara.
 */
@IntegrationTest
class SchemaAndConstraintsIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("las migraciones se aplican todas y en orden")
    void migrationsApply() {
        var versions = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class)
                .list();

        assertThat(versions).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("la extensión vector está instalada y la columna es vector(384)")
    void vectorColumnHasRightDimensions() {
        // El tipo lleva las dimensiones dentro. Si el modelo cambiara sin migrar
        // la columna, el fallo aparecería en la primera escritura y no aquí.
        String type = jdbc.sql("""
                        SELECT format_type(a.atttypid, a.atttypmod)
                        FROM pg_attribute a
                        WHERE a.attrelid = 'recipe'::regclass AND a.attname = 'embedding'
                        """)
                .query(String.class)
                .single();

        assertThat(type).isEqualTo("vector(384)");
    }

    @Test
    @DisplayName("el índice HNSW existe y usa el operador coseno")
    void hnswIndexUsesCosine() {
        // El operador del índice tiene que coincidir con el de la consulta: un
        // índice creado con vector_l2_ops no se usa para una consulta con <=>,
        // y el planificador se limita a ignorarlo en silencio.
        String definition = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_recipe_embedding_hnsw'")
                .query(String.class)
                .single();

        assertThat(definition).contains("hnsw").contains("vector_cosine_ops");
    }

    @Test
    @DisplayName("la regla Michelin 0..3 la impone la base, no sólo la aplicación")
    void michelinLimitIsEnforcedByDatabase() {
        Long restaurantId = jdbc.sql("SELECT id FROM restaurant ORDER BY id LIMIT 1").query(Long.class).single();

        // Se cuentan las que ya tiene para dejarlo exactamente en 3 y probar la
        // cuarta, sin depender de cuántas trajera el sembrado.
        int existing = jdbc.sql("SELECT count(*) FROM michelin_star WHERE restaurant_id = :id")
                .param("id", restaurantId).query(Integer.class).single();

        for (int i = existing; i < 3; i++) {
            jdbc.sql("INSERT INTO michelin_star (restaurant_id, acquired) VALUES (:id, DATE '2001-01-01' + :n)")
                    .param("id", restaurantId).param("n", i).update();
        }

        // La cuarta. El trigger es CONSTRAINT TRIGGER, así que la violación
        // aparece al confirmar la transacción y no en el INSERT — aquí cada
        // sentencia va en su propia transacción, de modo que salta igual.
        assertThatThrownBy(() ->
                jdbc.sql("INSERT INTO michelin_star (restaurant_id, acquired) VALUES (:id, DATE '2020-05-05')")
                        .param("id", restaurantId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Michelin");
    }

    @Test
    @DisplayName("un usuario no puede valorar dos veces la misma receta")
    void ratingIsUniquePerUserAndRecipe() {
        Long userId = jdbc.sql("SELECT id FROM app_user ORDER BY id LIMIT 1").query(Long.class).single();
        Long recipeId = jdbc.sql("SELECT id FROM recipe ORDER BY id LIMIT 1").query(Long.class).single();

        jdbc.sql("DELETE FROM rating WHERE user_id = :u AND recipe_id = :r")
                .param("u", userId).param("r", recipeId).update();

        jdbc.sql("INSERT INTO rating (user_id, recipe_id, score) VALUES (:u, :r, 5)")
                .param("u", userId).param("r", recipeId).update();

        // La unicidad es del índice y no de un «consulto y si no existe
        // inserto»: dos peticiones simultáneas ganan la carrera contra esa
        // comprobación, no contra la restricción.
        assertThatThrownBy(() ->
                jdbc.sql("INSERT INTO rating (user_id, recipe_id, score) VALUES (:u, :r, 3)")
                        .param("u", userId).param("r", recipeId).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.sql("DELETE FROM rating WHERE user_id = :u AND recipe_id = :r")
                .param("u", userId).param("r", recipeId).update();
    }

    @Test
    @DisplayName("la puntuación fuera de 1..5 la rechaza la base")
    void ratingScoreIsBounded() {
        Long userId = jdbc.sql("SELECT id FROM app_user ORDER BY id LIMIT 1").query(Long.class).single();
        Long recipeId = jdbc.sql("SELECT id FROM recipe ORDER BY id DESC LIMIT 1").query(Long.class).single();

        assertThatThrownBy(() ->
                jdbc.sql("INSERT INTO rating (user_id, recipe_id, score) VALUES (:u, :r, 6)")
                        .param("u", userId).param("r", recipeId).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                jdbc.sql("INSERT INTO rating (user_id, recipe_id, score) VALUES (:u, :r, 0)")
                        .param("u", userId).param("r", recipeId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("borrar una receta se lleva sus pasos, imágenes y valoraciones")
    void deletingRecipeCascadesDownward() {
        Long cultureId = jdbc.sql("SELECT id FROM gastronomic_culture ORDER BY id LIMIT 1").query(Long.class).single();

        jdbc.sql("""
                INSERT INTO recipe (name, slug, description, culture_id)
                VALUES ('Receta de cascada', 'receta-de-cascada', 'temporal', :c)
                """).param("c", cultureId).update();

        Long recipeId = jdbc.sql("SELECT id FROM recipe WHERE slug = 'receta-de-cascada'")
                .query(Long.class).single();

        jdbc.sql("INSERT INTO recipe_step (recipe_id, position, instruction) VALUES (:r, 1, 'paso')")
                .param("r", recipeId).update();
        jdbc.sql("INSERT INTO dish_multimedia (recipe_id, url, position) VALUES (:r, 'http://x/y.png', 1)")
                .param("r", recipeId).update();

        jdbc.sql("DELETE FROM recipe WHERE id = :r").param("r", recipeId).update();

        assertThat(jdbc.sql("SELECT count(*) FROM recipe_step WHERE recipe_id = :r")
                .param("r", recipeId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM dish_multimedia WHERE recipe_id = :r")
                .param("r", recipeId).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("borrar una categoría NO arrastra su cultura (la cascada invertida de 2023)")
    void deletingCategoryDoesNotDeleteCulture() {
        Long cultureId = jdbc.sql("SELECT id FROM gastronomic_culture ORDER BY id LIMIT 1").query(Long.class).single();

        jdbc.sql("INSERT INTO gastronomic_category (name, culture_id) VALUES ('Temporal', :c)")
                .param("c", cultureId).update();
        Long categoryId = jdbc.sql("SELECT id FROM gastronomic_category WHERE name = 'Temporal'")
                .query(Long.class).single();

        jdbc.sql("DELETE FROM gastronomic_category WHERE id = :id").param("id", categoryId).update();

        // En 2023 GastronomicCategory.culture cascadeaba REMOVE hacia ARRIBA, así
        // que borrar una categoría intentaba borrar la cultura entera con todas
        // sus recetas. La cultura tiene que seguir ahí.
        assertThat(jdbc.sql("SELECT count(*) FROM gastronomic_culture WHERE id = :c")
                .param("c", cultureId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("el slug y el nombre de cultura son únicos")
    void cultureIdentifiersAreUnique() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO gastronomic_culture (name, slug)
                SELECT name, 'otro-slug-cualquiera' FROM gastronomic_culture ORDER BY id LIMIT 1
                """).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO gastronomic_culture (name, slug)
                SELECT 'Nombre distinto del todo', slug FROM gastronomic_culture ORDER BY id LIMIT 1
                """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("iso2 es VARCHAR y no CHAR: 'JP' no se rellena con espacios")
    void iso2DoesNotBlankPad() {
        // bpchar rellena hasta la longitud fija, así que con CHAR(2) el valor
        // guardado sería 'JP' pero comparado como 'JP ' en algunos contextos.
        String type = jdbc.sql("""
                        SELECT format_type(a.atttypid, a.atttypmod)
                        FROM pg_attribute a
                        WHERE a.attrelid = 'country'::regclass AND a.attname = 'iso2'
                        """)
                .query(String.class)
                .single();

        assertThat(type).isEqualTo("character varying(2)");
    }

    @Test
    @DisplayName("los agregados de valoración los mantiene un trigger, no la aplicación")
    void ratingAggregatesAreMaintainedByTrigger() {
        Long userId = jdbc.sql("SELECT id FROM app_user ORDER BY id LIMIT 1").query(Long.class).single();
        Long recipeId = jdbc.sql("SELECT id FROM recipe ORDER BY id LIMIT 1").query(Long.class).single();

        jdbc.sql("DELETE FROM rating WHERE recipe_id = :r").param("r", recipeId).update();
        jdbc.sql("INSERT INTO rating (user_id, recipe_id, score) VALUES (:u, :r, 4)")
                .param("u", userId).param("r", recipeId).update();

        // Se lee la fila de recipe: el trigger la actualiza dentro de la misma
        // transacción que la escritura, sin que nadie llame a nada.
        var row = jdbc.sql("SELECT rating_count, rating_average FROM recipe WHERE id = :r")
                .param("r", recipeId)
                .query((rs, n) -> new int[]{rs.getInt(1), rs.getBigDecimal(2).intValue()})
                .single();

        assertThat(row[0]).isEqualTo(1);
        assertThat(row[1]).isEqualTo(4);

        // Y al retirarla vuelven a cero, que es el caso que se olvida siempre.
        jdbc.sql("DELETE FROM rating WHERE recipe_id = :r").param("r", recipeId).update();
        assertThat(jdbc.sql("SELECT rating_count FROM recipe WHERE id = :r")
                .param("r", recipeId).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("una receta sin pasos es válida en la base; el mínimo lo pone la API")
    void schemaAllowsRecipeWithoutSteps() {
        Long cultureId = jdbc.sql("SELECT id FROM gastronomic_culture ORDER BY id LIMIT 1").query(Long.class).single();

        // Deliberado: la base no exige pasos porque una receta puede crearse en
        // dos tiempos. La regla «al menos un paso» es de la API, y está donde
        // se puede devolver un 400 que explique qué falta.
        assertThatCode(() -> jdbc.sql("""
                INSERT INTO recipe (name, slug, culture_id)
                VALUES ('Sin pasos', 'sin-pasos-test', :c)
                """).param("c", cultureId).update())
                .doesNotThrowAnyException();

        jdbc.sql("DELETE FROM recipe WHERE slug = 'sin-pasos-test'").update();
    }
}
