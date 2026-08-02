import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CardModule } from 'primeng/card';
import { TagModule } from 'primeng/tag';
import { PaginatorModule } from 'primeng/paginator';
import { SkeletonModule } from 'primeng/skeleton';
import { MessageModule } from 'primeng/message';
import { CatalogService } from '../core/catalog.service';

@Component({
  selector: 'app-recipe-list',
  standalone: true,
  imports: [RouterLink, CardModule, TagModule, PaginatorModule, SkeletonModule, MessageModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-6xl px-4 py-8">
      <header class="mb-6">
        <h1 class="text-3xl font-semibold tracking-tight">Recetas</h1>
        <p class="mt-1 text-surface-500 dark:text-surface-400">
          Todas las recetas del catálogo, con su cocina de origen.
        </p>
      </header>

      @if (recipes.isLoading()) {
        <div class="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          @for (placeholder of [1, 2, 3, 4, 5, 6]; track placeholder) {
            <p-skeleton height="16rem" borderRadius="0.75rem" />
          }
        </div>
      } @else if (recipes.error()) {
        <p-message severity="error">No se pudieron cargar las recetas.</p-message>
      } @else if (recipes.value(); as result) {
        <div class="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          @for (recipe of result.content; track recipe.slug) {
            <a
              [routerLink]="['/recetas', recipe.slug]"
              class="group flex flex-col overflow-hidden rounded-xl border border-surface-200
                     bg-surface-0 transition hover:border-primary-400 hover:shadow-lg
                     dark:border-surface-700 dark:bg-surface-900"
            >
              @if (recipe.imageUrl) {
                <img
                  [src]="recipe.imageUrl"
                  [alt]="recipe.name"
                  class="h-44 w-full object-cover"
                  loading="lazy"
                />
              }
              <div class="flex flex-1 flex-col p-5">
                <h2 class="text-lg font-medium group-hover:text-primary-500">{{ recipe.name }}</h2>
                <p class="mt-1 text-xs uppercase tracking-wide text-surface-400">
                  {{ recipe.cultureName }}
                </p>
                <p class="mt-2 line-clamp-2 flex-1 text-sm text-surface-500 dark:text-surface-400">
                  {{ recipe.description }}
                </p>
                <div class="mt-4 flex gap-2">
                  @if (recipe.difficulty) {
                    <p-tag [value]="label(recipe.difficulty)" [severity]="severity(recipe.difficulty)" />
                  }
                  @if (recipe.prepTimeMinutes) {
                    <p-tag [value]="recipe.prepTimeMinutes + ' min'" severity="secondary" />
                  }
                </div>
              </div>
            </a>
          }
        </div>

        @if (result.totalPages > 1) {
          <p-paginator
            class="mt-8 block"
            [rows]="result.size"
            [totalRecords]="result.totalElements"
            [first]="result.page * result.size"
            (onPageChange)="page.set($event.page ?? 0)"
          />
        }
      }
    </section>
  `,
})
export class RecipeListPage {
  private readonly catalog = inject(CatalogService);

  protected readonly page = signal(0);
  protected readonly recipes = this.catalog.recipes(this.page);

  protected label(value: string): string {
    return { FACIL: 'Fácil', MEDIA: 'Media', DIFICIL: 'Difícil' }[value] ?? value;
  }

  protected severity(value: string): 'success' | 'warn' | 'danger' {
    return value === 'FACIL' ? 'success' : value === 'MEDIA' ? 'warn' : 'danger';
  }
}
