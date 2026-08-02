import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideClientHydration, withEventReplay } from '@angular/platform-browser';
import { providePrimeNG } from 'primeng/config';
import Aura from '@primeuix/themes/aura';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';

/*
 * No aparece provideZonelessChangeDetection(): en Angular 21+ el modo sin zona
 * es el predeterminado y zone.js ni siquiera está instalado. La consecuencia
 * práctica es que todo estado que llegue a una plantilla tiene que hacerlo por
 * señales o por AsyncPipe; una mutación de un objeto plano no repinta nada.
 *
 * Relacionado y menos evidente: en la v22 los componentes sin `changeDetection`
 * explícito son OnPush por defecto. `Default` pasó a ser un alias deprecado de
 * `Eager`. En un proyecto nuevo no hay migración automática que lo avise, así
 * que la disciplina de señales no es opcional.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),

    // withComponentInputBinding enlaza los parámetros de ruta a @Input(), de
    // modo que un componente de detalle recibe su slug sin inyectar
    // ActivatedRoute ni suscribirse a nada.
    provideRouter(routes, withComponentInputBinding()),

    // withFetch está deprecado en la v22 porque FetchBackend ya es el backend
    // por defecto; se deja explícito porque el consumo de SSE de la Fase 5
    // depende de él (lectura incremental por partialText) y así queda dicho.
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),

    // withEventReplay guarda los clics ocurridos antes de que hidrate la
    // aplicación y los reproduce después, en vez de perderlos.
    provideClientHydration(withEventReplay()),

    providePrimeNG({
      theme: {
        preset: Aura,
        options: {
          // Debe coincidir con el @custom-variant dark de styles.css: un solo
          // interruptor mueve a la vez los componentes de PrimeNG y las
          // utilidades de Tailwind.
          darkModeSelector: '.app-dark',
          // Este orden es lo que permite que una clase de Tailwind gane a los
          // estilos del componente. La documentación lo dice literal: la capa
          // primeng va después de theme y base, y antes de las utilidades.
          cssLayer: {
            name: 'primeng',
            order: 'theme, base, primeng',
          },
        },
      },
      // La interfaz está en español; esto traduce los textos que PrimeNG pinta
      // por su cuenta (paginadores, mensajes de tabla vacía, calendarios).
      translation: {
        emptyMessage: 'No hay resultados',
        emptySearchMessage: 'No se encontraron coincidencias',
        emptyFilterMessage: 'Ningún resultado pasa el filtro',
        clear: 'Limpiar',
        apply: 'Aplicar',
        matchAll: 'Cumple todo',
        matchAny: 'Cumple alguno',
        accept: 'Sí',
        reject: 'No',
        choose: 'Elegir',
        upload: 'Subir',
        cancel: 'Cancelar',
        dayNames: ['Domingo', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado'],
        dayNamesShort: ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'],
        dayNamesMin: ['D', 'L', 'M', 'X', 'J', 'V', 'S'],
        monthNames: [
          'enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio',
          'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre',
        ],
        today: 'Hoy',
        weekHeader: 'Sem',
      },
    }),
  ],
};
