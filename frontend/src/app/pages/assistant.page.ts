import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { environment } from '../../environments/environment';
import { AuthService } from '../core/auth.service';
import type { HitType } from '../core/api.types';

interface Source {
  slug: string;
  nombre: string;
  tipo: HitType;
}

/**
 * Preguntas en lenguaje natural sobre el catálogo.
 *
 * <p>Se consume con {@code fetch} y no con {@code EventSource}, y no es una
 * preferencia: EventSource <strong>no puede enviar cabeceras</strong>, así que
 * no hay forma de mandarle el Bearer que este endpoint exige. La alternativa
 * sería pasar el token por la query string, que lo dejaría escrito en los logs
 * del servidor y en el historial del navegador.
 *
 * <p>Las fuentes se pintan antes que la respuesta, en cuanto llega su evento.
 * Así el usuario ve de dónde va a salir lo que lea mientras se genera, que con
 * un modelo local tarda lo suyo.
 */
@Component({
  selector: 'app-assistant',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, MessageModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-3xl px-4 py-8">
      <header class="mb-6">
        <h1 class="text-3xl font-semibold tracking-tight">Preguntar</h1>
        <p class="mt-1 text-surface-500 dark:text-surface-400">
          Responde sólo con lo que hay en el catálogo, y cita de dónde lo saca.
        </p>
      </header>

      @if (!auth.isAuthenticated()) {
        <p-message severity="info">
          <a routerLink="/entrar" class="underline">Entra</a>
          para preguntar: la respuesta la genera un modelo en tu máquina y ocupa CPU.
        </p-message>
      } @else {
        <form class="flex gap-2" (submit)="ask($event)">
          <input
            pInputText
            class="flex-1"
            placeholder="¿Qué lleva la carbonara?"
            aria-label="Pregunta"
            [ngModel]="question()"
            (ngModelChange)="question.set($event)"
            name="pregunta"
            [disabled]="running()"
          />
          <p-button
            type="submit"
            label="Preguntar"
            [loading]="running()"
            [disabled]="question().trim().length < 3"
          />
        </form>

        @if (error()) {
          <p-message severity="warn" class="mt-4 block">{{ error() }}</p-message>
        }

        @if (sources().length) {
          <section class="mt-6">
            <h2 class="text-xs uppercase tracking-wide text-surface-400">Fuentes</h2>
            <ol class="mt-2 flex flex-wrap gap-2">
              @for (source of sources(); track source.slug; let i = $index) {
                <li>
                  <a
                    [routerLink]="source.tipo === 'RECIPE' ? ['/recetas', source.slug] : ['/culturas', source.slug]"
                    class="rounded-full border border-surface-200 px-3 py-1 text-sm transition
                           hover:border-primary-400 dark:border-surface-700"
                  >[{{ i + 1 }}] {{ source.nombre }}</a>
                </li>
              }
            </ol>
          </section>
        }

        @if (answer() || running()) {
          <article
            class="mt-6 rounded-xl border border-surface-200 p-5 dark:border-surface-700"
            aria-live="polite"
          >
            <p class="whitespace-pre-wrap text-surface-700 dark:text-surface-200">{{ answer() }}</p>
            @if (running()) {
              <span class="mt-2 inline-block animate-pulse text-sm text-surface-400">
                escribiendo…
              </span>
            }
          </article>
        }
      }
    </section>
  `,
})
export class AssistantPage {
  protected readonly auth = inject(AuthService);

  protected readonly question = signal('');
  protected readonly answer = signal('');
  protected readonly sources = signal<Source[]>([]);
  protected readonly running = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Corta el flujo si el usuario se va a otra pantalla. Sin esto la lectura
   * seguiría viva tras destruir el componente, escribiendo en señales que ya no
   * pinta nadie y manteniendo abierta la conexión con el servidor.
   */
  private controller: AbortController | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.controller?.abort());
  }

  protected async ask(event: Event): Promise<void> {
    event.preventDefault();

    this.controller?.abort();
    this.controller = new AbortController();

    this.running.set(true);
    this.answer.set('');
    this.sources.set([]);
    this.error.set(null);

    try {
      const response = await fetch(
        `${environment.apiUrl}/asistente/preguntar?q=${encodeURIComponent(this.question())}`,
        {
          headers: { Authorization: `Bearer ${this.auth.token()}` },
          signal: this.controller.signal,
        },
      );

      if (response.status === 503) {
        this.error.set('El asistente necesita Ollama en marcha. Arráncalo con `task llm:up`.');
        return;
      }
      if (!response.ok || !response.body) {
        this.error.set('No se pudo preguntar. Inténtalo otra vez.');
        return;
      }

      await this.consume(response.body);
    } catch (e) {
      // Abortar al navegar no es un fallo que haya que enseñar.
      if ((e as Error)?.name !== 'AbortError') {
        this.error.set('Se cortó la conexión mientras se generaba la respuesta.');
      }
    } finally {
      this.running.set(false);
    }
  }

  /**
   * Lee el flujo SSE.
   *
   * <p>Se acumula en un buffer y sólo se procesan los bloques terminados en
   * línea en blanco: un `read()` no respeta las fronteras de los eventos y
   * puede cortar a mitad de un `data:`. Procesar lo que llega tal cual parece
   * funcionar hasta que un evento se parte en dos y aparece texto truncado o
   * JSON ilegible.
   */
  private async consume(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader();
    // TextDecoder a mano en vez de pipeThrough(new TextDecoderStream()): la
    // firma de TextDecoderStream no encaja con ReadableWritablePair bajo la
    // configuración de TypeScript del proyecto. `stream: true` es lo que
    // importa aquí — sin él, un carácter multibyte partido entre dos lecturas
    // se decodifica como un rombo de reemplazo, y en español eso ocurre en
    // cuanto aparece una tilde.
    const decoder = new TextDecoder();
    let buffer = '';

    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });

      let separator = buffer.indexOf('\n\n');
      while (separator !== -1) {
        this.handleEvent(buffer.slice(0, separator));
        buffer = buffer.slice(separator + 2);
        separator = buffer.indexOf('\n\n');
      }
    }
  }

  private handleEvent(block: string): void {
    const lines = block.split('\n');
    const name = lines.find((line) => line.startsWith('event:'))?.slice(6).trim();

    // Un evento puede traer varias líneas `data:`, y se concatenan. El texto
    // del modelo puede contener saltos de línea.
    const data = lines
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5))
      .join('\n');

    switch (name) {
      case 'fuentes':
        try {
          this.sources.set(JSON.parse(data) as Source[]);
        } catch {
          // Fuentes ilegibles no deben impedir leer la respuesta.
        }
        break;
      case 'texto':
        this.answer.update((current) => current + data);
        break;
      case 'error':
        this.error.set(data);
        break;
    }
  }
}
