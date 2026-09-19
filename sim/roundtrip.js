// Ida y vuelta contra la API de verdad.
//
// Crea un movimiento de cada tipo con **las mismas peticiones que manda la
// app**, lo relee tal y como lo ha guardado el servidor, comprueba que la
// forma es la que produce la app oficial de Tricount, y lo borra.
//
//   node sim/roundtrip.js <enlace-o-token-del-grupo>
//
// Necesita red y un grupo de usar y tirar: **crea y borra movimientos de
// verdad** en el grupo que le pases. No lo apuntes a un grupo con datos que
// te importen.
//
// Es la comprobación que no puede hacer payload_check.js: aquella compara la
// petición contra una forma conocida, y esta pregunta al servidor si esa
// forma es la que él entiende. La distinción no es teórica — el reparto
// desigual se cayó con un 400 hasta que se supo que las partes tienen que
// sumar el total.

'use strict';

const crypto = require('crypto');
const P = require('./lib/payloads');

const BASE = 'https://api.tricount.bunq.com';
const UA = 'com.bunq.tricount.android:RELEASE:7.0.7:3174:ANDROID:13:C';
/** La API limita por ritmo: con menos pausa responde 429. */
const PAUSA_MS = 4000;

const entrada = process.argv[2];
if (!entrada) {
  console.error('Uso: node sim/roundtrip.js <enlace-o-token-del-grupo>');
  process.exit(2);
}
const TOKEN = (entrada.match(/tricount\.com\/(?:[a-z]{2}\/)?([A-Za-z0-9]{8,40})/) || [])[1]
  || entrada.trim();

const appId = crypto.randomUUID();
const { publicKey } = crypto.generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicKeyEncoding: { type: 'pkcs1', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
});
let session = null;
let uid = null;

const wait = ms => new Promise(r => setTimeout(r, ms));

async function call(path, method = 'GET', body = null) {
  await wait(PAUSA_MS);
  const headers = {
    'User-Agent': UA,
    'app-id': appId,
    'X-Bunq-Client-Request-Id': crypto.randomUUID(),
    'Content-Type': 'application/json',
  };
  if (session) headers['X-Bunq-Client-Authentication'] = session;
  const res = await fetch(BASE + path, {
    method, headers, body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 220)}`);
  return text ? JSON.parse(text) : {};
}

function fecha() {
  const d = new Date();
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.000000`;
}

let fallos = 0;
function afirma(nombre, condicion, detalle) {
  if (condicion) {
    console.log(`  OK    ${nombre}`);
  } else {
    fallos++;
    console.log(`  FALLA ${nombre}${detalle ? ' · ' + detalle : ''}`);
  }
}

/** Las asignaciones tal y como quedaron, por nombre de miembro. */
function leerAsignaciones(entry) {
  return entry.allocations.map(a => ({
    nombre: a.membership.RegistryMembershipNonUser.alias.display_name,
    uuid: a.membership.RegistryMembershipNonUser.uuid,
    valor: parseFloat(a.amount.value),
    tipo: a.type,
  }));
}

