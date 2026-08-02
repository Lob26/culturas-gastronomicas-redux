import { defineConfig, devices } from '@playwright/test';

/**
 * Configuración del test end-to-end.
 *
 * <p>Playwright arranca el frontend por su cuenta; el backend y los
 * contenedores los levanta `task e2e` antes de invocarlo, porque necesitan
 * Terraform y migraciones y no es cosa de un `webServer`.
 */
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
