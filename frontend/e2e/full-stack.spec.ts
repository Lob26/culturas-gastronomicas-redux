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

/**
 * El carril semántico, aislado.
 *
 * Cada consulta está elegida para que los otros dos carriles no puedan
 * responderla por construcción: están en inglés contra un catálogo escrito
 * íntegramente en español, así que no comparten ni lexemas (carril léxico) ni
 * trigramas suficientes (carril difuso). Si estas pasan, el vector está
 * haciendo el trabajo.
 *
 * Esto es lo que justifica haber elegido un modelo multilingüe en vez del
 * all-MiniLM-L6-v2 que Spring AI trae por defecto, que es sólo inglés. Sin
 * este test la elección sería una suposición.
 */
test('el carril semántico responde donde el léxico y el difuso no pueden', async () => {
  const nombres = async (q: string) =>
    (await (await http.get(`/buscar?q=${encodeURIComponent(q)}&limit=3`)).json())
      .map((h: { name: string }) => h.name);

  // Ni «raw», ni «fish», ni «seaweed» aparecen en el catálogo. El alga nori sí.
  expect(await nombres('raw fish with seaweed')).toContain('Sushi Rolls');

  // «creamy» y «pasta» contra una descripción que habla de huevo y guanciale.
  expect(await nombres('creamy italian pasta dish')).toContain('Pasta Carbonara');

  // Regresión concreta: al indexar también los pasos, éstos eran el 90 % del
  // texto y su lenguaje procedimental —cortar, calentar, mezclar— es idéntico
  // en todas las recetas. Eso acercaba entre sí platos que no se parecen y
  // hundía al que sí correspondía. Con nombre + descripción + cultura, el
  // acierto es el primero; con los pasos dentro, caía al tercero.
  expect((await nombres('raw fish with seaweed'))[0]).toBe('Sushi Rolls');
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

// -------------------------------------------------------- recomendaciones --

test('las recomendaciones pasan de populares a personales al valorar', async () => {
  // Usuario propio y no el compartido: el test de valoraciones ya le puso un 4
  // a la carbonara, y un 4 ya es semilla. Reutilizarlo haría que este test
  // arrancara en caliente y la comprobación de arranque en frío pasaría o
  // fallaría según el orden de ejecución, que es la definición de test frágil.
  const nuevo = await http.post('/auth/registro', {
    data: { username: `rec${Date.now().toString().slice(-9)}`, displayName: 'Recomendaciones', password: 'clave-de-prueba-123' },
  });
  expect(nuevo.status()).toBe(201);
  const propio = { Authorization: `Bearer ${(await nuevo.json()).token}` };

  // Nada que saber de él todavía. Devolver una lista vacía sería técnicamente
  // honesto e inútil; la reserva por popularidad es lo correcto, y el campo
  // `basis` lo dice explícitamente para que el cliente pueda titular la
  // sección de otra forma.
  const frio = await http.get('/buscar/recomendaciones?limit=5', { headers: propio });
  expect(frio.status()).toBe(200);
  const enFrio = await frio.json();
  expect(enFrio.basis).toBe('POPULAR');
  expect(enFrio.seeds).toBe(0);
  expect(enFrio.results.length).toBeGreaterThan(0);

  // Al valorar alto, esa receta pasa a ser semilla y el modo cambia.
  await http.put('/recetas/sushi-rolls/valoraciones/mia', {
    headers: propio,
    data: { score: 5, comment: 'El alga nori marca la diferencia.' },
  });

  const caliente = await (await http.get('/buscar/recomendaciones?limit=5', { headers: propio })).json();
  expect(caliente.basis).toBe('PERSONAL');
  expect(caliente.seeds).toBeGreaterThan(0);

  // Recomendar lo que ya valoraste no es recomendar. La exclusión va en el SQL,
  // así que esto comprueba el WHERE, no un filtro posterior.
  expect(caliente.results.map((h: { slug: string }) => h.slug)).not.toContain('sushi-rolls');
});

test('las recetas parecidas salen de la vecindad de vectores', async () => {
  const parecidas = await http.get('/buscar/similares/pasta-carbonara?limit=3');
  expect(parecidas.status()).toBe(200);

  const slugs = (await parecidas.json()).map((h: { slug: string }) => h.slug);
  expect(slugs.length).toBeGreaterThan(0);
  // Una receta no se recomienda a sí misma.
  expect(slugs).not.toContain('pasta-carbonara');
});

// -------------------------------------------------------------- asistente --

/**
 * El asistente necesita Ollama levantado, y Ollama se arranca a mano.
 *
 * El test cubre las dos situaciones porque las dos son correctas: con Ollama
 * parado tiene que degradar —503 y el resto del catálogo intacto— y con Ollama
 * en marcha tiene que responder de verdad. Fijar sólo una haría fallar el test
 * en la mitad de las ejecuciones legítimas.
 *
 * Para forzar el camino completo: `task llm:up` en otra terminal y repetir.
 */
test('el asistente exige identidad, y responde o degrada según Ollama', async () => {
  // Una generación local ocupa CPU o GPU durante segundos, así que dejarlo
  // abierto sería regalar un botón de denegación de servicio.
  expect((await http.get('/asistente/preguntar?q=que+lleva+la+carbonara')).status()).toBe(401);

  // Un modelo local en CPU tarda alrededor de un minuto en responder esto. El
  // plazo por defecto de Playwright son 30 s, así que sin esta línea el test
  // fallaría por reloj justo cuando el asistente SÍ está funcionando.
  test.setTimeout(300_000);

  const respuesta = await http.get('/asistente/preguntar?q=' + encodeURIComponent('¿Qué lleva la carbonara?'), {
    headers: auth(),
    timeout: 300_000,
  });

  expect([200, 503]).toContain(respuesta.status());

  if (respuesta.status() === 503) {
    // Apagado se apaga en RFC 9457, como todo lo demás.
    expect(respuesta.headers()['content-type']).toContain('application/problem+json');
  } else {
    const flujo = await respuesta.text();

    // Las fuentes van primero, para que la interfaz pueda pintar de dónde
    // saldrá la respuesta antes de que exista.
    expect(flujo).toContain('event:fuentes');
    expect(flujo).toContain('pasta-carbonara');

    // Llega troceado y termina: si faltara el `fin`, el cliente se quedaría
    // esperando para siempre sin que nada diera error.
    expect(flujo).toContain('event:texto');
    expect(flujo).toContain('event:fin');

    // Hay que recomponer el texto antes de mirarlo, y no es un detalle: el
    // modelo emite un token por evento, así que una cita «[1]» viaja como tres
    // eventos distintos —«[», «1», «]»— y NUNCA aparece contigua en el flujo en
    // crudo. Comprobarlo sobre el flujo sin recomponer da un fallo que parece
    // del modelo cuando es del test.
    const texto = flujo
      .split('\n')
      .filter((linea) => linea.startsWith('data:'))
      .map((linea) => linea.slice(5))
      .join('');

    // Está fundamentado: el modelo cita la fuente en vez de recitar lo que sabe
    // de cocina. Sin la cita no habría forma de comprobar la respuesta.
    expect(texto).toContain('[1]');

    // La carbonara de este catálogo lleva guanciale y NO nata. Es justo donde
    // un modelo tiende a corregir al catálogo con lo que aprendió por ahí, así
    // que esto mide que la recuperación manda sobre el conocimiento propio.
    expect(texto.toLowerCase()).toContain('guanciale');
  }

  // Lo importante: el resto del catálogo no se entera de nada de esto.
  expect((await http.get('/culturas?size=1')).status()).toBe(200);
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

// ------------------------------------------------------------ flujos SSE --

/**
 * Lee un flujo SSE durante un rato y devuelve lo recibido.
 *
 * <p>No se puede usar el cliente de peticiones de Playwright: espera a que la
 * respuesta <strong>termine</strong>, y estos dos flujos no terminan solos —el
 * de estadísticas vive diez minutos y el del catálogo media hora—. El del
 * verificador de enlaces sí funciona con `http.get` porque se cierra cuando
 * acaba su trabajo; estos no tienen final.
 *
 * <p>Con `fetch` y un AbortController se lee lo que haya llegado y se corta,
 * que es exactamente lo que hace un cliente de verdad.
 */
async function readSse(path: string, ms: number, headers: Record<string, string> = {}): Promise<string> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  let received = '';
  try {
    const response = await fetch(`${ORIGIN}${PREFIX}${path}`, { signal: controller.signal, headers });
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      received += decoder.decode(value, { stream: true });
    }
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      throw e;
    }
  } finally {
    clearTimeout(timer);
  }
  return received;
}

test('el flujo de estadísticas emite cifras periódicamente', async () => {
  const flujo = await readSse('/feed/estadisticas', 8_000);

  expect(flujo).toContain('event:estadisticas');
  const primera = flujo.split('\n').find((linea) => linea.startsWith('data:'))!.slice(5);
  const cifras = JSON.parse(primera);

  expect(cifras.recetas).toBeGreaterThanOrEqual(5);
  expect(cifras.momento).toBeTruthy();

  // Emite periódicamente, no una sola vez: en ocho segundos, con un tick cada
  // cinco, tienen que haber salido al menos dos.
  expect(flujo.match(/event:estadisticas/g)!.length).toBeGreaterThanOrEqual(2);
});

/**
 * El feed del catálogo, que es el que justifica tener Redis.
 *
 * Con dos instancias detrás de un balanceador, un bus en memoria sólo avisaría
 * a los navegadores conectados a la instancia que hizo el cambio; los demás se
 * quedarían mirando una lista que nunca se actualiza, sin que nada diera error.
 * Este test comprueba el camino completo: publicar en Redis, recibirlo en el
 * suscriptor y reenviarlo por SSE.
 */
test('crear una receta llega al flujo del catálogo por Redis', async () => {
  // Se abre el flujo ANTES de escribir; suscribirse después perdería el evento,
  // que es pub/sub y no una cola con historial.
  const flujo = readSse('/feed/catalogo', 10_000);

  // Un margen para que la suscripción esté establecida antes de publicar.
  await new Promise((resolve) => setTimeout(resolve, 1_500));

  const nombre = `Prueba Feed ${Date.now().toString().slice(-6)}`;
  const creada = await http.post('/recetas', {
    headers: auth(),
    data: {
      name: nombre,
      description: 'Creada por el test del flujo',
      cultureSlug: 'cocina-italiana',
      steps: ['Un paso cualquiera'],
    },
  });
  expect(creada.status()).toBe(201);
  const { slug } = await creada.json();

  const recibido = await flujo;
  expect(recibido).toContain('event:cambio');
  expect(recibido).toContain(slug);
  expect(recibido).toContain('creada');

  // Se limpia lo que este test creó: la base es compartida y dejar recetas de
  // prueba haría que los recuentos de otros casos dejaran de cuadrar.
  expect((await http.delete(`/recetas/${slug}`, { headers: auth() })).status()).toBe(204);
});

// ----------------------------------------------------- operación y límites --

test('cada respuesta lleva identificador de correlación, y respeta el que venga', async () => {
  const propio = await http.get('/jobs/estadisticas');
  // Se genera cuando no viene: sin él, depurar en producción es buscar por
  // marca de tiempo entre las líneas de todas las peticiones simultáneas.
  expect(propio.headers()['x-request-id']).toMatch(/[0-9a-f-]{8,}/);

  // Y se respeta el de fuera, para que el rastro cruce servicios.
  const ajeno = await http.get('/jobs/estadisticas', {
    headers: { 'X-Request-Id': 'traza-de-prueba' },
  });
  expect(ajeno.headers()['x-request-id']).toBe('traza-de-prueba');

  // Un identificador con saltos de línea permitiría inyectar entradas falsas
  // en el registro, así que se limpia en vez de aceptarse tal cual.
  const sucio = await http.get('/jobs/estadisticas', {
    headers: { 'X-Request-Id': 'abc<script>' },
  });
  expect(sucio.headers()['x-request-id']).toBe('abcscript');
});

test('las estadísticas cuadran con el catálogo', async () => {
  const stats = await (await http.get('/jobs/estadisticas')).json();

  expect(stats.culturas).toBeGreaterThanOrEqual(5);
  expect(stats.recetas).toBeGreaterThanOrEqual(5);

  // Las cinco sembradas tienen vector. NO se exige `indexadas === recetas`
  // aunque sea la señal que de verdad interesa vigilar: otros casos de esta
  // misma suite crean recetas, y una receta recién creada no tiene vector
  // hasta el siguiente reindexado. Afirmar la igualdad haría que este test
  // fallara según el orden de ejecución y no según el estado del sistema.
  // Esa vigilancia vive donde corresponde: en el resumen diario de n8n.
  expect(stats.indexadas).toBeGreaterThanOrEqual(5);
  expect(stats.indexadas).toBeLessThanOrEqual(stats.recetas);
});

test('subir una imagen: formato validado, identidad exigida y URL firmada', async () => {
  // PNG de 1x1 construido a mano; no hace falta un fichero de apoyo.
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
    'base64',
  );

  // Sin sesión no se sube: consume almacenamiento y queda atribuido.
  expect(
    (await http.post('/recetas/pasta-carbonara/imagenes', {
      multipart: { archivo: { name: 'uno.png', mimeType: 'image/png', buffer: png } },
    })).status(),
  ).toBe(401);

  // SVG rechazado: el navegador lo ejecuta como documento, así que admitirlo
  // convertiría una subida de imagen en XSS almacenado.
  expect(
    (await http.post('/recetas/pasta-carbonara/imagenes', {
      headers: auth(),
      multipart: { archivo: { name: 'x.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg/>') } },
    })).status(),
  ).toBe(422);

  const subida = await http.post('/recetas/pasta-carbonara/imagenes', {
    headers: auth(),
    multipart: { archivo: { name: 'uno.png', mimeType: 'image/png', buffer: png } },
  });
  expect(subida.status()).toBe(201);

  const { url } = await subida.json();
  // La URL firmada sirve el objeto directamente desde MinIO, sin pasar por el
  // backend: si esto devuelve 200, el almacén está de verdad en el circuito.
  const descarga = await api.get(url);
  expect(descarga.status()).toBe(200);
  expect(descarga.headers()['content-type']).toContain('image/png');
});

