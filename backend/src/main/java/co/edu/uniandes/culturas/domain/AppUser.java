package co.edu.uniandes.culturas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Persona registrada. El registro es instantáneo y sin verificación de correo.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "app_user",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_user_username", columnNames = "username"),
                @UniqueConstraint(name = "uq_user_email", columnNames = "email")
        })
public class AppUser extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String username;

    @Column(length = 160)
    private String email;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    /**
     * Hash BCrypt. Nunca sale de aquí: no aparece en ningún DTO y se excluye
     * del toString para que no acabe en un log por accidente.
     */
    @ToString.Exclude
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role = Role.USER;

    public enum Role {
        USER,
        ADMIN;

        /** Spring Security espera el prefijo {@code ROLE_} en las autoridades. */
        public String authority() {
            return "ROLE_" + name();
        }
    }
}
