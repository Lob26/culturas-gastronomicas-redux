package co.edu.uniandes.culturas.support;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un test de integración: contexto completo sobre Postgres y Redis reales.
 *
 * <p>Una anotación compuesta y no una clase base de la que heredar. Con herencia,
 * cada test arrastra los métodos y campos de su padre y no se puede combinar con
 * otra jerarquía; con una anotación, Spring cachea el contexto igual —la clave
 * de caché es la configuración, no la clase— y los tests siguen siendo planos.
 *
 * <p>Los contenedores los declara {@link TestContainers}, que se importa aquí:
 * al ser la misma configuración para todos, Spring reutiliza un único contexto
 * y los contenedores se levantan <strong>una sola vez</strong> para toda la
 * suite. Declararlos con {@code @Container} en cada clase los levantaría por
 * clase, y la suite pasaría de segundos a minutos.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(classes = TestContainers.class)
public @interface IntegrationTest {
}
