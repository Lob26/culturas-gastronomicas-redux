import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SkeletonModule } from 'primeng/skeleton';
import { TagModule } from 'primeng/tag';
import { CatalogService } from '../core/catalog.service';
import type { SearchHit } from '../core/api.types';

/**
 * Búsqueda.
 *
 * <p>Esto en 2023 era un botón que abría un aviso cuyo cuerpo decía «TO-DO».
 *
 * <p>El término se mantiene sincronizado con la query string en los dos
 * sentidos: se lee al entrar, para que una búsqueda se pueda compartir por
 * enlace o recargar sin perderla, y se escribe al buscar. Se usa
 * {@code replaceUrl} para no dejar una entrada de historial por cada pulsación,
 * que convertiría el botón «atrás» en algo inservible.
 */
@Component({
  selector: 'app-search',
  standalone: true,
  imports: [RouterLink, FormsModule, InputTextModule, MessageModule, SkeletonModule, TagModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-3xl px-4 py-8">
      <header class="mb-6">
        <h1 class="text-3xl font-semibold tracking-tight">Buscar</h1>
        <p class="mt-1 text-surface-500 dark:text-surface-400">
          Por nombre, por ingrediente o describiendo lo que te apetece.
        </p>
      </header>

      <input
        pInputText
        type="search"
        class="w-full"
        placeholder="carbonara · algo picante con maíz · raw fish"
        aria-label="Término de búsqueda"
        [ngModel]="term()"
        (ngModelChange)="term.set($event)"
      />

      @if (term().trim().length === 1) {
        <p class="mt-3 text-sm text-surface-500">Escribe al menos dos caracteres.</p>
      }

      <div class="mt-6">
        @if (results.isLoading()) {
          @for (placeholder of [1, 2, 3]; track placeholder) {
            <p-skeleton height="3.5rem" borderRadius="0.5rem" styleClass="mb-3" />
          }
        } @else if (results.error()) {
          <p-message severity="error">La búsqueda falló. Reintenta en unos segundos.</p-message>
        } @else if (term().trim().length >= 2 && !results.value()?.length) {
          <p-message severity="info">Nada coincide con «{{ term() }}».</p-message>
        } @else if (results.value()?.length) {
          <ul class="space-y-3">
            @for (hit of results.value()!; track hit.type + hit.slug) {
              <li>
                <a
                  [routerLink]="linkFor(hit)"
                  class="flex items-center justify-between rounded-lg border border-surface-200
                         bg-surface-0 px-4 py-3 transition hover:border-primary-400 hover:shadow
                         dark:border-surface-700 dark:bg-surface-900"
                >
                  <span class="font-medium">{{ hit.name }}</span>
                  <p-tag
                    [value]="hit.type === 'RECIPE' ? 'Receta' : 'Cultura'"
                    [severity]="hit.type === 'RECIPE' ? 'success' : 'info'"
                  />
                </a>
              </li>
            }
          </ul>
        }
      </div>
    </section>
  `,
})
export class SearchPage {
  private readonly catalog = inject(CatalogService);
  private readonly router = inject(Router);

  /**
   * Inicializado desde la URL de forma síncrona.
   *
   * <p>Se lee de `router.parseUrl(router.url)` y no de un `ActivatedRoute`
   * suscrito porque el valor hace falta en la construcción del componente: si
   * llegara después, el recurso ya habría hecho una petición con el término
   * vacío y la primera pintada mostraría «no hay resultados» antes de
   * corregirse sola.
   */
  protected readonly term = signal(
    this.router.parseUrl(this.router.url).queryParams['q'] ?? '',
  );

  protected readonly results = this.catalog.search(this.term);

  constructor() {
    effect(() => {
      const value = this.term().trim();
      this.router.navigate([], {
        queryParams: { q: value || null },
        replaceUrl: true,
      });
    });
  }

  protected linkFor(hit: SearchHit): unknown[] {
    return hit.type === 'RECIPE' ? ['/recetas', hit.slug] : ['/culturas', hit.slug];
  }
}
