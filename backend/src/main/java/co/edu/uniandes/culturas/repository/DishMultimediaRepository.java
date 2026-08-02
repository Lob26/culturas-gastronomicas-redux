package co.edu.uniandes.culturas.repository;

import co.edu.uniandes.culturas.domain.DishMultimedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DishMultimediaRepository extends JpaRepository<DishMultimedia, Long> {

    /**
     * Todas las imágenes con su receta ya resuelta.
     *
     * <p>El verificador necesita el nombre de la receta para cada resultado, y
     * sin este fetch cada una dispararía una consulta adicional — desde un hilo
     * virtual y fuera de transacción, donde además fallaría por sesión cerrada.
     */
    @Query("SELECT m FROM DishMultimedia m JOIN FETCH m.recipe")
    List<DishMultimedia> findAllWithRecipe();

    /**
     * Imágenes que la última comprobación marcó como rotas.
     *
     * <p>Consulta explícita en lugar de método derivado: {@code NotBetween} no
     * es una palabra clave de Spring Data, y el nombre derivado
     * {@code findByLastStatusNotBetween} falla al <em>arrancar</em> la
     * aplicación con "No property 'not' found for type 'Short'".
     */
    @Query("SELECT m FROM DishMultimedia m WHERE m.lastStatus IS NOT NULL AND (m.lastStatus < 200 OR m.lastStatus >= 300)")
    List<DishMultimedia> findBroken();
}
