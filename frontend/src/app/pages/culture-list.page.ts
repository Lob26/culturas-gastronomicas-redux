import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgOptimizedImage } from '@angular/common';
import { CardModule } from 'primeng/card';
import { PaginatorModule } from 'primeng/paginator';
import { SkeletonModule } from 'primeng/skeleton';
import { MessageModule } from 'primeng/message';
import { CatalogService } from '../core/catalog.service';

/**
 * Listado de culturas.
 *
 * <p>Tiene estados de carga, de error y de vacío. El proyecto de 2023 no tenía
 * ninguno de los tres: los fallos iban a {@code console.error} y la pantalla se
 * quedaba en blanco sin explicar nada.
 */
@Component({
  selector: 'app-culture-list',
  standalone: true,
  imports: [RouterLink, NgOptimizedImage, CardModule, PaginatorModule, SkeletonModule, MessageModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-6xl px-4 py-8">
      <header class="mb-6">
        <h1 class="text-3xl font-semibold tracking-tight">Culturas gastronómicas</h1>
        <p class="mt-1 text-surface-500 dark:text-surface-400">
          Cocinas del mundo, con sus recetas y los países donde se practican.
        </p>
      </header>

      @if (cultures.isLoading()) {
        <div class="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          @for (placeholder of [1, 2, 3, 4, 5, 6]; track placeholder) {
            <p-skeleton height="14rem" borderRadius="0.75rem" />
          }
        </div>
      } @else if (cultures.error()) {
        <p-message severity="error">No se pudo cargar el catálogo. Reintenta en unos segundos.</p-message>
      } @else if (!cultures.value()?.content?.length) {
        <p-message severity="info">Todavía no hay culturas registradas.</p-message>
      } @else {
        <div class="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          @for (culture of cultures.value()!.content; track culture.slug) {
            <a
              [routerLink]="['/culturas', culture.slug]"
              class="group rounded-xl border border-surface-200 bg-surface-0 p-5 transition
                     hover:border-primary-400 hover:shadow-lg
                     dark:border-surface-700 dark:bg-surface-900"
            >
              @if (culture.imageUrl) {
                <img
                  [ngSrc]="culture.imageUrl"
                  width="320"
                  height="180"
                  class="mb-4 h-40 w-full rounded-lg object-cover"
                  [alt]="culture.name"
                />
              }
              <h2 class="text-xl font-medium group-hover:text-primary-500">{{ culture.name }}</h2>
              <p class="mt-2 line-clamp-3 text-sm text-surface-500 dark:text-surface-400">
                {{ culture.description }}
              </p>
              <p class="mt-4 text-xs uppercase tracking-wide text-surface-400">
                {{ culture.recipeCount }} receta{{ culture.recipeCount === 1 ? '' : 's' }}
                · {{ culture.categoryCount }} categoría{{ culture.categoryCount === 1 ? '' : 's' }}
              </p>
            </a>
          }
        </div>

        @if (cultures.value()!.totalPages > 1) {
          <p-paginator
            class="mt-8 block"
            [rows]="cultures.value()!.size"
            [totalRecords]="cultures.value()!.totalElements"
            [first]="cultures.value()!.page * cultures.value()!.size"
            (onPageChange)="page.set($event.page ?? 0)"
          />
        }
      }
    </section>
  `,
})
export class CultureListPage {
  private readonly catalog = inject(CatalogService);

  protected readonly page = signal(0);

  // El recurso se recarga solo cuando cambia `page`, porque esa señal se lee
  // dentro de la función que construye la URL.
  protected readonly cultures = this.catalog.cultures(this.page);
}
