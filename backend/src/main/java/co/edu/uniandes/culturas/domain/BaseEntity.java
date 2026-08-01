package co.edu.uniandes.culturas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;

/**
 * Identidad, versionado optimista y auditoría, compartidos por todas las entidades.
 *
 * <p>La versión de 2023 de esta clase tenía tres defectos que se corrigen aquí:
 *
 * <ul>
 *   <li>{@code hashCode()} devolvía {@code getClass().hashCode()}, con lo que todas
 *       las instancias de un tipo colisionaban y cualquier {@code HashSet} de
 *       entidades degeneraba en una lista enlazada. Las clases hijas además
 *       repetían el mismo par equals/hashCode, palabra por palabra.
 *   <li>{@code compareTo} hacía {@code (int) (this.id - o.id)}: NPE con entidades
 *       transitorias y desbordamiento de {@code int} con ids grandes. Se elimina;
 *       el orden es responsabilidad de la consulta, no de la entidad.
 *   <li>No había bloqueo optimista, así que dos escrituras concurrentes sobre la
 *       misma fila se pisaban en silencio.
 * </ul>
 *
 * <p>El contrato de equals/hashCode sigue la recomendación de Hibernate para
 * entidades con id generado: un {@code hashCode} constante por tipo — estable
 * antes y después de persistir, que es el único requisito real del contrato — y
 * un {@code equals} que sólo considera iguales dos entidades ya persistidas.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bloqueo optimista: dos escrituras concurrentes ya no se pisan en silencio. */
    @Version
    private Long version;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** Login de quien creó la fila. Null para los datos semilla. */
    @CreatedBy
    @Column(updatable = false, length = 64)
    private String createdBy;

    @LastModifiedBy
    @Column(length = 64)
    private String updatedBy;

    /**
     * Dos entidades son iguales sólo si ambas están persistidas y comparten id.
     * Una entidad transitoria no es igual a nada, ni siquiera a otra transitoria.
     */
    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        // Un proxy perezoso de Hibernate tiene una clase sintética distinta de la
        // real; comparar con getClass() haría que una entidad cargada nunca fuera
        // igual a su propio proxy.
        Class<?> thisType = effectiveClass(this);
        Class<?> otherType = effectiveClass(o);
        if (thisType != otherType) {
            return false;
        }
        Long thisId = this.getId();
        return thisId != null && Objects.equals(thisId, ((BaseEntity) o).getId());
    }

    /**
     * Constante por tipo, a propósito. Un hash derivado del id cambiaría al
     * persistir la entidad, y cualquier colección que ya la contuviera dejaría
     * de encontrarla.
     */
    @Override
    public final int hashCode() {
        return effectiveClass(this).hashCode();
    }

    private static Class<?> effectiveClass(Object o) {
        return o instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : o.getClass();
    }
}
