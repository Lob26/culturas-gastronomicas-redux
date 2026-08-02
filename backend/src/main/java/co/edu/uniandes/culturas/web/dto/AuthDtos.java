package co.edu.uniandes.culturas.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** DTOs de registro y acceso. */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(name = "RegisterRequest", description = "Alta inmediata, sin verificación de correo")
    public record RegisterRequest(
            @NotBlank(message = "el nombre de usuario es obligatorio")
            @Pattern(regexp = "^[a-z0-9][a-z0-9_.-]{2,39}$",
                    message = "usa entre 3 y 40 caracteres: minúsculas, números, punto, guion o guion bajo")
            String username,

            @Email(message = "el correo no tiene un formato válido")
            @Size(max = 160)
            String email,

            @NotBlank(message = "el nombre visible es obligatorio")
            @Size(max = 80)
            String displayName,

            @NotBlank(message = "la contraseña es obligatoria")
            @Size(min = 8, max = 100, message = "la contraseña debe tener al menos 8 caracteres")
            String password
    ) {
    }

    @Schema(name = "LoginRequest")
    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }

    @Schema(name = "TokenResponse", description = "Token para la cabecera Authorization: Bearer")
    public record TokenResponse(
            String token,
            Instant expiresAt,
            String username,
            String displayName,
            @Schema(example = "ROLE_USER")
            String role
    ) {
    }
}
