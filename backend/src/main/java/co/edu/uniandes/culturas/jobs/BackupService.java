package co.edu.uniandes.culturas.jobs;

import co.edu.uniandes.culturas.service.MediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Respaldo lógico del catálogo, guardado en MinIO.
 *
 * <p><strong>No es un {@code pg_dump}</strong>, y la diferencia importa. Un
 * pg_dump necesita el binario de Postgres: no está en el contenedor de n8n, ni
 * en el de la base —donde vive el servidor, no las herramientas—, ni junto a la
 * JVM. Montarlo en alguno de los tres sólo para esto añadiría una imagen propia
 * al stack.
 *
 * <p>Lo que hace es exportar el contenido a JSON con las mismas consultas que
 * usa la aplicación. Cubre el caso real —recuperar los datos si alguien ejecuta
 * `task nuke` de más— y no cubre el que aquí no aplica: restaurar un clúster
 * entero con sus roles, secuencias y permisos. Para eso haría falta pg_dump de
 * verdad, y así queda dicho en vez de dar por hecho que esto lo sustituye.
 *
 * <p>El destino es MinIO, que ya está en el stack y hasta ahora sólo guardaba
 * imágenes. Un respaldo en el disco del contenedor de la base desaparecería con
 * el mismo `nuke` del que protege.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /**
     * Tablas exportadas, en orden de dependencia.
     *
     * <p>El orden es el de inserción para restaurar: primero las que nadie
     * referencia. Restaurar en orden alfabético fallaría contra las claves
     * ajenas.
     */
    private static final List<String> TABLES = List.of(
            "country",
            "gastronomic_culture",
            "culture_country",
            "gastronomic_category",
            "recipe",
            "recipe_step",
            "ingredient",
            "dish_multimedia",
            "representative_product",
            "restaurant",
            "michelin_star",
            "app_user",
            "rating",
            "favorite");

    private final JdbcClient jdbc;
    private final MediaService media;
    private final JsonMapper json;

    public BackupService(JdbcClient jdbc, MediaService media, JsonMapper json) {
        this.jdbc = jdbc;
        this.media = media;
        this.json = json;
    }

    /**
     * Exporta y sube.
     *
     * <p>En una sola transacción de lectura para que todas las tablas se lean
     * en el mismo instante lógico. Sin ella, una receta creada a mitad del
     * volcado podría aparecer con sus pasos pero sin su cultura, y el respaldo
     * sería irrestaurable justo cuando hiciera falta.
     */
    @Transactional(readOnly = true)
    public Result run() {
        long start = System.nanoTime();

        Map<String, List<Map<String, Object>>> content = TABLES.stream()
                .collect(java.util.LinkedHashMap::new,
                        (map, table) -> map.put(table, dump(table)),
                        java.util.LinkedHashMap::putAll);

        long rows = content.values().stream().mapToLong(List::size).sum();

        Instant now = Instant.now();
        // La clave lleva la fecha, así que los respaldos no se pisan y se
        // ordenan solos al listarlos.
        String key = "respaldos/catalogo-%s.json".formatted(now.toString().replace(':', '-'));

        byte[] bytes = json.writeValueAsBytes(Map.of(
                "generado", now.toString(),
                "tablas", content));

        media.put(key, bytes, "application/json");

        long millis = (System.nanoTime() - start) / 1_000_000;
        log.info("Respaldo subido: clave={} tablas={} filas={} bytes={} ms={}",
                key, TABLES.size(), rows, bytes.length, millis);

        return new Result(key, TABLES.size(), rows, bytes.length, millis);
    }

    private List<Map<String, Object>> dump(String table) {
        // El nombre de la tabla NO viene de fuera: sale de la lista constante de
        // arriba. Es lo que hace seguro interpolarlo, porque un identificador no
        // puede ir como parámetro de sentencia.
        return jdbc.sql("SELECT * FROM " + table).query().listOfRows();
    }

    public record Result(String key, int tables, long rows, int bytes, long millis) {
    }
}
