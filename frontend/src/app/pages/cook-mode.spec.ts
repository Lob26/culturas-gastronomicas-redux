import { describe, expect, it } from 'vitest';
import { formatClock, stepProgress } from './cook-mode.page';

/**
 * El temporizador del modo cocina.
 *
 * <p>Se prueban las funciones puras y no el componente entero: lo que puede
 * estar mal aquí es la aritmética, y montarla dentro de un TestBed la
 * comprobaría igual de bien a cambio de un test mucho más lento y capaz de
 * fallar por motivos que no tienen que ver con lo que mide.
 */
describe('formatClock', () => {
  it('rellena con ceros a la izquierda', () => {
    expect(formatClock(0)).toBe('00:00');
    expect(formatClock(5)).toBe('00:05');
    expect(formatClock(65)).toBe('01:05');
  });

  it('no reinicia la cuenta al pasar de una hora', () => {
    // 90 minutos son «90:00» y no «30:00»: un guiso largo es un caso real y
    // envolver a la hora mostraría un tiempo que no es el que falta.
    expect(formatClock(5400)).toBe('90:00');
  });

  it('trata los negativos como cero', () => {
    // Al terminar la cuenta atrás, un tick de más produciría «00:-1».
    expect(formatClock(-1)).toBe('00:00');
  });
});

describe('stepProgress', () => {
  it('cuenta desde 1, así que el primer paso ya muestra avance', () => {
    expect(stepProgress(0, 4)).toBe(25);
    expect(stepProgress(3, 4)).toBe(100);
  });

  it('devuelve 0 sin pasos en vez de NaN', () => {
    // Dividir por cero daría NaN, que la barra de PrimeNG pinta vacía sin
    // avisar: el fallo sería invisible.
    expect(stepProgress(0, 0)).toBe(0);
  });
});
