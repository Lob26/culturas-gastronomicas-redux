import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { RatingModule } from 'primeng/rating';
import { TextareaModule } from 'primeng/textarea';
import { AuthService } from '../core/auth.service';
import { SocialService } from '../core/social.service';

/**
 * Valoraciones de una receta: las de todos, y la tuya.
 *
 * <p>Se separa del detalle de la receta porque tiene su propio ciclo de vida
 * —se recarga al votar, sin tocar la receta— y porque el detalle ya era largo.
 *
 * <p>A quien no ha entrado se le enseñan las valoraciones y un enlace para
 * entrar, no un formulario que fallará con 401 al enviarlo. Un control que
 * parece disponible y luego rechaza es peor que uno que dice desde el principio
 * lo que hace falta.
 */
@Component({
  selector: 'app-ratings-section',
  standalone: true,
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    RouterLink,
    ButtonModule,
    MessageModule,
    RatingModule,
    TextareaModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mt-10 border-t border-surface-200 pt-8 dark:border-surface-700">
      <header class="flex flex-wrap items-baseline justify-between gap-3">
        <h2 class="text-xl font-medium">Valoraciones</h2>
        @if (count() > 0) {
          <p class="text-sm text-surface-500">
            <strong class="text-lg text-surface-800 dark:text-surface-100">
              {{ average() | number: '1.1-1' }}
            </strong>
            de 5 · {{ count() }} {{ count() === 1 ? 'valoración' : 'valoraciones' }}
          </p>
        }
      </header>

      @if (auth.isAuthenticated()) {
        <div class="mt-5 rounded-xl border border-surface-200 p-4 dark:border-surface-700">
          <p class="text-sm font-medium">Tu valoración</p>

          <p-rating
            class="mt-2 block"
            [ngModel]="score()"
            (ngModelChange)="score.set($event)"
            [disabled]="saving()"
          />

          <textarea
            pTextarea
            rows="3"
            class="mt-3 w-full"
            placeholder="¿Qué tal te quedó? (opcional)"
            aria-label="Comentario"
            [ngModel]="comment()"
            (ngModelChange)="comment.set($event)"
          ></textarea>

          <div class="mt-3 flex items-center gap-2">
            <p-button
              label="Guardar"
              size="small"
              [loading]="saving()"
              [disabled]="!score()"
              (onClick)="save()"
            />
            @if (mine()) {
              <p-button
                label="Retirar"
                size="small"
                severity="secondary"
                [text]="true"
                [disabled]="saving()"
                (onClick)="remove()"
              />
            }
          </div>

          @if (error()) {
            <p-message severity="error" class="mt-3 block">{{ error() }}</p-message>
          }
        </div>
      } @else {
        <p class="mt-4 text-sm text-surface-500">
          <a routerLink="/entrar" class="text-primary-500 underline">Entra</a>
          para valorar esta receta.
        </p>
      }

      @if (ratings.isLoading()) {
        <p class="mt-6 text-sm text-surface-400">Cargando valoraciones…</p>
      } @else if (ratings.value()?.length) {
        <ul class="mt-6 space-y-4">
          @for (rating of ratings.value()!; track rating.author + rating.createdAt) {
            <li class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <strong class="text-sm">{{ rating.author }}</strong>
                <span class="text-xs text-surface-400">{{ rating.createdAt | date: 'mediumDate' }}</span>
              </div>
              <p-rating class="mt-1 block" [ngModel]="rating.score" [readonly]="true" />
              @if (rating.comment) {
                <p class="mt-2 text-sm text-surface-600 dark:text-surface-300">{{ rating.comment }}</p>
              }
            </li>
          }
        </ul>
      } @else {
        <p class="mt-6 text-sm text-surface-500">Nadie ha valorado esta receta todavía.</p>
      }
    </section>
  `,
})
export class RatingsSection {
  protected readonly auth = inject(AuthService);
  private readonly social = inject(SocialService);

  readonly slug = input.required<string>();
  /** Agregados que ya vienen en el detalle; evitan recalcularlos en el cliente. */
  readonly average = input<number>(0);
  readonly count = input<number>(0);

  protected readonly ratings = this.social.ratings(computed(() => this.slug()));

  protected readonly score = signal(0);
  protected readonly comment = signal('');
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Si el usuario ya valoró, para ofrecer «retirar» sólo cuando hay algo que retirar. */
  protected readonly mine = computed(() =>
    this.ratings.value()?.some((rating) => rating.author === this.auth.displayName()) ?? false,
  );

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.social.rate(this.slug(), this.score(), this.comment() || null);
    } catch {
      // El detalle exacto vive en el ProblemDetail, pero para el usuario lo
      // accionable es que no se guardó y puede reintentar.
      this.error.set('No se pudo guardar tu valoración. Inténtalo otra vez.');
    } finally {
      this.saving.set(false);
    }
  }

  protected async remove(): Promise<void> {
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.social.removeRating(this.slug());
      this.score.set(0);
      this.comment.set('');
    } catch {
      this.error.set('No se pudo retirar tu valoración.');
    } finally {
      this.saving.set(false);
    }
  }
}
