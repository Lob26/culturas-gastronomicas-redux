#!/usr/bin/env node
/**
 * Consola de tareas: una interfaz web para lanzar los objetivos del Taskfile.
 *
 * Es a `task` lo que Swagger UI es a la API: `task --list --json` hace de
 * documento de especificación y esto lo pinta. La lista NO se mantiene a mano;
 * añadir una tarea al Taskfile la hace aparecer sola.
 *
 * ── Por qué es un proceso aparte ────────────────────────────────────────────
 * No puede vivir dentro del backend de Spring: haría falta el backend en marcha
 * para levantar la infraestructura de la que depende el propio backend, que es
 * una dependencia circular. Y no puede ir en un contenedor porque su trabajo es
 * gobernar `podman` y `terraform` DEL ANFITRIÓN.
 *
 * De ahí que sea Node pelado, sin una sola dependencia y sin paso de compilación:
 * esta herramienta tiene que funcionar justo cuando no funciona nada más.
 *
 * ── Esto ejecuta comandos: léase la parte de seguridad ──────────────────────
 * Un endpoint HTTP que lanza procesos en tu máquina es, por definición, un
 * ejecutor remoto de código. Las defensas están en `authorize()` y en que los
 * nombres de tarea salgan SIEMPRE de la lista del Taskfile, nunca del cliente.
 */

import { createHash, randomBytes } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');

const HOST = '127.0.0.1';
const PORT = Number(process.env.CONSOLE_PORT ?? 7777);

/**
 * Token de sesión, nuevo en cada arranque.
 *
 * Con la consola escuchando sólo en 127.0.0.1, «cualquiera de la red» no puede
 * llegar — pero tu propio navegador sí. Cualquier página que tengas abierta
 * puede intentar `fetch('http://127.0.0.1:7777/api/runs')`. El token es lo que
 * convierte eso en un 401.
 */
const TOKEN = randomBytes(24).toString('base64url');

/** Orígenes que se aceptan como propios. */
const SELF_ORIGINS = new Set([
  `http://${HOST}:${PORT}`,
  `http://localhost:${PORT}`,
]);

/**
 * Tareas que destruyen datos. Exigen confirmación explícita en el cuerpo, no
 * basta con pulsar: `nuke` borra los volúmenes de Postgres, MinIO y n8n.
 */
const DESTRUCTIVE = new Set(['nuke']);

/**
 * Tareas que no terminan solas. La interfaz las marca y ofrece «parar» en vez
 * de esperar un código de salida que no va a llegar.
 */
const LONG_RUNNING = new Set(['dev', 'backend:dev', 'frontend:dev', 'llm:up', 'logs']);

// ─────────────────────────────────────────────────────────── binario de task ──

/**
 * Ruta absoluta del ejecutable de `task`.
 *
 * Se resuelve UNA vez y se lanza siempre por ruta absoluta, lo que permite
 * mantener `shell: false`. Con `shell: true` bastaría escribir `task` a secas,
 * pero entonces todo lo que se pasara acabaría interpretado por un shell, que
 * es exactamente lo que no se quiere en un ejecutor de comandos.
 *
 * En Windows hay un detalle: desde Node 20.12 lanzar un `.bat` o `.cmd` sin
 * shell falla con EINVAL (mitigación de CVE-2024-27980). Aquí no aplica porque
 * el shim de scoop es `task.exe`, pero si en otra máquina `where task` devuelve
 * un `.cmd`, este mismo código fallaría — y el mensaje no lo explicaría.
 */
function resolveTaskBinary() {
  const finder = process.platform === 'win32' ? 'where' : 'which';
  let found;
  try {
    found = execFileSync(finder, ['task'], { encoding: 'utf8' });
  } catch {
    throw new Error("No se encontró `task` en el PATH. En Windows: scoop install main/task");
  }
  const first = found.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)[0];
  if (!first) {
    throw new Error('`task` está en el PATH pero no se pudo resolver su ruta.');
  }
  if (process.platform === 'win32' && /\.(cmd|bat)$/i.test(first)) {
    throw new Error(
      `El ejecutable encontrado es ${first}. Node no puede lanzar .cmd/.bat sin shell ` +
      'desde la 20.12; instala Task de forma que quede un task.exe (scoop lo hace).');
  }
  return first;
}

const TASK_BIN = resolveTaskBinary();

/** Lee del Taskfile la lista de tareas: es la única fuente de nombres válidos. */
function listTasks() {
  const raw = execFileSync(TASK_BIN, ['--list', '--json'], { cwd: REPO, encoding: 'utf8' });
  const parsed = JSON.parse(raw);
  return parsed.tasks.map((entry) => ({
    name: entry.name,
    desc: entry.desc,
    group: entry.name.includes(':') ? entry.name.split(':')[0] : 'stack',
    destructive: DESTRUCTIVE.has(entry.name),
    longRunning: LONG_RUNNING.has(entry.name),
  }));
}

