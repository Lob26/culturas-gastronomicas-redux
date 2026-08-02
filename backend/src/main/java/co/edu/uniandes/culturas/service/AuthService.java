package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.config.CulturasProperties;
import co.edu.uniandes.culturas.domain.AppUser;
import co.edu.uniandes.culturas.repository.AppUserRepository;
import co.edu.uniandes.culturas.web.dto.AuthDtos;
import co.edu.uniandes.culturas.web.error.DomainRuleException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Registro, acceso y emisión de tokens. */
@Service
public class AuthService {

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final CulturasProperties properties;

    public AuthService(AppUserRepository repository,
                       PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder,
                       CulturasProperties properties) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /**
     * Alta inmediata: sin correo de confirmación ni aprobación.
     *
     * <p>Es deliberado. La identidad aquí no sirve para dejar gente fuera sino
     * para atribuir: quién creó cada receta y quién puede editarla.
     */
    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest request) {
        if (repository.existsByUsername(request.username())) {
            throw new DomainRuleException("usuario-duplicado",
                    "El nombre de usuario '%s' ya está tomado".formatted(request.username()));
        }
        if (request.email() != null && !request.email().isBlank() && repository.existsByEmail(request.email())) {
            throw new DomainRuleException("correo-duplicado", "Ese correo ya está registrado");
        }

        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setEmail(request.email() == null || request.email().isBlank() ? null : request.email());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(AppUser.Role.USER);

        return issueToken(repository.save(user));
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        AppUser user = repository.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Mismo mensaje y mismo tipo que cuando el usuario no existe: si
            // difirieran, se podría averiguar qué cuentas están registradas.
            throw new BadCredentialsException("Credenciales inválidas");
        }
        return issueToken(user);
    }

    private AuthDtos.TokenResponse issueToken(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.security().jwtTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("culturas-gastronomicas")
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getUsername())
                // El convertidor de la cadena de seguridad lee este claim con
                // prefijo vacío, así que las autoridades ya vienen con ROLE_.
                .claim("roles", List.of(user.getRole().authority()))
                .claim("displayName", user.getDisplayName())
                .build();

        String token = jwtEncoder.encode(
                        JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return new AuthDtos.TokenResponse(token, expiresAt, user.getUsername(),
                user.getDisplayName(), user.getRole().authority());
    }
}
