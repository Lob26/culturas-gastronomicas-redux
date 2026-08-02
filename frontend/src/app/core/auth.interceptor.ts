import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { environment } from '../../environments/environment';

/**
 * Añade el token a las peticiones dirigidas a nuestra API.
 *
 * <p>Interceptor funcional, no una clase con {@code HTTP_INTERCEPTORS}: es la
 * forma vigente desde Angular 15 y permite usar {@code inject} directamente.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(AuthService).token();

  // La comprobación del destino no es cosmética: sin ella, cualquier petición
  // a un tercero (una imagen, una API externa) saldría con la cabecera
  // Authorization puesta, filtrando el token fuera de nuestro dominio.
  const isOwnApi = req.url.startsWith(environment.apiUrl) || req.url.startsWith('/api/');

  if (!token || !isOwnApi) {
    return next(req);
  }

  return next(
    req.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    }),
  );
};
