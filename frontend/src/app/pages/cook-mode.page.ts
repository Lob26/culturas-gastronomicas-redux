import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { ProgressBarModule } from 'primeng/progressbar';
import { CatalogService } from '../core/catalog.service';

/**
 * Segundos a `mm:ss`.
 *
 * <p>Exportada y pura para poder comprobarla sin montar el componente: el
 * temporizador es lo único de esta pantalla que puede estar mal de una forma
 * que nadie note hasta que alguien esté cocinando.
 */
export function formatClock(seconds: number): string {
  // Los negativos se tratan como cero: al terminar la cuenta atrás, un tick de
  // más produciría "-1" y se pintaría "00:-1".
  const safe = Math.max(0, Math.floor(seconds));
  const minutes = Math.floor(safe / 60);
  return `${String(minutes).padStart(2, '0')}:${String(safe % 60).padStart(2, '0')}`;
}

/** Porcentaje de avance del paso actual, contando desde 1. */
export function stepProgress(index: number, total: number): number {
  // Sin la guarda, una receta sin pasos divide por cero y la barra recibe NaN,
  // que PrimeNG pinta como una barra vacía sin avisar de nada.
  return total > 0 ? ((index + 1) / total) * 100 : 0;
}

/**
 * Modo cocina: un paso a la vez, con temporizador y pantalla siempre encendida.
 *
 * <p>Sólo es posible porque los pasos volvieron a ser filas. En 2023 las
 * instrucciones eran un único {@code @Lob} de texto corrido, así que no había
 * forma de saber dónde empieza un paso ni cuánto dura.
 */
@Component({
  selector: 'app-cook-mode',
  standalone: true,
  imports: [RouterLink, ButtonModule, ProgressBarModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (recipe.value(); as r) {
      <section class="mx-auto flex min-h-screen max-w-3xl flex-col px-4 py-6">
        <header class="flex items-center justify-between">
          <a [routerLink]="['/recetas', r.slug]" class="text-sm text-surface-500 hover:underline">
            <i class="pi pi-arrow-left"></i> Salir del modo cocina
          </a>
          <span class="text-sm text-surface-500">
            Paso {{ index() + 1 }} de {{ r.steps.length }}
          </span>
        </header>

        <!-- p-progressbar en minúsculas: PrimeNG 22 declara
             'p-progressbar, p-progress-bar' y ya no acepta el camelCase
             p-progressBar que usaban las versiones anteriores. -->
        <p-progressbar
          class="mt-4 block"
          [value]="progress()"
          [showValue]="false"
          styleClass="h-2!"
        />

        <div class="flex flex-1 flex-col justify-center py-10">
          <p class="text-2xl leading-relaxed">{{ current()?.instruction }}</p>

          @if (current()?.durationSeconds) {
            <div class="mt-8 flex items-center gap-4">
              <span class="font-mono text-5xl tabular-nums" [class.text-red-500]="remaining() === 0">
                {{ formatted() }}
              </span>
              @if (!running()) {
                <p-button label="Iniciar" icon="pi pi-play" (onClick)="start()" />
              } @else {
                <p-button label="Pausar" icon="pi pi-pause" severity="secondary" (onClick)="pause()" />
              }
              <p-button label="Reiniciar" icon="pi pi-refresh" [text]="true" (onClick)="reset()" />
            </div>
          }
        </div>

        <footer class="flex justify-between gap-3 pb-6">
          <p-button
            label="Anterior"
            icon="pi pi-chevron-left"
            severity="secondary"
            [disabled]="index() === 0"
            (onClick)="previous()"
          />
          <p-button
            [label]="index() === r.steps.length - 1 ? 'Terminar' : 'Siguiente'"
            icon="pi pi-chevron-right"
            iconPos="right"
            (onClick)="next(r.steps.length)"
          />
        </footer>
      </section>
    }
  `,
})
export class CookModePage {
  private readonly catalog = inject(CatalogService);
  private readonly destroyRef = inject(DestroyRef);

  readonly slug = input<string>();

  protected readonly recipe = this.catalog.recipe(computed(() => this.slug()));
  protected readonly index = signal(0);
  protected readonly remaining = signal(0);
  protected readonly running = signal(false);

  protected readonly current = computed(() => this.recipe.value()?.steps[this.index()]);
  protected readonly progress = computed(() =>
    stepProgress(this.index(), this.recipe.value()?.steps.length ?? 0),
  );
  protected readonly formatted = computed(() => formatClock(this.remaining()));

  private ticker: ReturnType<typeof setInterval> | null = null;
  private wakeLock: WakeLockSentinel | null = null;

  constructor() {
    // afterNextRender sólo se ejecuta en el navegador, así que aquí no hace
    // falta comprobar la plataforma: setInterval y Wake Lock no existen
    // durante el renderizado en servidor y tocarlos allí tumbaría el render.
    afterNextRender(() => this.requestWakeLock());

    this.destroyRef.onDestroy(() => {
      this.stopTicker();
      void this.wakeLock?.release();
    });
  }

  protected start(): void {
    const duration = this.current()?.durationSeconds;
    if (!duration) {
      return;
    }
    if (this.remaining() === 0) {
      this.remaining.set(duration);
    }
    this.running.set(true);
    this.stopTicker();
    this.ticker = setInterval(() => {
      const left = this.remaining() - 1;
      this.remaining.set(Math.max(0, left));
      if (left <= 0) {
        this.pause();
      }
    }, 1000);
  }

  protected pause(): void {
    this.running.set(false);
    this.stopTicker();
  }

  protected reset(): void {
    this.pause();
    this.remaining.set(this.current()?.durationSeconds ?? 0);
  }

  protected next(total: number): void {
    if (this.index() < total - 1) {
      this.index.update((value) => value + 1);
      this.reset();
    }
  }

  protected previous(): void {
    if (this.index() > 0) {
      this.index.update((value) => value - 1);
      this.reset();
    }
  }

  private stopTicker(): void {
    if (this.ticker) {
      clearInterval(this.ticker);
      this.ticker = null;
    }
  }

  /** Evita que la pantalla se apague mientras se cocina con las manos ocupadas. */
  private async requestWakeLock(): Promise<void> {
    try {
      // No está en todos los navegadores y requiere contexto seguro; si no
      // está disponible el modo cocina sigue funcionando, sólo que la pantalla
      // puede apagarse.
      this.wakeLock = (await navigator.wakeLock?.request('screen')) ?? null;
    } catch {
      this.wakeLock = null;
    }
  }
}