// ─────────────────────────────────────────────────────────────── seguridad ──

/**
 * Tres comprobaciones, y ninguna sobra.
 *
 * 1. `Host` tiene que ser local. Para de raíz el «DNS rebinding»: un dominio
 *    que resuelve a 127.0.0.1 llegaría hasta aquí con el Host de ese dominio.
 * 2. `Origin`, cuando viene, tiene que ser el nuestro. El navegador lo pone en
 *    toda petición de origen cruzado, así que es lo que distingue «lo pediste
 *    tú desde la consola» de «lo pidió una pestaña cualquiera».
 * 3. El token, en cabecera propia. Una cabecera no estándar obliga al navegador
 *    a hacer preflight, y como aquí no se responde ni un solo encabezado CORS,
 *    ese preflight falla: una web de terceros no puede ni intentarlo.
 */
function authorize(req) {
  const host = String(req.headers.host ?? '').replace(/:\d+$/, '');
  if (!['127.0.0.1', 'localhost', '[::1]', '::1'].includes(host)) {
    return false;
  }
  const origin = req.headers.origin;
  if (origin && !SELF_ORIGINS.has(origin)) {
    return false;
  }
  return timingSafeEquals(String(req.headers['x-console-token'] ?? ''), TOKEN);
}

/** Comparación de longitud constante, para no filtrar el token carácter a carácter. */
function timingSafeEquals(a, b) {
  const ha = createHash('sha256').update(a).digest();
  const hb = createHash('sha256').update(b).digest();
  return ha.equals(hb);
}

// ──────────────────────────────────────────────────────────── ejecuciones ──

/** @type {Map<string, {id:string,task:string,child:import('node:child_process').ChildProcess|null,lines:{stream:string,text:string}[],status:string,exitCode:number|null,subscribers:Set<import('node:http').ServerResponse>}>} */
const runs = new Map();

/** Una ejecución viva por tarea: pulsar dos veces «up» no debe lanzar dos apply. */
const active = new Map();

function startRun(taskName) {
  const id = randomBytes(8).toString('hex');

  // El nombre viene SIEMPRE de listTasks(), y se pasa como elemento de un array
  // con shell:false. Sin shell no hay metacaracteres que interpretar, así que
  // no existe la vía «task up; rm -rf ~» ni aunque el nombre llegara sucio.
  const child = spawn(TASK_BIN, [taskName], {
    cwd: REPO,
    shell: false,
    windowsHide: true,
    // En POSIX se crea un grupo de procesos propio para poder matar también a
    // los hijos —Maven lanza una JVM, npm lanza otro node—. En Windows el
    // equivalente es `taskkill /T`, más abajo.
    detached: process.platform !== 'win32',
    env: { ...process.env, FORCE_COLOR: '0', NO_COLOR: '1' },
  });

  const run = {
    id, task: taskName, child,
    lines: [], status: 'running', exitCode: null,
    subscribers: new Set(),
  };
  runs.set(id, run);
  active.set(taskName, id);

  const push = (stream) => (chunk) => {
    for (const text of chunk.toString().split(/\r?\n/)) {
      if (text === '') continue;
      const line = { stream, text };
      // Se guarda el histórico porque el navegador se suscribe DESPUÉS de
      // lanzar: sin esto, las primeras líneas se perderían siempre.
      run.lines.push(line);
      if (run.lines.length > 5000) run.lines.shift();
      broadcast(run, 'salida', line);
    }
  };

  child.stdout.on('data', push('out'));
  child.stderr.on('data', push('err'));

  child.on('error', (error) => {
    run.status = 'error';
    broadcast(run, 'salida', { stream: 'err', text: `No se pudo lanzar la tarea: ${error.message}` });
    finish(run, null);
  });

  child.on('close', (code) => finish(run, code));

  return run;
}

function finish(run, code) {
  if (run.status === 'finished') return;
  run.status = code === 0 ? 'finished' : 'failed';
  run.exitCode = code;
  run.child = null;
  if (active.get(run.task) === run.id) active.delete(run.task);
  broadcast(run, 'fin', { estado: run.status, codigo: code });
  for (const res of run.subscribers) res.end();
  run.subscribers.clear();
}

/**
 * Detiene una ejecución y a todo lo que haya lanzado.
 *
 * Matar sólo al proceso de `task` dejaría viva la JVM o el servidor de Vite que
 * ese `task` arrancó, y el puerto seguiría ocupado. De ahí el árbol completo.
 */
function stopRun(run) {
  const child = run.child;
  if (!child) return;
  if (process.platform === 'win32') {
    spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
  } else {
    try {
      process.kill(-child.pid, 'SIGTERM');
      setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL'); } catch { /* ya murió */ } }, 5000);
    } catch { /* ya murió */ }
  }
}

