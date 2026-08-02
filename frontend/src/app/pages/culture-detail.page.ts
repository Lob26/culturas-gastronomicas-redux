import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TagModule } from 'primeng/tag';
import { SkeletonModule } from 'primeng/skeleton';
import { MessageModule } from 'primeng/message';
import { CatalogService } from '../core/catalog.service';

@Component({
  selector: 'app-culture-detail',
  standalone: true,
  imports: [RouterLink, TagModule, SkeletonModule, MessageModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-5xl px-4 py-8">
      @if (culture.isLoading()) {
        <p-skeleton height="2.5rem" width="50%" />
      } @else if (culture.error()) {
        <p-message severity="error">No encontramos esta cultura.</p-message>
        <a routerLink="/culturas" class="mt-4 inline-block text-primary-500 underline">Ver todas</a>
      } @else if (culture.value(); as c) {
        <h1 class="text-3xl font-semibold tracking-tight">{{ c.name }}</h1>
        <p class="mt-3 max-w-2xl text-surface-600 dark:text-surface-300">{{ c.description }}</p>

        @if (c.countries.length) {
          <div class="mt-4 flex flex-wrap gap-2">
            @for (country of c.countries; track country.id) {
              <p-tag [value]="country.name + ' · ' + country.iso2" severity="secondary" />
            }
          </div>
        }

        <h2 class="mt-10 text-xl font-medium">Recetas de esta cocina</h2>
        @if (recipes.isLoading()) {
          <p-skeleton height="8rem" class="mt-4 block" />
        } @else if (recipes.value(); as page) {
          @if (!page.content.length) {
            <p class="mt-3 text-surface-500">Todavía no hay recetas registradas para esta cocina.</p>
          } @else {
            <ul class="mt-4 grid gap-3 sm:grid-cols-2">
              @for (recipe of page.content; track recipe.slug) {
                <li>
                  <a
                    [routerLink]="['/recetas', recipe.slug]"
                    class="block rounded-lg border border-surface-200 p-4 transition
                           hover:border-primary-400 dark:border-surface-700"
                  >
                    <span class="font-medium">{{ recipe.name }}</span>
                    @if (recipe.prepTimeMinutes) {
                      <span class="ml-2 text-xs text-surface-400">{{ recipe.prepTimeMinutes }} min</span>
                    }
                  </a>
                </li>
              }
            </ul>
          }
        }
      }
    </section>
  `,
})
export class CultureDetailPage {
  private readonly catalog = inject(CatalogService);

  readonly slug = input<string>();

  private readonly slugSignal = computed(() => this.slug());
  protected readonly page = signal(0);

  protected readonly culture = this.catalog.culture(this.slugSignal);
  protected readonly recipes = this.catalog.recipesOfCulture(this.slugSignal, this.page);
}
