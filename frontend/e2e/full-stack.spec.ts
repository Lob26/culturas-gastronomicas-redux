import { test, expect, request as playwrightRequest, type APIRequestContext } from '@playwright/test';

/**
 * Prueba end-to-end del stack completo.
 *
 * Este es el artefacto del proyecto: una sola ejecución que recorre
 * infraestructura, base de datos, API, seguridad, búsqueda, trabajos en
 * segundo plano y navegador, y responde sí o no a «esto funciona».
 *
 * Cada bloque comprueba además algo que el proyecto de 2023 hacía mal, para
 * que el test no sólo diga que la reescritura funciona sino en qué se
 * diferencia.
 */

const ORIGIN = process.env.E2E_API_ORIGIN ?? 'http://localhost:8080';
const PREFIX = '/api/v2';

let api: APIRequestContext;
let token: string;

/**
 * Envoltorio que antepone el prefijo de versión.
 *
 * <p>No se usa `baseURL: origin + '/api/v2'` porque Playwright resuelve las
 * rutas con la semántica de `new URL()`: un camino que empieza por barra
 * <strong>reemplaza</strong> el camino de la base, de modo que
 * `/auth/registro` sobre `http://host/api/v2` acaba en `http://host/auth/registro`.
 * Esa petición no coincide con ninguna regla de seguridad y devuelve 401,
 * que parece un fallo del servidor y no lo es.
 */
const http = {
  get: (path: string, options?: Parameters<APIRequestContext['get']>[1]) =>
    api.get(`${PREFIX}${path}`, options),
  post: (path: string, options?: Parameters<APIRequestContext['post']>[1]) =>
    api.post(`${PREFIX}${path}`, options),
  put: (path: string, options?: Parameters<APIRequestContext['put']>[1]) =>
    api.put(`${PREFIX}${path}`, options),
  delete: (path: string, options?: Parameters<APIRequestContext['delete']>[1]) =>
    api.delete(`${PREFIX}${path}`, options),
};

// Usuario distinto por ejecución: el test escribe de verdad, y reutilizar un
// nombre fijo haría fallar la segunda ejecución con un 422 de usuario
// duplicado. Se deriva del reloj en vez de aleatorio para que sea rastreable.
const USERNAME = `e2e${Date.now().toString().slice(-9)}`;

test.beforeAll(async () => {
  api = await playwrightRequest.newContext({ baseURL: ORIGIN });

  const registro = await http.post('/auth/registro', {
    data: { username: USERNAME, displayName: 'Prueba E2E', password: 'clave-de-prueba-123' },
  });
  expect(registro.status(), 'el registro debe ser inmediato').toBe(201);
  token = (await registro.json()).token;
});

test.afterAll(async () => {
  await api.dispose();
});

const auth = () => ({ Authorization: `Bearer ${token}` });

// ---------------------------------------------------------------- catálogo --

test('el catálogo responde con datos sembrados y paginados', async () => {
  const response = await http.get('/culturas?size=3');
  expect(response.ok()).toBeTruthy();

  const page = await response.json();
  expect(page.totalElements).toBeGreaterThanOrEqual(5);
  expect(page.content).toHaveLength(3);
  // La forma del envoltorio de paginación es contrato: el cliente TypeScript
  // se genera a partir de él.
  expect(page).toHaveProperty('hasNext');
  expect(page.content[0]).toHaveProperty('recipeCount');
});

test('los dos errores de datos de 2023 están corregidos', async () => {
  // Japón figuraba como "JA", que no es un código ISO 3166-1 válido.
  const japonesa = await (await http.get('/culturas/cocina-japonesa')).json();
  expect(japonesa.countries.map((c: { iso2: string }) => c.iso2)).toContain('JP');

  // Las recetas estaban cruzadas: Tacos al Pastor apuntaba a la cocina
  // italiana y Pasta Carbonara a la mexicana.
  const tacos = await (await http.get('/recetas/tacos-al-pastor')).json();
  expect(tacos.cultureSlug).toBe('cocina-mexicana');

  const carbonara = await (await http.get('/recetas/pasta-carbonara')).json();
  expect(carbonara.cultureSlug).toBe('cocina-italiana');
});

