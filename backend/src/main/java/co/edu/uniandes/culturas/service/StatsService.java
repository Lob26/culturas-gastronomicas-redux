package co.edu.uniandes.culturas.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Números del catálogo, en una sola consulta.
 *
 * <p>Una consulta con subselects y no ocho {@code count(*)} sueltos: el flujo
 * en vivo las pide cada pocos segundos y cada consulta suelta es un viaje de ida
 * y vuelta a Postgres. Ocho viajes por tick y por pestaña abierta se nota; uno
 * no.
 *
 * <p>JdbcClient y no JPA porque aquí no hay entidades que cargar, sólo agregados.
 * Pasar esto por Hibernate obligaría a inventar un tipo de resultado y a que el
 * contexto de persistencia gestione algo que nadie va a modificar.
 */
@Service
public class StatsService {

    private final JdbcClient jdbc;

    public StatsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        return jdbc.sql("""
                        SELECT
                          (SELECT count(*) FROM gastronomic_culture)                    AS culturas,
                          (SELECT count(*) FROM recipe)                                 AS recetas,
                          (SELECT count(*) FROM app_user)                               AS usuarios,
                          (SELECT count(*) FROM rating)                                 AS valoraciones,
                          (SELECT count(*) FROM favorite)                               AS favoritos,
                          (SELECT count(*) FROM recipe WHERE embedding IS NOT NULL)     AS indexadas,
                          (SELECT count(*) FROM dish_multimedia
                            WHERE last_status IS NOT NULL
                              AND (last_status < 200 OR last_status >= 300))            AS imagenes_rotas,
                          (SELECT coalesce(round(avg(score), 2), 0) FROM rating)        AS media_global
                        """)
                .query((rs, row) -> new Snapshot(
                        rs.getLong("culturas"),
                        rs.getLong("recetas"),
                        rs.getLong("usuarios"),
                        rs.getLong("valoraciones"),
                        rs.getLong("favoritos"),
                        rs.getLong("indexadas"),
                        rs.getLong("imagenes_rotas"),
                        rs.getBigDecimal("media_global"),
                        Instant.now()))
                .single();
    }

    /**
     * @param indexadas cuántas recetas tienen vector; comparado con `recetas`
     *                  dice si el reindexado va al día sin tener que mirar logs
     */
    public record Snapshot(
            long culturas,
            long recetas,
            long usuarios,
            long valoraciones,
            long favoritos,
            long indexadas,
            long imagenesRotas,
            java.math.BigDecimal mediaGlobal,
            Instant momento
    ) {
    }
}
