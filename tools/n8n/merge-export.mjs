#!/usr/bin/env node
/**
 * Coloca lo exportado por n8n encima del archivo del repositorio que le toca.
 *
 * `n8n export:workflow --separate` nombra los archivos por IDENTIFICADOR
 * (`culturasMant0001.json`), mientras que en el repositorio se llaman por lo
 * que hacen (`mantenimiento-nocturno.json`). Copiar sin más dejaba ocho
 * archivos donde había cuatro: los originales intactos y los exportados al
 * lado, con los mismos workflows dentro.
 *
 * Aquí se emparejan por el campo `id`, que ambos comparten, y se sobrescribe el
 * archivo del repositorio conservando su nombre. Un workflow creado desde la
 * interfaz de n8n no tiene pareja: ese se queda con el nombre que trae, para
 * que quien lo exportó decida cómo llamarlo.
 *
 * Uso: node tools/n8n/merge-export.mjs <directorio-exportado> <directorio-repo>
 */

import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Lo único que se versiona de un workflow.
 *
 * Lista BLANCA, no negra. n8n exporta además un montón de estado de ejecución
 * —`updatedAt`, `versionId`, `versionCounter`, `triggerCount`, `staticData`—
 * y, lo que es peor, un bloque `shared` con el `projectId` y el `creatorId` del
 * n8n que lo exportó. Guardar eso tendría dos consecuencias feas: cada máquina
 * produciría un diff distinto sin que nadie hubiera cambiado nada, y el
 * repositorio acabaría con identificadores de la instalación local dentro.
 *
 * Con una lista negra habría que ir añadiendo campos cada vez que n8n
 * incorpore uno nuevo, y el fallo sería silencioso. Con lista blanca, un campo
 * nuevo simplemente no entra.
 *
 * Son exactamente los campos que `n8n import:workflow` necesita para
 * reconstruir el workflow.
 */
const CAMPOS = ['id', 'name', 'nodes', 'connections', 'settings'];

const [exported, repo] = process.argv.slice(2);
if (!exported || !repo) {
  console.error('Uso: merge-export.mjs <directorio-exportado> <directorio-repo>');
  process.exit(2);
}

const jsonFiles = (dir) => readdirSync(dir).filter((name) => name.endsWith('.json'));

/** id -> nombre de archivo, tal y como está hoy en el repositorio. */
const byId = new Map();
for (const file of jsonFiles(repo)) {
  try {
    const { id } = JSON.parse(readFileSync(join(repo, file), 'utf8'));
    if (id) byId.set(id, file);
  } catch {
    // Un archivo ilegible no debe impedir mezclar los demás; se avisa al final.
    console.warn(`  ! ${file} no es JSON válido, se ignora`);
  }
}

let actualizados = 0;
let nuevos = 0;

for (const file of jsonFiles(exported)) {
  const completo = JSON.parse(readFileSync(join(exported, file), 'utf8'));
  const { id, name } = completo;
  const destino = byId.get(id) ?? file;

  // Se conserva el orden de CAMPOS para que dos exportaciones den siempre el
  // mismo archivo; el orden de claves de n8n no está garantizado.
  const limpio = {};
  for (const campo of CAMPOS) {
    if (completo[campo] !== undefined) limpio[campo] = completo[campo];
  }

  // Sangría de dos espacios y salto final, igual que los archivos escritos a
  // mano: así el diff muestra lo que cambió de verdad y no un reformateo.
  writeFileSync(join(repo, destino), `${JSON.stringify(limpio, null, 2)}\n`);

  if (byId.has(id)) {
    actualizados++;
    console.log(`  = ${destino}  (${name})`);
  } else {
    nuevos++;
    console.log(`  + ${destino}  (${name})  ← nuevo, renómbralo si quieres`);
  }
}

console.log(`\n  ${actualizados} actualizados, ${nuevos} nuevos.`);
