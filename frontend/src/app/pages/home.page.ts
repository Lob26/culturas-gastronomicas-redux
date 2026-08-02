import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';

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

      <div class="mt-10 flex justify-center gap-3">
        <p-button label="Ver culturas" icon="pi pi-globe" routerLink="/culturas" />
        <p-button label="Ver recetas" icon="pi pi-book" severity="secondary" routerLink="/recetas" />
      </div>

      <p class="mx-auto mt-16 max-w-xl text-sm text-surface-400">
        Reescritura 2026 de un proyecto de curso de 2023. El código original se conserva
        como referencia; este catálogo corrige sus errores de modelo y de datos.
      </p>
    </section>
  `,
})
export class HomePage {}
