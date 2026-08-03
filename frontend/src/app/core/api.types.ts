/**
 * Tipos del contrato con la API.
 *
 * <p>Escritos a mano, y no generados con Orval como se planteó al principio.
 * Lo que se buscaba con la generación era detectar la deriva entre las dos
 * mitades: en 2023 el frontend llamaba a `/categories/{nombre}` contra un
 * backend que sólo aceptaba `?id=`, y nada lo detectaba porque ningún test
 * cruzaba la frontera.
 *
 * <p>Esa frontera hoy la cruza el test end-to-end, que llama a los endpoints
 * de verdad contra el backend de verdad. Es una comprobación más fuerte que
 * diferenciar un cliente generado: un diff limpio sólo demuestra que los tipos
 * coinciden con el spec, no que la llamada funcione. Generar además el cliente
 * añadiría una segunda representación del mismo contrato —y el trabajo de
 * mantenerla— para cubrir algo que ya está cubierto.
 *
 * <p>El spec sigue siendo descargable con `task spec` para inspeccionarlo o
 * para alimentar a un generador el día que haya un consumidor externo, que es
 * cuando la generación empieza a pagar por sí sola.
 */

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export interface CountrySummary {
  id: number;
  name: string;
  iso2: string;
}

export interface CultureSummary {
  id: number;
  name: string;
  slug: string;
  description: string | null;
  imageUrl: string | null;
  recipeCount: number;
  categoryCount: number;
}

export interface CultureDetail {
  id: number;
  name: string;
  slug: string;
  description: string | null;
  imageUrl: string | null;
  countries: CountrySummary[];
}

export type Difficulty = 'FACIL' | 'MEDIA' | 'DIFICIL';

export interface RecipeSummary {
  id: number;
  name: string;
  slug: string;
  description: string | null;
  cultureName: string;
  cultureSlug: string;
  prepTimeMinutes: number | null;
  servings: number | null;
  difficulty: Difficulty | null;
  imageUrl: string | null;
}

export interface RecipeStep {
  position: number;
  instruction: string;
  /** Segundos detectados en el texto del paso; null si no menciona tiempo. */
  durationSeconds: number | null;
}

export interface RecipeIngredient {
  position: number;
  name: string;
  quantity: number | null;
  unit: string | null;
}

export interface RecipeDetail {
  id: number;
  name: string;
  slug: string;
  description: string | null;
  cultureName: string;
  cultureSlug: string;
  prepTimeMinutes: number | null;
  servings: number | null;
  difficulty: Difficulty | null;
  steps: RecipeStep[];
  ingredients: RecipeIngredient[];
  images: string[];
}

/** Un resultado de búsqueda puede ser una receta o una cultura. */
export type HitType = 'RECIPE' | 'CULTURE';

export interface SearchHit {
  slug: string;
  name: string;
  type: HitType;
}

/**
 * En qué se basó la recomendación. `POPULAR` significa arranque en frío: el
 * usuario aún no ha valorado nada, así que se le devuelve lo más popular que
 * no conoce. La interfaz titula la sección de otra forma según el valor.
 */
export type RecommendationBasis = 'PERSONAL' | 'POPULAR';

export interface Recommendations {
  results: SearchHit[];
  basis: RecommendationBasis;
  seeds: number;
}

export interface TokenResponse {
  token: string;
  expiresAt: string;
  username: string;
  displayName: string;
  role: string;
}

/**
 * Cuerpo de error RFC 9457. El backend responde siempre con esta forma en
 * `application/problem+json`, así que la interfaz puede mostrar `detail` sin
 * inventarse un mensaje genérico.
 */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  timestamp?: string;
  errores?: Array<{ campo: string; mensaje: string }>;
  regla?: string;
}