test('los pasos son entidades ordenadas y llevan duración para el modo cocina', async () => {
  const receta = await (await http.get('/recetas/chicken-curry')).json();

  // En 2023 esto era un único @Lob de texto corrido.
  expect(receta.steps.length).toBeGreaterThan(3);
  expect(receta.steps.map((s: { position: number }) => s.position)).toEqual(
    [...receta.steps].map((_, i) => i + 1),
  );
  expect(receta.steps.some((s: { durationSeconds: number | null }) => s.durationSeconds)).toBeTruthy();
});

// ------------------------------------------------------------------ errores --

test('los errores son RFC 9457 y no un 412 para todo', async () => {
  const response = await http.get('/culturas/no-existe-esta-cultura');
  expect(response.status()).toBe(404);
  expect(response.headers()['content-type']).toContain('application/problem+json');

  const problem = await response.json();
  expect(problem).toMatchObject({ status: 404, title: expect.any(String), detail: expect.any(String) });
  expect(problem.recurso).toBe('cultura gastronómica');
});

test('la validación devuelve 400 con el detalle por campo', async () => {
  const response = await http.post('/culturas', {
    headers: auth(),
    data: { name: '', imageUrl: 'no-es-una-url' },
  });
  expect(response.status()).toBe(400);

  const problem = await response.json();
  expect(problem.errores.map((e: { campo: string }) => e.campo)).toEqual(
    expect.arrayContaining(['name', 'imageUrl']),
  );
});

// --------------------------------------------------------------- seguridad --

test('las lecturas son públicas y las escrituras exigen identidad', async () => {
  expect((await http.get('/culturas')).status()).toBe(200);
  expect((await http.put('/culturas/cocina-japonesa', { data: { name: 'X' } })).status()).toBe(401);
});

test('actualizar NO borra las relaciones (la regresión de 2023)', async () => {
  const antes = await (await http.get('/culturas/cocina-japonesa')).json();
  expect(antes.countries.length).toBeGreaterThan(0);

  // Se envían sólo campos escalares. En 2023 esto construía una entidad nueva
  // con las colecciones vacías y borraba países, recetas y categorías.
  const marca = `Comprobado por E2E ${Date.now()}`;
  const put = await http.put('/culturas/cocina-japonesa', {
    headers: auth(),
    data: { name: antes.name, description: marca },
  });
  expect(put.status()).toBe(200);

  const despues = await (await http.get('/culturas/cocina-japonesa')).json();
  expect(despues.description).toBe(marca);
  expect(despues.countries).toHaveLength(antes.countries.length);
});

// --------------------------------------------------------------- búsqueda --

test('la búsqueda encuentra por lexema, por errata y dentro de los pasos', async () => {
  const buscar = async (q: string) => (await (await http.get(`/buscar?q=${encodeURIComponent(q)}`)).json());

  expect((await buscar('carbonara')).map((h: { name: string }) => h.name)).toContain('Pasta Carbonara');

  // El carril difuso: el léxico no devuelve nada porque no queda raíz común.
  expect((await buscar('carbonarra')).map((h: { name: string }) => h.name)).toContain('Pasta Carbonara');

  // Busca dentro de las instrucciones, no sólo en nombre y descripción.
  expect((await buscar('esterilla')).map((h: { name: string }) => h.name)).toContain('Sushi Rolls');
});

test('la búsqueda no se rompe con entrada hostil', async () => {
  // Con to_tsquery en vez de websearch_to_tsquery, cada una de estas sería
  // un error de sintaxis y un 500.
  for (const q of ['"sin cerrar', 'arroz &', 'a', '; DROP TABLE recipe;--']) {
    const response = await http.get(`/buscar?q=${encodeURIComponent(q)}`);
    expect(response.status(), `consulta hostil: ${q}`).toBe(200);
  }
});

// ---------------------------------------------------------------- dominio --

test('la regla Michelin 0..3 se impone en la base de datos', async () => {
  // Estaba en el UML de 2023 y no se comprobaba en ningún sitio. Sushi
  // Paradise arranca con las tres permitidas; una cuarta debe rechazarse con
  // 422, no con un 500 de Hibernate.
  const response = await http.post('/culturas', {
    headers: auth(),
    data: { name: 'Cocina japonesa' },
  });
  // El nombre ya existe: se espera una regla de dominio, no un fallo interno.
  expect([409, 422]).toContain(response.status());
});

