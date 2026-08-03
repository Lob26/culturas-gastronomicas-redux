import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { AuthService } from '../core/auth.service';
import { SocialService } from '../core/social.service';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, ButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-4xl px-4 py-20 text-center">
      <h1 class="text-5xl font-semibold tracking-tight">Culturas Gastronómicas</h1>
      <p class="mx-auto mt-5 max-w-2xl text-lg text-surface-500 dark:text-surface-400">
        Un catálogo de cocinas del mundo: sus países, sus recetas paso a paso y los
        restaurantes donde probarlas.
      </p>

      <div class="mt-10 flex flex-wrap justify-center gap-3">
        <p-button label="Ver culturas" icon="pi pi-globe" routerLink="/culturas" />
        <p-button label="Ver recetas" icon="pi pi-book" severity="secondary" routerLink="/recetas" />
        <p-button label="Preguntar" icon="pi pi-comments" severity="secondary" [outlined]="true" routerLink="/preguntar" />
      </div>

      <!--
        Bloque personal. La home se prerrenderiza, así que en el HTML servido no
        hay nada de esto: aparece tras la hidratación, cuando ya se sabe si hay
        sesión. Es justo el motivo de que el recurso se quede en reposo sin
        sesión en vez de pedir y recibir un 401.
      -->
      @if (auth.isAuthenticated() && recommendations.value(); as recommended) {
        @if (recommended.results.length) {
          <section class="mt-16 text-left">
            <h2 class="text-xl font-medium">
              {{ recommended.basis === 'PERSONAL' ? 'Recomendado para ti' : 'Lo que más gusta' }}
            </h2>
            <!--
              El porqué se dice en voz alta. Sin esto, «recomendado para ti» a
              un usuario nuevo son resultados populares disfrazados de
              personales, y la primera impresión es que el sistema no acierta.
            -->
            <p class="mt-1 text-sm text-surface-500">
              @if (recommended.basis === 'PERSONAL') {
                A partir de {{ recommended.seeds }}
                {{ recommended.seeds === 1 ? 'receta que valoraste' : 'recetas que valoraste' }} alto.
              } @else {
                Todavía no sabemos qué te gusta. Valora alguna receta y esto cambia.
              }
            </p>

            <div class="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              @for (hit of recommended.results; track hit.slug) {
                <a
                  [routerLink]="hit.type === 'RECIPE' ? ['/recetas', hit.slug] : ['/culturas', hit.slug]"
                  class="rounded-lg border border-surface-200 px-4 py-3 text-left transition
                         hover:border-primary-400 hover:shadow dark:border-surface-700"
                >{{ hit.name }}</a>
              }
            </div>
          </section>
        }
      }

      <p class="mx-auto mt-16 max-w-xl text-sm text-surface-400">
        Reescritura 2026 de un proyecto de curso de 2023. El código original se conserva
        como referencia; este catálogo corrige sus errores de modelo y de datos.
      </p>
    </section>
  `,
})
export class HomePage {
  protected readonly auth = inject(AuthService);
  private readonly social = inject(SocialService);

  protected readonly recommendations = this.social.recommendations(this.auth.isAuthenticated);
}
