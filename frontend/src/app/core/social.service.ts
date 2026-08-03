import { Injectable, Signal, inject, signal } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../environments/environment';
import type { Favorite, FavoriteTarget, Rating, Recommendations, SearchHit } from './api.types';

/**
 * Valoraciones y recetario personal.
 *
 * <p>Mismo reparto que en {@link CatalogService}: {@code httpResource} para
 * leer, {@code HttpClient} para escribir. Un recurso es una lectura declarativa
 * que se recarga sola cuando cambia su URL; un POST es un comando puntual y no
 * encaja en ese modelo.
 *
 * <p>Las escrituras devuelven una promesa en lugar de mutar un estado global.
 * Quien llama decide qué recargar, que en la práctica es el recurso de al lado
 * — y hacerlo explícito evita el clásico «guardé y la pantalla no se enteró».
 */
@Injectable({ providedIn: 'root' })
export class SocialService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl;

  /**
   * Contador que fuerza la recarga de los recursos dependientes.
   *
   * <p>Existe porque `httpResource` se recarga cuando cambia su URL, y aquí la
   * URL no cambia: tras votar hay que releer exactamente la misma. Incluirlo en
   * la función del recurso da un punto de invalidación explícito, en vez de
   * duplicar en el cliente el estado que acaba de calcular el servidor.
   */
  private readonly revision = signal(0);

  ratings(slug: Signal<string | undefined>) {
    return httpResource<Rating[]>(() => {
      const value = slug();
      this.revision();
      return value ? `${this.base}/recetas/${value}/valoraciones` : undefined;
    });
  }

  /**
   * El recetario del usuario.
   *
   * <p>Devuelve 401 sin sesión, así que sólo se pide cuando hay una: el
   * llamador pasa una señal que vale `undefined` mientras no la haya, y el
   * recurso se queda en reposo en lugar de provocar un error esperado.
   */
  favorites(enabled: Signal<boolean>) {
    return httpResource<Favorite[]>(() => {
      this.revision();
      return enabled() ? `${this.base}/favoritos` : undefined;
    });
  }

  recommendations(enabled: Signal<boolean>, limit = 6) {
    return httpResource<Recommendations>(() => {
      this.revision();
      return enabled() ? `${this.base}/buscar/recomendaciones?limit=${limit}` : undefined;
    });
  }

  similar(slug: Signal<string | undefined>, limit = 4) {
    return httpResource<SearchHit[]>(() => {
      const value = slug();
      return value ? `${this.base}/buscar/similares/${value}?limit=${limit}` : undefined;
    });
  }

  async rate(slug: string, score: number, comment: string | null): Promise<void> {
    await firstValueFrom(
      this.http.put<Rating>(`${this.base}/recetas/${slug}/valoraciones/mia`, { score, comment }),
    );
    this.invalidate();
  }

  async removeRating(slug: string): Promise<void> {
    await firstValueFrom(this.http.delete<void>(`${this.base}/recetas/${slug}/valoraciones/mia`));
    this.invalidate();
  }

  /**
   * Guarda o quita, según cómo esté ahora.
   *
   * <p>Es el servidor quien decide y devuelve el estado resultante; el cliente
   * no manda «guardar» ni «quitar». Con dos pestañas abiertas, un cliente que
   * enviara la acción calculada a partir de lo que cree saber acabaría
   * invirtiendo el estado que ya cambió en la otra.
   */
  async toggleFavorite(type: FavoriteTarget, id: number): Promise<boolean> {
    const response = await firstValueFrom(
      this.http.post<{ favorito: boolean }>(`${this.base}/favoritos/${type}/${id}`, {}),
    );
    this.invalidate();
    return response.favorito;
  }

  private invalidate(): void {
    this.revision.update((value) => value + 1);
  }
}
