import { Injectable, Signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { environment } from '../../environments/environment';
import type {
  CultureDetail,
  CultureSummary,
  PagedResponse,
  RecipeDetail,
  RecipeSummary,
} from './api.types';

/**
 * Lecturas del catálogo, expuestas como recursos de señal.
 *
 * <p>Se usa {@link httpResource} para las lecturas y {@code HttpClient} directo
 * para las escrituras (ver {@link AuthService}). El reparto no es arbitrario:
 * un recurso es una primitiva declarativa de lectura —se recarga sola cuando
 * cambia su URL y expone value/status/error/isLoading como señales— mientras
 * que un POST es un comando puntual que no encaja en ese modelo.
 *
 * <p>Cada método devuelve el recurso en lugar de guardarlo: {@code httpResource}
 * debe invocarse en contexto de inyección, y el sitio natural es la
 * inicialización de un campo del componente.
 */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly base = environment.apiUrl;

  /**
   * Culturas paginadas. La página llega como señal, así que cambiarla
   * dispara la recarga sin ninguna suscripción manual.
   */
  cultures(page: Signal<number>, size = 12) {
    return httpResource<PagedResponse<CultureSummary>>(
      () => `${this.base}/culturas?page=${page()}&size=${size}&sort=name,asc`,
    );
  }

  culture(slug: Signal<string | undefined>) {
    return httpResource<CultureDetail>(() => {
      const value = slug();
      // Devolver undefined deja el recurso en reposo en vez de lanzar una
      // petición a una URL a medio construir.
      return value ? `${this.base}/culturas/${value}` : undefined;
    });
  }

  recipesOfCulture(slug: Signal<string | undefined>, page: Signal<number>, size = 12) {
    return httpResource<PagedResponse<RecipeSummary>>(() => {
      const value = slug();
      return value
        ? `${this.base}/culturas/${value}/recetas?page=${page()}&size=${size}`
        : undefined;
    });
  }

  recipes(page: Signal<number>, size = 12) {
    return httpResource<PagedResponse<RecipeSummary>>(
      () => `${this.base}/recetas?page=${page()}&size=${size}&sort=name,asc`,
    );
  }

  recipe(slug: Signal<string | undefined>) {
    return httpResource<RecipeDetail>(() => {
      const value = slug();
      return value ? `${this.base}/recetas/${value}` : undefined;
    });
  }
}
