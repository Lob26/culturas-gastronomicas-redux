/**
 * Tipos del contrato con la API.
 *
 * <p>Escritos a mano por ahora; en la Fase 5 los genera Orval a partir del
 * OpenAPI del backend y CI falla si el resultado difiere de lo versionado. Esa
 * comprobación existe por lo que pasó en 2023: el frontend llamaba a
 * `/categories/{nombre}` contra un backend que sólo aceptaba `?id=`, y nada lo
 * detectaba porque ningún test cruzaba la frontera.
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
