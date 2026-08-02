package co.edu.uniandes.culturas.security;

import co.edu.uniandes.culturas.repository.AppUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carga usuarios para HTTP Basic. El JWT no pasa por aquí: su firma ya prueba
 * la identidad y volver a consultar la base en cada petición desharía la
 * ventaja de un esquema sin estado.
 */
@Service
public class CulturasUserDetailsService implements UserDetailsService {

    private final AppUserRepository repository;

    public CulturasUserDetailsService(AppUserRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return repository.findByUsername(username)
                .map(user -> User.withUsername(user.getUsername())
                        .password(user.getPasswordHash())
                        .authorities(user.getRole().authority())
                        .build())
                // El mensaje no distingue entre "no existe" y "contraseña
                // incorrecta": hacerlo permitiría enumerar qué cuentas existen.
                .orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));
    }
}