test('el respaldo se guarda en el almacén de objetos', async () => {
  // Sólo ADMIN: el volcado incluye la tabla de usuarios.
  expect((await http.post('/jobs/respaldo', { headers: auth() })).status()).toBe(403);

  // La clave la genera Terraform con random_password, así que sale del .env
  // que carga playwright.config; en CI llega del entorno del job.
  const respaldo = await http.post('/jobs/respaldo', {
    headers: { 'X-API-Key': process.env.CULTURAS_API_KEY ?? 'dev-api-key-change-me' },
  });
  expect(respaldo.status()).toBe(200);

  const resultado = await respaldo.json();
  // Un respaldo de cero filas es el fallo peligroso: se sube, parece que
  // funcionó y sólo se descubre al restaurar.
  expect(resultado.filas).toBeGreaterThan(0);
  expect(resultado.clave).toMatch(/^respaldos\/catalogo-.*\.json$/);
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

/**
 * La búsqueda, desde el navegador.
 *
 * En 2023 este botón abría un aviso cuyo cuerpo decía «TO-DO». Aquí se escribe
 * en el campo y salen resultados que vienen del backend real.
 */
test('la búsqueda funciona desde la interfaz y se puede compartir por enlace', async ({ page }) => {
  await page.goto('/buscar');
  await page.waitForLoadState('networkidle');

  await page.getByRole('searchbox', { name: /término de búsqueda/i }).fill('carbonara');

  const resultado = page.getByRole('link', { name: /Pasta Carbonara/ });
  await expect(resultado).toBeVisible({ timeout: 15_000 });

  // El término se refleja en la URL, así que la búsqueda es compartible.
  await expect(page).toHaveURL(/q=carbonara/);

  // Y se lee al entrar: recargar no la pierde.
  await page.reload();
  await expect(page.getByRole('link', { name: /Pasta Carbonara/ })).toBeVisible({ timeout: 15_000 });

  await resultado.click();
  await page.waitForURL(/\/recetas\/pasta-carbonara/, { timeout: 15_000 });
});

/**
 * Las pantallas sociales, desde el navegador.
 *
 * Recorre el circuito entero que en 2023 no existía ni en el backend: entrar,
 * valorar una receta, verla guardada en el recetario y recibir una
 * recomendación basada en eso.
 *
 * Se hace en un solo test y no en cuatro a propósito: son pasos encadenados de
 * un mismo estado —hay que haber valorado para que la recomendación sea
 * personal— y partirlo obligaría a repetir el registro y el voto en cada uno.
 */
test('valorar, guardar y recibir recomendaciones desde la interfaz', async ({ page }) => {
  const usuario = `ui${Date.now().toString().slice(-9)}`;

  await page.goto('/entrar');
  await page.waitForLoadState('networkidle');

  // Registro y acceso comparten pantalla; se alterna con este enlace.
  await page.getByRole('button', { name: /no tienes cuenta/i }).click();
  await page.getByLabel('Usuario').fill(usuario);
  await page.getByLabel('Nombre visible').fill('Prueba interfaz');
  await page.getByLabel('Contraseña').fill('clave-de-prueba-123');
  await page.getByRole('button', { name: 'Crear cuenta' }).click();

  // Al registrarse navega a la home, y la cabecera pasa a mostrar el nombre.
  await page.waitForURL(/\/$/, { timeout: 15_000 });
  await expect(page.getByText('Prueba interfaz')).toBeVisible({ timeout: 15_000 });

  await page.goto('/recetas/pasta-carbonara');
  await page.waitForLoadState('networkidle');

  // Atribución: las recetas sembradas no las creó nadie, así que no debe
  // aparecer un «Añadido por» sin nombre detrás.
  await expect(page.getByText(/añadido por/i)).toHaveCount(0);

  // Valorar. PrimeNG deja los radios ocultos —son sólo el soporte accesible,
  // con data-pc-section="hiddenoptioninput"— y el clic va sobre el div de la
  // estrella. Pulsar el radio directamente falla por accionabilidad, que es
  // correcto: un usuario tampoco puede pulsar algo invisible.
  //
  // Se elige por posición y no por etiqueta accesible porque esa etiqueta sale
  // de las traducciones de PrimeNG («5 stars»), y ahí sí cambiaría al
  // traducirlas; la quinta estrella es un 5 en cualquier idioma.
  await page.locator('p-rating .p-rating-option').nth(4).click();
  // `exact` porque el marcador de favoritos se llama «Guardar en el recetario»
  // y la búsqueda por nombre es por subcadena: sin esto coinciden los dos.
  await page.getByRole('button', { name: 'Guardar', exact: true }).click();

  // «Retirar» sólo aparece cuando la lista recargada ya contiene tu voto, así
  // que verlo prueba a la vez que se guardó y que el recurso se invalidó.
  await expect(page.getByRole('button', { name: 'Retirar' })).toBeVisible({ timeout: 15_000 });

  // Guardar en el recetario.
  await page.getByRole('button', { name: /guardar en el recetario/i }).click();
  await expect(page.getByRole('button', { name: /quitar del recetario/i })).toBeVisible({
    timeout: 15_000,
  });

  await page.goto('/recetario');
  await page.waitForLoadState('networkidle');
  await expect(page.getByRole('link', { name: /Pasta Carbonara/ })).toBeVisible({ timeout: 15_000 });

  // Y la home pasa de populares a personales, porque ya hay un 5 que sirve de
  // semilla. Es el mismo cambio de `basis` que comprueba el test de API, visto
  // desde donde lo ve el usuario.
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.getByRole('heading', { name: 'Recomendado para ti' })).toBeVisible({
    timeout: 15_000,
  });
});

/**
 * El asistente desde el navegador.
 *
 * Comprueba lo que el test de API no puede ver: que el flujo SSE se consume y
 * se pinta de verdad en la pantalla. Con Ollama parado se conforma con el aviso
 * de que hace falta arrancarlo, que es el otro comportamiento correcto.
 */
test('el asistente responde en pantalla o avisa de que falta Ollama', async ({ page }) => {
  test.setTimeout(300_000);

  const usuario = `ask${Date.now().toString().slice(-9)}`;

  await page.goto('/entrar');
  await page.waitForLoadState('networkidle');
  await page.getByRole('button', { name: /no tienes cuenta/i }).click();
  await page.getByLabel('Usuario').fill(usuario);
  await page.getByLabel('Nombre visible').fill('Preguntón');
  await page.getByLabel('Contraseña').fill('clave-de-prueba-123');
  await page.getByRole('button', { name: 'Crear cuenta' }).click();
  await page.waitForURL(/\/$/, { timeout: 15_000 });

  await page.goto('/preguntar');
  await page.waitForLoadState('networkidle');

  await page.getByLabel('Pregunta').fill('¿Qué lleva la carbonara?');
  await page.getByRole('button', { name: 'Preguntar' }).click();

  // Una cosa o la otra, y las dos son correctas.
  const respuesta = page.locator('article');
  const aviso = page.getByText(/necesita Ollama/i);
  await expect(respuesta.or(aviso).first()).toBeVisible({ timeout: 240_000 });

  if (await aviso.isVisible()) {
    return;
  }

  // Las fuentes se pintan antes que el texto, y son enlaces navegables: sin
  // eso la cita «[1]» sería una marca que no lleva a ninguna parte.
  await expect(page.getByRole('link', { name: /\[1\] Pasta Carbonara/ })).toBeVisible({
    timeout: 240_000,
  });

  // Y la respuesta acaba conteniendo lo que dice la fuente, no lo que el modelo
  // recuerde de otras carbonaras.
  await expect(respuesta).toContainText(/guanciale/i, { timeout: 240_000 });
});

test('la home se sirve prerrenderizada, con contenido en el HTML', async ({ request }) => {
  // Se pide el HTML en crudo, sin ejecutar JavaScript: si el contenido está
  // ahí, el prerenderizado funciona de verdad y no es una cáscara que se
  // rellena en el cliente.
  const html = await (await request.get('/')).text();
  expect(html).toContain('Culturas Gastronómicas');
});
