package co.edu.uniandes.culturas.security;

import co.edu.uniandes.culturas.config.CulturasProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.util.List;

/**
 * Cadena de seguridad sin estado.
 *
 * <p>Tres formas de autenticarse, cada una para un tipo de cliente:
 * <ul>
 *   <li><strong>JWT</strong> — el navegador, tras registrarse o entrar.
 *   <li><strong>HTTP Basic</strong> — curl y el «Try it out» de Swagger UI.
 *   <li><strong>X-API-Key</strong> — n8n y los scripts.
 * </ul>
 *
 * <p>Las lecturas son públicas y las escrituras exigen identidad. Nadie queda
 * fuera: el registro es inmediato. Lo que la identidad aporta no es una barrera
 * sino atribución — quién creó cada cosa y quién puede editarla.
 *
 * <p>En 2023 no había ninguna capa de seguridad: cada endpoint, incluidos todos
 * los DELETE, era anónimo, con CORS abierto a {@code *} y la consola de H2
 * expuesta sin autenticar.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final CulturasProperties properties;

    public SecurityConfig(CulturasProperties properties) {
        this.properties = properties;
    }

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                // Ojo con los patrones: Spring Security 7 los compila como
                // PathPattern, donde ** sólo es válido como último segmento.
                .securityMatcher("/**")
                // Sin CSRF porque no hay sesión ni cookies: el token viaja en
                // la cabecera Authorization, que un sitio de terceros no puede
                // provocar que el navegador envíe.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // El orden importa: gana la primera regla que coincida.
                        // Lo personal va ANTES que la apertura de las lecturas,
                        // porque «GET /api/v2/favoritos» también es un GET y
                        // con la regla general quedaría público — el endpoint
                        // recibiría un principal nulo y expondría, o reventaría.
                        .requestMatchers("/api/v2/favoritos", "/api/v2/favoritos/**").authenticated()
                        .requestMatchers("/api/v2/recetas/*/valoraciones/mia").authenticated()
                        // Misma trampa: las recomendaciones se leen con GET y
                        // salen del token, así que dejarlas caer en la regla
                        // general de lectura pública les daría un principal nulo.
                        .requestMatchers("/api/v2/buscar/recomendaciones").authenticated()
                        // El asistente es un GET, pero cada llamada gasta dinero
                        // en un proveedor externo. Dejarlo público sería regalar
                        // un endpoint de facturación a cualquiera con la URL.
                        //
                        // PENDIENTE: exigir identidad limita quién puede gastar,
                        // no cuánto. Falta un límite por usuario —Redis ya está
                        // en el stack para llevar la cuenta—; sin él, un usuario
                        // registrado puede vaciar la cuota en un bucle.
                        .requestMatchers("/api/v2/asistente/**").authenticated()
                        // Subir imágenes exige identidad: consume almacenamiento
                        // y queda atribuido a quien lo sube.
                        .requestMatchers(HttpMethod.POST, "/api/v2/recetas/*/imagenes").authenticated()

                        // Lectura del catálogo: pública.
                        .requestMatchers(HttpMethod.GET, "/api/v2/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v2/auth/**").permitAll()
                        // Sondas y documentación.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // Métricas: sólo para quien administra.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Todo lo demás —es decir, cualquier escritura— exige identidad.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .httpBasic(Customizer.withDefaults())
                .addFilterBefore(new ApiKeyFilter(properties.security().apiKey()),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Lee los roles del claim {@code roles} en lugar de los scopes de OAuth2.
     *
     * <p>El convertidor por defecto busca {@code scope} y antepone
     * {@code SCOPE_}, lo que dejaría a {@code hasRole("ADMIN")} sin coincidir
     * nunca — un fallo silencioso: no da error, simplemente no autoriza.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Coste 10: el valor por defecto de Spring. Subirlo endurece el hash a
        // costa de latencia en cada acceso.
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private SecretKeySpec secretKey() {
        return new SecretKeySpec(properties.security().jwtSecretBytes(), "HmacSHA256");
    }

    /**
     * CORS acotado al frontend de desarrollo.
     *
     * <p>El proyecto de 2023 permitía {@code *} sobre {@code /**} para GET,
     * POST, PUT y DELETE, sin ninguna autenticación detrás.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.security().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", ApiKeyFilter.HEADER));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
