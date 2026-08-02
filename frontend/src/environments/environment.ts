/**
 * Configuración de entorno.
 *
 * <p>En 2023 environment.ts y environment.prod.ts tenían exactamente el mismo
 * valor —`http://localhost:8080/api/`— de modo que el build de producción
 * apuntaba a localhost y el reemplazo configurado en angular.json no cambiaba
 * nada.
 */
export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080/api/v2',
};
