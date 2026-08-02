import { RenderMode, ServerRoute } from '@angular/ssr';

/**
 * Modo de render por ruta (renderizado híbrido).
 *
 * <p>Las rutas estáticas se prerrenderizan en el build: llegan como HTML ya
 * hecho, sin esperar a la API. Las de detalle se renderizan en cliente porque
 * prerrenderizarlas exigiría consultar el backend en tiempo de compilación
 * —y entonces una receta creada después del build no existiría hasta el
 * siguiente— mientras que renderizarlas en servidor obligaría a que el
 * proceso Node hablara con la API en cada visita.
 *
 * <p>Modo cocina va en cliente por otra razón: usa temporizadores y Wake Lock,
 * APIs que no existen en el servidor.
 */
export const serverRoutes: ServerRoute[] = [
  { path: '', renderMode: RenderMode.Prerender },
  { path: 'culturas', renderMode: RenderMode.Prerender },
  { path: 'recetas', renderMode: RenderMode.Prerender },
  { path: 'entrar', renderMode: RenderMode.Prerender },

  // Búsqueda en cliente: el resultado depende de la query string, así que
  // prerrenderizarla produciría un HTML con la lista vacía que después se
  // reemplaza — el usuario vería un «nada coincide» parpadear antes de sus
  // resultados. Además la página escribe en la URL mientras se escribe.
  { path: 'buscar', renderMode: RenderMode.Client },

  { path: 'culturas/:slug', renderMode: RenderMode.Client },
  { path: 'recetas/:slug', renderMode: RenderMode.Client },
  { path: 'recetas/:slug/cocinar', renderMode: RenderMode.Client },

  { path: '**', renderMode: RenderMode.Prerender },
];
