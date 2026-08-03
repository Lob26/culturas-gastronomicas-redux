import { Routes } from '@angular/router';

/**
 * Rutas de la aplicación.
 *
 * <p>Todo con {@code loadComponent}: en 2023 los cuatro módulos de
 * funcionalidad se importaban de forma anticipada en AppModule, sin un solo
 * {@code loadChildren}, así que la primera carga descargaba la aplicación
 * entera.
 *
 * <p>El detalle recibe el slug como parámetro de ruta. El proyecto original
 * declaraba rutas anidadas con {@code :id} pero el componente lo leía de la
 * query string, y encima el padre no tenía {@code router-outlet}: las rutas
 * hijas nunca pintaban nada y `/culture/5/recipe/all` renderizaba un detalle
 * con id nulo.
 */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/home.page').then((m) => m.HomePage),
    title: 'Culturas Gastronómicas',
  },
  {
    path: 'culturas',
    loadComponent: () => import('./pages/culture-list.page').then((m) => m.CultureListPage),
    title: 'Culturas · Culturas Gastronómicas',
  },
  {
    path: 'culturas/:slug',
    loadComponent: () => import('./pages/culture-detail.page').then((m) => m.CultureDetailPage),
  },
  {
    path: 'recetas',
    loadComponent: () => import('./pages/recipe-list.page').then((m) => m.RecipeListPage),
    title: 'Recetas · Culturas Gastronómicas',
  },
  {
    path: 'recetas/:slug',
    loadComponent: () => import('./pages/recipe-detail.page').then((m) => m.RecipeDetailPage),
  },
  {
    path: 'recetas/:slug/cocinar',
    loadComponent: () => import('./pages/cook-mode.page').then((m) => m.CookModePage),
  },
  {
    path: 'buscar',
    loadComponent: () => import('./pages/search.page').then((m) => m.SearchPage),
    title: 'Buscar · Culturas Gastronómicas',
  },
  {
    path: 'preguntar',
    loadComponent: () => import('./pages/assistant.page').then((m) => m.AssistantPage),
    title: 'Preguntar · Culturas Gastronómicas',
  },
  {
    path: 'recetario',
    loadComponent: () => import('./pages/cookbook.page').then((m) => m.CookbookPage),
    title: 'Mi recetario · Culturas Gastronómicas',
  },
  {
    path: 'entrar',
    loadComponent: () => import('./pages/login.page').then((m) => m.LoginPage),
    title: 'Entrar · Culturas Gastronómicas',
  },
  // Comodín para que una dirección equivocada muestre algo. En 2023 no había
  // ruta ** ni página 404: se quedaba en blanco.
  {
    path: '**',
    loadComponent: () => import('./pages/not-found.page').then((m) => m.NotFoundPage),
    title: 'Página no encontrada',
  },
];
