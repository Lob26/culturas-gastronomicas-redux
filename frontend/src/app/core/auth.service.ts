import { Injectable, computed, inject, signal, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../environments/environment';
import type { TokenResponse } from './api.types';

const STORAGE_KEY = 'culturas.session';

/**
 * Sesión del usuario, expuesta como señales.
 *
 * <p>El token se guarda en localStorage, que <strong>no existe durante el
 * renderizado en servidor</strong>. Tocarlo sin comprobar la plataforma lanza
 * ReferenceError y tumba el render antes de que llegue nada al navegador, así
 * que cada acceso pasa por {@link isPlatformBrowser}. En servidor la sesión
 * arranca vacía y se rehidrata en el cliente.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly isBrowser = isPlatformBrowser(inject(PLATFORM_ID));

  private readonly session = signal<TokenResponse | null>(this.restore());

  readonly user = computed(() => this.session());
  readonly isAuthenticated = computed(() => this.session() !== null);
  readonly displayName = computed(() => this.session()?.displayName ?? null);
  readonly isAdmin = computed(() => this.session()?.role === 'ROLE_ADMIN');

  /** Lo lee el interceptor en cada petición. */
  token(): string | null {
    return this.session()?.token ?? null;
  }

  async register(username: string, displayName: string, password: string): Promise<void> {
    const response = await firstValueFrom(
      this.http.post<TokenResponse>(`${environment.apiUrl}/auth/registro`, {
        username,
        displayName,
        password,
      }),
    );
    this.persist(response);
  }

  async login(username: string, password: string): Promise<void> {
    const response = await firstValueFrom(
      this.http.post<TokenResponse>(`${environment.apiUrl}/auth/acceso`, {
        username,
        password,
      }),
    );
    this.persist(response);
  }

  logout(): void {
    this.session.set(null);
    if (this.isBrowser) {
      localStorage.removeItem(STORAGE_KEY);
    }
  }

  private persist(response: TokenResponse): void {
    this.session.set(response);
    if (this.isBrowser) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(response));
    }
  }

  private restore(): TokenResponse | null {
    if (!this.isBrowser) {
      return null;
    }
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as TokenResponse;
      // Un token caducado se descarta al arrancar en vez de dejar que la
      // primera petición falle con un 401 desconcertante.
      return new Date(parsed.expiresAt) > new Date() ? parsed : null;
    } catch {
      // Contenido corrupto: se ignora y se empieza sin sesión.
      return null;
    }
  }
}
