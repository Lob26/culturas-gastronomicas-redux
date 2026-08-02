/**
 * En producción la API se sirve bajo el mismo origen, detrás de un proxy
 * inverso, así que la ruta es relativa: evita CORS y no hay que reconstruir la
 * aplicación para cambiar de host.
 */
export const environment = {
  production: true,
  apiUrl: '/api/v2',
};
