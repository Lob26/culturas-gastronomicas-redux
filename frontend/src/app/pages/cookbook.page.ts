import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MessageModule } from 'primeng/message';
import { SkeletonModule } from 'primeng/skeleton';
import { CatalogService } from '../core/catalog.service';
import { AuthService } from '../core/auth.service';
import { SocialService } from '../core/social.service';

/**
 * El recetario personal.
 *
 * <p>La API de favoritos devuelve identificadores, no documentos: guarda
 * `targetType` y `targetId` y nada más. Para pintar nombres hace falta cruzarlos
 * con el catálogo, y se hace en el cliente sobre las páginas que ya se piden
 * —el catálogo de ejemplo son cinco recetas y cinco culturas—.
 *
 * <p>Con un catálogo de verdad esto no se sostiene y lo correcto sería que el
 * endpoint devolviera ya el nombre y el slug. Se deja anotado en vez de
 * fingir que escala.
 */
@Component({
  selector: 'app-cookbook',
  standalone: true,
  imports: [RouterLink, MessageModule, SkeletonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-4xl px-4 py-8">
      <header class="mb-6">
        <h1 class="text-3xl font-semibold tracking-tight">Mi recetario</h1>
        <p class="mt-1 text-surface-500 dark:text-surface-400">
          Lo que has guardado para tenerlo a mano.
        </p>
      </header>

      @if (!auth.isAuthenticated()) {
        <p-message severity="info">
          <a routerLink="/entrar" class="underline">Entra</a> para ver tu recetario.
        </p-message>
      } @else if (favorites.isLoading() || recipes.isLoading()) {
        @for (placeholder of [1, 2, 3]; track placeholder) {
          <p-skeleton height="3.5rem" borderRadius="0.5rem" styleClass="mb-3" />
        }
      } @else if (favorites.error()) {
        <p-message severity="error">No se pudo cargar tu recetario.</p-message>
      } @else if (!entries().length) {
        <p-message severity="info">
          Todavía no has guardado nada. Usa el marcador en cualquier receta.
        </p-message>
      } @else {
        <ul class="space-y-3">
          @for (entry of entries(); track entry.type + entry.slug) {
            <li>
              <a
                [routerLink]="entry.type === 'RECIPE' ? ['/recetas', entry.slug] : ['/culturas', entry.slug]"
                class="flex items-center justify-between rounded-lg border border-surface-200
                       px-4 py-3 transition hover:border-primary-400 hover:shadow
                       dark:border-surface-700"
              >
                <span class="font-medium">{{ entry.name }}</span>
                <span class="text-xs uppercase tracking-wide text-surface-400">
                  {{ entry.type === 'RECIPE' ? 'Receta' : 'Cultura' }}
                </span>
              </a>
            </li>
          }
        </ul>
      }
    </section>
  `,
})
export class CookbookPage {
  protected readonly auth = inject(AuthService);
  private readonly social = inject(SocialService);
  private readonly catalog = inject(CatalogService);

  protected readonly favorites = this.social.favorites(this.auth.isAuthenticated);

  private readonly page = signal(0);
  protected readonly recipes = this.catalog.recipes(this.page, 100);
  protected readonly cultures = this.catalog.cultures(this.page, 100);

  /**
   * Favoritos resueltos a nombre y slug.
   *
   * <p>Un favorito cuyo destino ya no existe simplemente no aparece, en lugar
   * de pintar una fila vacía o un enlace roto: borrar una receta se lleva su
   * favorito por FK, pero entre la carga de una lista y la de la otra cabe una
   * ventana en la que sí puede pasar.
   */
  protected readonly entries = computed(() => {
    const recipesBySlug = this.recipes.value()?.content ?? [];
    const culturesBySlug = this.cultures.value()?.content ?? [];

    return (this.favorites.value() ?? []).flatMap((favorite) => {
      const source = favorite.targetType === 'RECIPE' ? recipesBySlug : culturesBySlug;
      const match = source.find((item) => item.id === favorite.targetId);
      return match
        ? [{ type: favorite.targetType, slug: match.slug, name: match.name }]
        : [];
    });
  });
}
