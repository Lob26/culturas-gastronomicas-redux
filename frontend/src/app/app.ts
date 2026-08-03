import { ChangeDetectionStrategy, Component, DOCUMENT, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { AuthService } from './core/auth.service';

/**
 * Cáscara de la aplicación: navegación, sesión y modo oscuro.
 *
 * <p>El interruptor de modo oscuro pone o quita una sola clase en el elemento
 * raíz. Esa clase es a la vez el {@code darkModeSelector} de PrimeNG y el
 * {@code @custom-variant dark} de Tailwind, de modo que un único cambio mueve
 * los componentes y las utilidades juntos. Si cada uno usara su propio
 * selector, acabaría habiendo dos modos oscuros independientes.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a
      href="#contenido"
      class="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50
             focus:rounded focus:bg-primary-500 focus:px-4 focus:py-2 focus:text-white"
    >
      Saltar al contenido
    </a>

    <header class="border-b border-surface-200 dark:border-surface-700">
      <nav class="mx-auto flex max-w-6xl items-center gap-6 px-4 py-3">
        <a routerLink="/" class="font-semibold tracking-tight">Culturas Gastronómicas</a>

        <ul class="flex flex-1 gap-4 text-sm">
          <li>
            <a
              routerLink="/culturas"
              routerLinkActive="text-primary-500 font-medium"
              class="hover:text-primary-500"
            >Culturas</a>
          </li>
          <li>
            <a
              routerLink="/recetas"
              routerLinkActive="text-primary-500 font-medium"
              class="hover:text-primary-500"
            >Recetas</a>
          </li>
          <li>
            <a
              routerLink="/buscar"
              routerLinkActive="text-primary-500 font-medium"
              class="hover:text-primary-500"
            >Buscar</a>
          </li>
          <li>
            <a
              routerLink="/preguntar"
              routerLinkActive="text-primary-500 font-medium"
              class="hover:text-primary-500"
            >Preguntar</a>
          </li>
          @if (auth.isAuthenticated()) {
            <li>
              <a
                routerLink="/recetario"
                routerLinkActive="text-primary-500 font-medium"
                class="hover:text-primary-500"
              >Mi recetario</a>
            </li>
          }
        </ul>

        <p-button
          [icon]="'pi ' + (dark() ? 'pi-sun' : 'pi-moon')"
          severity="secondary"
          [text]="true"
          [ariaLabel]="dark() ? 'Cambiar a modo claro' : 'Cambiar a modo oscuro'"
          (onClick)="toggleDark()"
        />

        @if (auth.isAuthenticated()) {
          <span class="text-sm text-surface-500">{{ auth.displayName() }}</span>
          <p-button label="Salir" severity="secondary" [text]="true" (onClick)="auth.logout()" />
        } @else {
          <p-button label="Entrar" severity="secondary" [outlined]="true" routerLink="/entrar" />
        }
      </nav>
    </header>

    <main id="contenido">
      <router-outlet />
    </main>

    <footer class="mt-16 border-t border-surface-200 py-6 text-center text-xs text-surface-400 dark:border-surface-700">
      Reescritura 2026 · ISIS2603 Universidad de los Andes
    </footer>
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);

  protected readonly dark = signal(false);

  protected toggleDark(): void {
    this.dark.update((value) => !value);
    // classList.toggle sobre el elemento raíz: PrimeNG busca '.app-dark' por
    // darkModeSelector y Tailwind por @custom-variant, ambos apuntando aquí.
    this.document.documentElement.classList.toggle('app-dark', this.dark());
  }
}
