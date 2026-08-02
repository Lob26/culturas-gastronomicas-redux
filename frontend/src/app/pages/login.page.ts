import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { AuthService } from '../core/auth.service';
import type { ProblemDetail } from '../core/api.types';

/**
 * Acceso y registro en la misma pantalla.
 *
 * <p>Reactive Forms y no Signal Forms: los componentes de entrada de PrimeNG
 * siguen basados en ControlValueAccessor, y Signal Forms tendría que pasar por
 * su capa /compat. Con el modo sin zona hay que tener presente que los cambios
 * del modelo de un formulario reactivo <strong>no</strong> programan detección
 * de cambios por sí solos: por eso los estados de la interfaz —enviando, error—
 * viven en señales y no se leen del formulario.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, ButtonModule, InputTextModule, MessageModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-md px-4 py-16">
      <h1 class="text-2xl font-semibold">{{ isRegistering() ? 'Crear cuenta' : 'Entrar' }}</h1>
      <p class="mt-2 text-sm text-surface-500">
        El registro es inmediato: no pedimos confirmación por correo.
      </p>

      @if (error(); as message) {
        <p-message severity="error" class="mt-4 block">{{ message }}</p-message>
      }

      <form [formGroup]="form" (ngSubmit)="submit()" class="mt-6 space-y-4">
        <div>
          <label for="username" class="mb-1 block text-sm font-medium">Usuario</label>
          <input pInputText id="username" formControlName="username" class="w-full" autocomplete="username" />
        </div>

        @if (isRegistering()) {
          <div>
            <label for="displayName" class="mb-1 block text-sm font-medium">Nombre visible</label>
            <input pInputText id="displayName" formControlName="displayName" class="w-full" />
          </div>
        }

        <div>
          <label for="password" class="mb-1 block text-sm font-medium">Contraseña</label>
          <input
            pInputText
            type="password"
            id="password"
            formControlName="password"
            class="w-full"
            [attr.autocomplete]="isRegistering() ? 'new-password' : 'current-password'"
          />
        </div>

        <p-button
          type="submit"
          [label]="isRegistering() ? 'Crear cuenta' : 'Entrar'"
          [loading]="submitting()"
          [disabled]="form.invalid"
          class="w-full"
        />
      </form>

      <button type="button" class="mt-6 text-sm text-primary-500 underline" (click)="toggleMode()">
        {{ isRegistering() ? '¿Ya tienes cuenta? Entra' : '¿No tienes cuenta? Crea una' }}
      </button>
    </section>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  protected readonly isRegistering = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9_.-]{2,39}$/)]],
    displayName: [''],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  protected toggleMode(): void {
    this.isRegistering.update((value) => !value);
    this.error.set(null);
  }

  protected async submit(): Promise<void> {
    if (this.form.invalid) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);

    const { username, displayName, password } = this.form.getRawValue();
    try {
      if (this.isRegistering()) {
        await this.auth.register(username, displayName || username, password);
      } else {
        await this.auth.login(username, password);
      }
      await this.router.navigate(['/']);
    } catch (cause: unknown) {
      // El backend responde en RFC 9457, así que se puede mostrar su `detail`
      // en lugar de un mensaje genérico inventado por el cliente.
      const problem = (cause as { error?: ProblemDetail })?.error;
      this.error.set(problem?.detail ?? 'No pudimos completar la operación. Inténtalo de nuevo.');
    } finally {
      this.submitting.set(false);
    }
  }
}