function broadcast(run, event, data) {
  const payload = `event:${event}\ndata:${JSON.stringify(data)}\n\n`;
  for (const res of run.subscribers) {
    res.write(payload);
  }
}

// ────────────────────────────────────────────────────────────── servidor ──

const INDEX = readFileSync(join(HERE, 'index.html'), 'utf8');

function json(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    // Sin CORS a propósito: nada externo debe poder leer estas respuestas.
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(text);
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    return {};
  }
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://${HOST}:${PORT}`);

  // La página se sirve con el token en la query porque una navegación del
  // navegador no puede llevar cabeceras. A partir de ahí, el JavaScript lo
  // manda en X-Console-Token y la query no vuelve a usarse.
  if (req.method === 'GET' && url.pathname === '/') {
    if (url.searchParams.get('t') !== TOKEN) {
      res.writeHead(401, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Falta el token. Abre la URL que imprimió `task console` al arrancar.\n');
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(INDEX);
    return;
  }

  if (!url.pathname.startsWith('/api/')) {
    json(res, 404, { error: 'No existe' });
    return;
  }

  if (!authorize(req)) {
    json(res, 401, { error: 'No autorizado' });
    return;
  }

  // GET /api/tasks
  if (req.method === 'GET' && url.pathname === '/api/tasks') {
    try {
      json(res, 200, { repo: REPO, plataforma: process.platform, tareas: listTasks() });
    } catch (error) {
      json(res, 500, { error: String(error.message ?? error) });
    }
    return;
  }

  // POST /api/runs  {tarea, confirmacion?}
  if (req.method === 'POST' && url.pathname === '/api/runs') {
    const body = await readBody(req);
    const known = listTasks();
    const wanted = known.find((entry) => entry.name === body.tarea);

    // Allowlist: si no está en el Taskfile, no existe. Es lo que impide que el
    // cliente decida qué se ejecuta.
    if (!wanted) {
      json(res, 400, { error: 'Esa tarea no está en el Taskfile' });
      return;
    }
    if (wanted.destructive && body.confirmacion !== wanted.name) {
      json(res, 409, { error: `Escribe «${wanted.name}» para confirmar: borra datos.` });
      return;
    }
    if (active.has(wanted.name)) {
      json(res, 409, { error: 'Esa tarea ya está corriendo', runId: active.get(wanted.name) });
      return;
    }

    const run = startRun(wanted.name);
    json(res, 202, { runId: run.id, tarea: run.task });
    return;
  }

  // GET /api/runs/:id/stream
  const streamMatch = url.pathname.match(/^\/api\/runs\/([a-f0-9]+)\/stream$/);
  if (req.method === 'GET' && streamMatch) {
    const run = runs.get(streamMatch[1]);
    if (!run) { json(res, 404, { error: 'No existe esa ejecución' }); return; }

    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store',
      Connection: 'keep-alive',
      // Sin esto, un proxy intermedio podría almacenar el flujo y entregarlo
      // entero al final, que es justo lo contrario de lo que se quiere.
      'X-Accel-Buffering': 'no',
    });

    // Primero el histórico, luego lo nuevo: quien se conecta tarde ve todo.
    for (const line of run.lines) {
      res.write(`event:salida\ndata:${JSON.stringify(line)}\n\n`);
    }
    if (run.status !== 'running') {
      res.write(`event:fin\ndata:${JSON.stringify({ estado: run.status, codigo: run.exitCode })}\n\n`);
      res.end();
      return;
    }

    run.subscribers.add(res);
    req.on('close', () => run.subscribers.delete(res));
    return;
  }

  // POST /api/runs/:id/stop
  const stopMatch = url.pathname.match(/^\/api\/runs\/([a-f0-9]+)\/stop$/);
  if (req.method === 'POST' && stopMatch) {
    const run = runs.get(stopMatch[1]);
    if (!run) { json(res, 404, { error: 'No existe esa ejecución' }); return; }
    stopRun(run);
    json(res, 202, { detenida: true });
    return;
  }

  json(res, 404, { error: 'No existe' });
});

server.listen(PORT, HOST, () => {
  const url = `http://${HOST}:${PORT}/?t=${TOKEN}`;
  process.stdout.write(
    `\n  Consola de tareas de Culturas Gastronómicas\n` +
    `  Repositorio : ${REPO}\n` +
    `  Ejecutable  : ${TASK_BIN}\n\n` +
    `  Abre esta URL (el token cambia en cada arranque):\n\n    ${url}\n\n` +
    `  Ctrl-C para parar. Las tareas que sigan vivas se paran solas al salir.\n\n`);
});

/** Al cerrar la consola no se dejan procesos huérfanos ocupando puertos. */
function shutdown() {
  for (const run of runs.values()) {
    if (run.child) stopRun(run);
  }
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
