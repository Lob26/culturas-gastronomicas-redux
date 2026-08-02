import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';

/** Página 404. En 2023 no existía ruta comodín: una dirección errónea dejaba la pantalla en blanco. */
@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [RouterLink, ButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-xl px-4 py-24 text-center">
      <p class="text-6xl font-semibold text-surface-300 dark:text-surface-600">404</p>
      <h1 class="mt-4 text-2xl font-medium">Esta página no existe</h1>
      <p class="mt-2 text-surface-500">Puede que el enlace esté mal o que el contenido se haya movido.</p>
      <p-button class="mt-8 inline-block" label="Volver al inicio" icon="pi pi-home" routerLink="/" />
    </section>
  `,
})
export class NotFoundPage {}