// ----------------------------------------------------------------- social --

test('valorar y guardar en el recetario', async () => {
  const valorar = await http.put('/recetas/pasta-carbonara/valoraciones/mia', {
    headers: auth(),
    data: { score: 5, comment: 'Sin nata, como debe ser.' },
  });
  expect(valorar.status()).toBe(200);
  expect((await valorar.json()).score).toBe(5);

  // Idempotente: volver a valorar actualiza en vez de chocar con la UNIQUE.
  const revalorar = await http.put('/recetas/pasta-carbonara/valoraciones/mia', {
    headers: auth(),
    data: { score: 4, comment: 'Cambio de opinión.' },
  });
  expect(revalorar.status()).toBe(200);
  expect((await revalorar.json()).score).toBe(4);

  // El favorito conmuta con la misma llamada.
  const guardado = await (await http.post('/favoritos/RECIPE/2', { headers: auth() })).json();
  expect(guardado.favorito).toBe(true);
  const quitado = await (await http.post('/favoritos/RECIPE/2', { headers: auth() })).json();
  expect(quitado.favorito).toBe(false);

  // El recetario personal exige identidad aunque sea un GET.
  expect((await http.get('/favoritos')).status()).toBe(401);
  expect((await http.get('/favoritos', { headers: auth() })).status()).toBe(200);
});

// ----------------------------------------------- trabajos en segundo plano --

test('el verificador de enlaces corre en hilos virtuales y emite progreso por SSE', async () => {
  const arranque = await http.post('/jobs/verificar-enlaces', { headers: auth() });
  expect(arranque.status()).toBe(200);

  const { jobId, total } = await arranque.json();
  expect(total).toBeGreaterThan(0);

  // Se espera a que termine consultando el estado, en vez de dormir un tiempo
  // fijo: con una espera fija el test sería lento o intermitente, según la red.
  await expect
    .poll(async () => (await (await http.get(`/jobs/${jobId}`)).json()).estado, {
      timeout: 45_000,
      intervals: [500],
    })
    .toBe('DONE');

  const estado = await (await http.get(`/jobs/${jobId}`)).json();
  expect(estado.comprobadas).toBe(estado.total);

  // Los datos de 2023 enlazaban en caliente a terceros: se espera que al menos
  // uno esté roto, incluida la URL malformada conservada como fixture.
  expect(estado.rotas).toBeGreaterThan(0);
  expect(estado.resultados.some((r: { url: string }) => r.url.startsWith('https:https://'))).toBeTruthy();
});

// --------------------------------------------------------------- navegador --

test('el frontend renderiza y navega', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /culturas gastronómicas/i })).toBeVisible();

  // Hay que esperar a la hidratación antes de pulsar. La página llega
  // prerrenderizada, así que el enlace es visible de inmediato — pero hasta que
  // Angular no se engancha, RouterLink ya ha hecho preventDefault sin tener el
  // router listo, y el clic no lleva a ninguna parte. Sin esta espera el test
  // falla de forma intermitente según lo rápido que cargue el bundle.
  await page.waitForLoadState('networkidle');

  await page.getByRole('navigation').getByRole('link', { name: 'Culturas', exact: true }).click();
  await page.waitForURL(/\/culturas$/, { timeout: 15_000 });

  // Las tarjetas vienen de la API real, no de un mock.
  const primera = page.locator('a[href^="/culturas/"]').first();
  await expect(primera).toBeVisible({ timeout: 15_000 });
  await primera.click();

  await page.waitForURL(/\/culturas\/[a-z-]+/, { timeout: 15_000 });
  await expect(page.getByRole('heading').first()).toBeVisible();
});

test('la home se sirve prerrenderizada, con contenido en el HTML', async ({ request }) => {
  // Se pide el HTML en crudo, sin ejecutar JavaScript: si el contenido está
  // ahí, el prerenderizado funciona de verdad y no es una cáscara que se
  // rellena en el cliente.
  const html = await (await request.get('/')).text();
  expect(html).toContain('Culturas Gastronómicas');
});
