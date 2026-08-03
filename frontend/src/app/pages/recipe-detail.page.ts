import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { SkeletonModule } from 'primeng/skeleton';
import { MessageModule } from 'primeng/message';
import { CatalogService } from '../core/catalog.service';
import { AuthService } from '../core/auth.service';
import { SocialService } from '../core/social.service';
import { RatingsSection } from './ratings.section';

@Component({
  selector: 'app-recipe-detail',
  standalone: true,
  imports: [
    RouterLink,
    ButtonModule,
    TagModule,
    SkeletonModule,
    MessageModule,
    RatingsSection,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-4xl px-4 py-8">
      @if (recipe.isLoading()) {
        <p-skeleton height="2.5rem" width="60%" />
        <p-skeleton height="18rem" class="mt-6 block" />
      } @else if (recipe.error()) {
        <p-message severity="error">No encontramos esta receta.</p-message>
        <a routerLink="/recetas" class="mt-4 inline-block text-primary-500 underline">Volver a las recetas</a>
      } @else if (recipe.value(); as r) {
        <nav class="mb-3 text-sm text-surface-500">
          <a [routerLink]="['/culturas', r.cultureSlug]" class="hover:underline">{{ r.cultureName }}</a>
        </nav>

        <div class="flex flex-wrap items-start justify-between gap-4">
          <h1 class="text-3xl font-semibold tracking-tight">{{ r.name }}</h1>
          <div class="flex gap-2">
            @if (auth.isAuthenticated()) {
              <p-button
                [icon]="isFavorite(r.id) ? 'pi pi-bookmark-fill' : 'pi pi-bookmark'"
                severity="secondary"
                [outlined]="true"
                [loading]="savingFavorite()"
                [ariaLabel]="isFavorite(r.id) ? 'Quitar del recetario' : 'Guardar en el recetario'"
                (onClick)="toggleFavorite(r.id)"
              />
            }
            <p-button
              label="Modo cocina"
              icon="pi pi-play"
              [routerLink]="['/recetas', r.slug, 'cocinar']"
            />
          </div>
        </div>

        <p class="mt-3 text-surface-600 dark:text-surface-300">{{ r.description }}</p>

        <!--
          Atribución: el catálogo es colaborativo y saber de quién viene una
          receta forma parte de poder juzgarla. Las sembradas no las creó nadie,
          así que no llevan nada en vez de un «añadido por null».
        -->
        @if (r.createdBy) {
          <p class="mt-2 text-xs uppercase tracking-wide text-surface-400">
            Añadido por {{ r.createdBy }}
          </p>
        }

        <div class="mt-4 flex flex-wrap gap-2">
          @if (r.difficulty) {
            <p-tag [value]="difficultyLabel(r.difficulty)" [severity]="difficultySeverity(r.difficulty)" />
          }
          @if (r.prepTimeMinutes) {
            <p-tag [value]="r.prepTimeMinutes + ' min'" severity="secondary" />
          }
          @if (r.servings) {
            <p-tag [value]="r.servings + ' raciones'" severity="secondary" />
          }
        </div>

        @if (r.images.length) {
          <img
            [src]="r.images[0]"
            [alt]="r.name"
            class="mt-6 h-72 w-full rounded-xl object-cover"
            (error)="imageFailed.set(true)"
          />
        }

        @if (r.ingredients.length) {
          <h2 class="mt-8 text-xl font-medium">Ingredientes</h2>
          <ul class="mt-3 space-y-1">
            @for (ingredient of r.ingredients; track ingredient.position) {
              <li class="flex gap-2 text-surface-700 dark:text-surface-300">
                <span class="text-surface-400">·</span>
                <span>
                  @if (ingredient.quantity) {
                    <strong>{{ ingredient.quantity }} {{ ingredient.unit }}</strong>
                  }
                  {{ ingredient.name }}
                </span>
              </li>
            }
          </ul>
        }

        <h2 class="mt-8 text-xl font-medium">Preparación</h2>
        <ol class="mt-3 space-y-4">
          @for (step of r.steps; track step.position) {
            <li class="flex gap-4">
              <span
                class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full
                       bg-primary-500 text-sm font-semibold text-white"
              >{{ step.position }}</span>
              <div>
                <p class="text-surface-700 dark:text-surface-300">{{ step.instruction }}</p>
                @if (step.durationSeconds) {
                  <span class="mt-1 inline-block text-xs uppercase tracking-wide text-surface-400">
                    <i class="pi pi-clock"></i> {{ minutes(step.durationSeconds) }} min
                  </span>
                }
              </div>
            </li>
          }
        </ol>

        <app-ratings-section
          [slug]="r.slug"
          [average]="r.ratingAverage"
          [count]="r.ratingCount"
        />

        <!--
          «Recetas parecidas» sale de la vecindad de vectores, no de la misma
          cultura: lo interesante es que relacione un curry con un guiso de otro
          continente por cómo se cocinan, cosa que un filtro por cultura no
          puede hacer nunca.
        -->
        @if (similar.value()?.length) {
          <section class="mt-10 border-t border-surface-200 pt-8 dark:border-surface-700">
            <h2 class="text-xl font-medium">Recetas parecidas</h2>
            <div class="mt-4 grid gap-3 sm:grid-cols-2">
              @for (hit of similar.value()!; track hit.slug) {
                <a
                  [routerLink]="['/recetas', hit.slug]"
                  class="rounded-lg border border-surface-200 px-4 py-3 transition
                         hover:border-primary-400 hover:shadow dark:border-surface-700"
                >{{ hit.name }}</a>
              }
            </div>
          </section>
        }
      }
    </section>
  `,
})
export class RecipeDetailPage {
  private readonly catalog = inject(CatalogService);
  private readonly social = inject(SocialService);
  protected readonly auth = inject(AuthService);

  /**
   * Llega del parámetro de ruta gracias a withComponentInputBinding: no hace
   * falta inyectar ActivatedRoute ni suscribirse a paramMap.
   */
  readonly slug = input<string>();

  protected readonly imageFailed = signal(false);
  protected readonly recipe = this.catalog.recipe(computed(() => this.slug()));
  protected readonly similar = this.social.similar(computed(() => this.slug()));

  /** El recetario sólo se pide con sesión: sin ella el endpoint responde 401. */
  protected readonly favorites = this.social.favorites(this.auth.isAuthenticated);

  protected readonly savingFavorite = signal(false);

  protected isFavorite(recipeId: number): boolean {
    return this.favorites.value()?.some(
      (favorite) => favorite.targetType === 'RECIPE' && favorite.targetId === recipeId,
    ) ?? false;
  }

  /**
   * El servidor decide y devuelve el estado resultante; aquí no se calcula.
   * Con dos pestañas abiertas, mandar la acción deducida de lo que el cliente
   * cree saber acabaría invirtiendo el cambio hecho en la otra.
   */
  protected async toggleFavorite(recipeId: number): Promise<void> {
    this.savingFavorite.set(true);
    try {
      await this.social.toggleFavorite('RECIPE', recipeId);
    } finally {
      this.savingFavorite.set(false);
    }
  }

  protected minutes(seconds: number): number {
    return Math.round(seconds / 60);
  }

  protected difficultyLabel(value: string): string {
    return { FACIL: 'Fácil', MEDIA: 'Media', DIFICIL: 'Difícil' }[value] ?? value;
  }

  protected difficultySeverity(value: string): 'success' | 'warn' | 'danger' {
    return value === 'FACIL' ? 'success' : value === 'MEDIA' ? 'warn' : 'danger';
  }
}
