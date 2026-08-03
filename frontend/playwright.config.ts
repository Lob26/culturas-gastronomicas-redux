import { defineConfig, devices } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Configuración del test end-to-end.
 *
 * <p>Playwright arranca el frontend por su cuenta; el backend y los
 * contenedores los levanta `task e2e` antes de invocarlo, porque necesitan
 * Terraform y migraciones y no es cosa de un `webServer`.
 */

/**
 * Carga el .env que genera Terraform.
 *
 * <p>Hace falta para la clave de API: Terraform la crea con random_password, de
 * modo que ni está en el repositorio ni se puede escribir en el test. En CI no
 * hay .env y las variables llegan del entorno del job, así que se leen sólo las
 * que aún no estén definidas — el entorno gana sobre el archivo.
 *
 * <p>A mano y sin dotenv: son seis líneas y evitan una dependencia más para
 * leer un archivo de pares clave-valor.
 */
function loadEnvFile(): void {
  const path = resolve(__dirname, '..', '.env');
  if (!existsSync(path)) {
    return;
  }
  for (const line of readFileSync(path, 'utf8').split('\n')) {
    const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line.trim());
    if (match && process.env[match[1]] === undefined) {
      process.env[match[1]] = match[2];
    }
  }
}

loadEnvFile();
export default defineConfig({
  testDir: './e2e',

  // Un solo worker y sin paralelismo: el test escribe en la base compartida
  // (valora, marca favoritos, edita una cultura). En paralelo, dos casos se
  // pisarían y el fallo resultante sería intermitente y desconcertante.
  fullyParallel: false,
  workers: 1,

  // Sin reintentos ni siquiera en CI: este test existe para dar una respuesta
  // binaria y fiable. Un reintento convertiría un fallo real e intermitente en
  // un verde ocasional, que es peor que no tener el test.
  retries: 0,

  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],

  timeout: 60_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4200',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'es-CO',
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  webServer: {
    command: 'npm start',
    url: 'http://localhost:4200',
    reuseExistingServer: true,
    timeout: 180_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