(async () => {
  const auth = await call('/v1/session-registry-installation', 'POST', {
    app_installation_uuid: appId,
    client_public_key: publicKey,
    device_description: 'Android',
  });
  for (const i of auth.Response) {
    if (i.Token) session = i.Token.token;
    if (i.UserPerson) uid = i.UserPerson.id;
  }

  // Unirse es lo que da permiso de escritura, igual que en la app.
  await call(`/v1/user/${uid}/registry-synchronization`, 'POST', {
    all_registry_active: [{ public_identifier_token: TOKEN }],
    all_registry_archived: [],
    all_registry_deleted: [],
  });

  const lista = await call(`/v1/user/${uid}/registry`);
  const reg = lista.Response.map(r => r.Registry)
    .find(r => r && r.public_identifier_token === TOKEN);
  if (!reg) throw new Error('no se encontró el grupo con ese enlace');

  const miembros = reg.memberships.map(m => m.RegistryMembershipNonUser);
  if (miembros.length < 3) throw new Error('el grupo de pruebas necesita al menos tres miembros');
  const [A, B, C] = miembros;
  const EUR = reg.currency;
  console.log(`grupo «${reg.title}» (${reg.id}) · ${miembros.map(m => m.alias.display_name).join(', ')}\n`);

  const creados = [];
  async function crear(nombre, payload) {
    const r = await call(`/v1/user/${uid}/registry/${reg.id}/registry-entry`, 'POST', payload);
    const id = r.Response[0].Id.id;
    creados.push(id);
    return id;
  }

  const casos = [
    {
      nombre: 'gasto igualitario entre tres',
      payload: P.createExpense({
        uuid: crypto.randomUUID(), description: 'SC-RT gasto igual', amount: 9,
        payerUuid: A.uuid, split: P.split([A, B, C]), currency: EUR, date: fecha(),
      }),
      espera: { total: -9, tipo: 'NORMAL', partes: [[A, -3, 'RATIO'], [B, -3, 'RATIO'], [C, -3, 'RATIO']] },
    },
    {
      nombre: 'gasto desigual: 7,50 fijado y el resto para otro',
      payload: P.createExpense({
        uuid: crypto.randomUUID(), description: 'SC-RT gasto desigual', amount: 10,
        payerUuid: A.uuid, split: P.split([A, C], { [A.uuid]: 7.5 }), currency: EUR, date: fecha(),
      }),
      espera: { total: -10, tipo: 'NORMAL', partes: [[A, -7.5, 'AMOUNT'], [C, -2.5, 'RATIO']] },
    },
    {
      nombre: 'ingreso igualitario entre tres',
      payload: P.createIncome({
        uuid: crypto.randomUUID(), description: 'SC-RT ingreso igual', amount: 9,
        receiverUuid: A.uuid, split: P.split([A, B, C]), currency: EUR, date: fecha(),
      }),
      espera: { total: 9, tipo: 'INCOME', partes: [[A, 3, 'RATIO'], [B, 3, 'RATIO'], [C, 3, 'RATIO']] },
    },
    {
      nombre: 'ingreso desigual: 8,00 fijado a uno',
      payload: P.createIncome({
        uuid: crypto.randomUUID(), description: 'SC-RT ingreso desigual', amount: 10,
        receiverUuid: A.uuid, split: P.split([B, A], { [B.uuid]: 8 }), currency: EUR, date: fecha(),
      }),
      espera: { total: 10, tipo: 'INCOME', partes: [[B, 8, 'AMOUNT'], [A, 2, 'RATIO']] },
    },
    {
      nombre: 'transferencia de uno a otro',
      payload: P.createReimbursement({
        uuid: crypto.randomUUID(), description: 'SC-RT transferencia', amount: 10,
        payerUuid: A.uuid, receiverUuid: B.uuid, currency: EUR, date: fecha(),
      }),
      espera: { total: -10, tipo: 'BALANCE', partes: [[B, -10, 'RATIO'], [A, 0, 'AMOUNT']] },
    },
  ];

  for (const caso of casos) {
    try {
      caso.id = await crear(caso.nombre, caso.payload);
    } catch (e) {
      fallos++;
      console.log(`  FALLA ${caso.nombre} · la API rechazó la petición: ${e.message}`);
    }
  }

  // Releer de una vez y comprobar cómo quedó cada uno.
  const despues = await call(`/v1/user/${uid}/registry`);
  const reg2 = despues.Response.map(r => r.Registry).find(r => r && r.id === reg.id);

  for (const caso of casos) {
    if (!caso.id) continue;
    const e = reg2.all_registry_entry.map(x => x.RegistryEntry).find(x => x.id === caso.id);
    if (!e) {
      fallos++;
      console.log(`  FALLA ${caso.nombre} · no aparece al releer`);
      continue;
    }
    const partes = leerAsignaciones(e);
    const total = parseFloat(e.amount.value);
    const suma = Math.round(partes.reduce((a, p) => a + p.valor, 0) * 100) / 100;

    const detalles = [
      [`${caso.nombre} · tipo ${caso.espera.tipo}`, e.type_transaction === caso.espera.tipo, e.type_transaction],
      [`${caso.nombre} · total ${caso.espera.total}`, total === caso.espera.total, String(total)],
      [`${caso.nombre} · las partes suman el total`, suma === total, `${suma} != ${total}`],
    ];
    for (const [m, valor, esperado] of caso.espera.partes) {
      const p = partes.find(x => x.uuid === m.uuid);
      detalles.push([
        `${caso.nombre} · ${m.alias.display_name} recibe ${valor} como ${esperado}`,
        !!p && p.valor === valor && p.tipo === esperado,
        p ? `${p.valor} ${p.tipo}` : 'no aparece',
      ]);
    }
    detalles.forEach(([n, ok, d]) => afirma(n, ok, ok ? null : d));
  }

  // Limpieza: el grupo queda como estaba.
  console.log('');
  let borrados = 0;
  for (const caso of casos) {
    if (!caso.id) continue;
    try {
      await call(`/v1/user/${uid}/registry/${reg.id}/registry-entry/${caso.id}`, 'DELETE');
      borrados++;
    } catch (e) {
      console.log(`  AVISO no se pudo borrar ${caso.id}: ${e.message}`);
    }
  }
  const final = await call(`/v1/user/${uid}/registry`);
  const reg3 = final.Response.map(r => r.Registry).find(r => r && r.id === reg.id);
  const restos = reg3.all_registry_entry
    .map(x => x.RegistryEntry)
    .filter(x => x.status === 'ACTIVE' && x.description.startsWith('SC-RT'));
  afirma(`limpieza: ${borrados} borrados y ningún resto`, restos.length === 0,
    `quedan ${restos.length}`);

  console.log(fallos === 0 ? '\nTodo correcto' : `\n${fallos} fallos`);
  process.exit(fallos === 0 ? 0 : 1);
})().catch(e => {
  console.error('ERROR', e.message);
  process.exit(1);
});
