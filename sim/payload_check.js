// Comprueba las peticiones que construye la app contra lo que la app oficial
// de Tricount guarda de verdad.
//
// Los casos no son inventados: salen de un grupo donde se creó un movimiento
// de cada tipo **desde la app oficial** y luego se leyó por la API. De ahí
// sale la forma que hay que reproducir — qué va en AMOUNT, qué en RATIO, y con
// qué signo — y por eso el reparto desigual manda solo las partes fijadas
// como AMOUNT y deja el resto en RATIO para que lo cuadre el servidor.
//
//   node sim/payload_check.js
//
// La comprobación contra el servidor de verdad está en sim/roundtrip.js, que
// necesita red.

'use strict';

const P = require('./lib/payloads');

const EUR = 'EUR';
const A = { uuid: 'uuid-A' };
const B = { uuid: 'uuid-B' };
const C = { uuid: 'uuid-C' };
const DATE = '2026-09-17 18:51:33.651000';
const UUID = 'nuevo-uuid';

let fallos = 0;

function check(nombre, actual, esperado) {
  const a = JSON.stringify(actual, null, 2);
  const e = JSON.stringify(esperado, null, 2);
  if (a === e) {
    console.log(`  OK    ${nombre}`);
  } else {
    fallos++;
    console.log(`  FALLA ${nombre}`);
    console.log(`     esperado: ${e.replace(/\n/g, '\n     ')}`);
    console.log(`     obtenido: ${a.replace(/\n/g, '\n     ')}`);
  }
}

function esperaError(nombre, fn, fragmento) {
  try {
    fn();
    fallos++;
    console.log(`  FALLA ${nombre} · no lanzó ningún error`);
  } catch (e) {
    if (e.message.includes(fragmento)) {
      console.log(`  OK    ${nombre} · «${e.message}»`);
    } else {
      fallos++;
      console.log(`  FALLA ${nombre} · esperaba «${fragmento}» y dijo «${e.message}»`);
    }
  }
}

console.log('Peticiones contra la forma de la app oficial\n');

// 1. Gasto a partes iguales: TODAS las asignaciones en RATIO, sin importe.
//    Los céntimos los reparte el servidor (10,00 entre 3 → 3,33/3,33/3,34).
check('gasto igualitario entre tres', P.createExpense({
  uuid: UUID, description: 'Gasto igualitario', amount: 10, payerUuid: A.uuid,
  split: P.split([A, B, C]), currency: EUR, date: DATE,
}), {
  uuid: UUID,
  description: 'Gasto igualitario',
  amount: { value: '-10.00', currency: EUR },
  membership_uuid_owner: A.uuid,
  allocations: [
    { membership_uuid: A.uuid, type: 'RATIO', share_ratio: 1 },
    { membership_uuid: B.uuid, type: 'RATIO', share_ratio: 1 },
    { membership_uuid: C.uuid, type: 'RATIO', share_ratio: 1 },
  ],
  type_transaction: 'NORMAL',
  status: 'ACTIVE',
  date: DATE,
});

// 2. Gasto desigual: lo fijado a mano en AMOUNT y el resto en RATIO, tal cual
//    lo guarda la app oficial. B queda fuera del reparto.
check('gasto desigual, 7,50 fijado a A y el resto para C', P.createExpense({
  uuid: UUID, description: 'Gasto desigual', amount: 10, payerUuid: A.uuid,
  split: P.split([A, C], { [A.uuid]: 7.5 }), currency: EUR, date: DATE,
}), {
  uuid: UUID,
  description: 'Gasto desigual',
  amount: { value: '-10.00', currency: EUR },
  membership_uuid_owner: A.uuid,
  allocations: [
    { membership_uuid: A.uuid, amount: { value: '-7.50', currency: EUR }, type: 'AMOUNT' },
    { membership_uuid: C.uuid, type: 'RATIO', share_ratio: 1 },
  ],
  type_transaction: 'NORMAL',
  status: 'ACTIVE',
  date: DATE,
});

// 3. Ingreso igualitario: el mismo reparto, en positivo.
check('ingreso igualitario entre tres', P.createIncome({
  uuid: UUID, description: 'Ingreso igualitario', amount: 10, receiverUuid: A.uuid,
  split: P.split([A, B, C]), currency: EUR, date: DATE,
}), {
  uuid: UUID,
  description: 'Ingreso igualitario',
  amount: { value: '10.00', currency: EUR },
  membership_uuid_owner: A.uuid,
  allocations: [
    { membership_uuid: A.uuid, type: 'RATIO', share_ratio: 1 },
    { membership_uuid: B.uuid, type: 'RATIO', share_ratio: 1 },
    { membership_uuid: C.uuid, type: 'RATIO', share_ratio: 1 },
  ],
  type_transaction: 'INCOME',
  status: 'ACTIVE',
  date: DATE,
});

// 4. Ingreso desigual: 8,00 para B y lo que quede para A.
check('ingreso desigual, 8,00 fijado a B', P.createIncome({
  uuid: UUID, description: 'Ingreso desigual', amount: 10, receiverUuid: A.uuid,
  split: P.split([B, A], { [B.uuid]: 8 }), currency: EUR, date: DATE,
}), {
  uuid: UUID,
  description: 'Ingreso desigual',
  amount: { value: '10.00', currency: EUR },
  membership_uuid_owner: A.uuid,
  allocations: [
    { membership_uuid: B.uuid, amount: { value: '8.00', currency: EUR }, type: 'AMOUNT' },
    { membership_uuid: A.uuid, type: 'RATIO', share_ratio: 1 },
  ],
  type_transaction: 'INCOME',
  status: 'ACTIVE',
  date: DATE,
});

// 5. Transferencia: en NEGATIVO, quien recibe con el importe entero en RATIO y
//    quien paga a cero en AMOUNT. Antes se mandaba en positivo y con las dos
//    en AMOUNT: el balance salía igual, pero el apunte no era el mismo que ve
//    el resto del grupo desde Tricount.
check('transferencia de A a B', P.createReimbursement({
  uuid: UUID, description: 'Transferencia', amount: 10,
  payerUuid: A.uuid, receiverUuid: B.uuid, currency: EUR, date: DATE,
}), {
  uuid: UUID,
  description: 'Transferencia',
  amount: { value: '-10.00', currency: EUR },
  membership_uuid_owner: A.uuid,
  allocations: [
    { membership_uuid: B.uuid, amount: { value: '-10.00', currency: EUR }, type: 'RATIO', share_ratio: 1 },
    { membership_uuid: A.uuid, amount: { value: '0', currency: EUR }, type: 'AMOUNT' },
  ],
  type_transaction: 'BALANCE',
  status: 'ACTIVE',
  date: DATE,
});

// 6. Con categoría.
check('gasto con categoría', P.createExpense({
  uuid: UUID, description: 'Cena', amount: 20, payerUuid: A.uuid,
  split: P.split([A, B]), currency: EUR, category: 'FOOD_AND_DRINK', date: DATE,
}), {
  uuid: UUID,
  description: 'Cena',
  amount: { value: '-20.00', currency: EUR },
  membership_uuid_owner: A.uuid,
  allocations: [
    { membership_uuid: A.uuid, type: 'RATIO', share_ratio: 1 },
    { membership_uuid: B.uuid, type: 'RATIO', share_ratio: 1 },
  ],
  type_transaction: 'NORMAL',
  status: 'ACTIVE',
  date: DATE,
  category: 'FOOD_AND_DRINK',
});

// 7. Todo fijado y cuadrando: no queda nadie en RATIO y la suma da el total.
check('gasto con las dos partes fijadas y cuadradas', P.createExpense({
  uuid: UUID, description: 'Mitad y mitad', amount: 10, payerUuid: A.uuid,
  split: P.split([A, B], { [A.uuid]: 6, [B.uuid]: 4 }), currency: EUR, date: DATE,
}), {
  uuid: UUID,
  description: 'Mitad y mitad',
  amount: { value: '-10.00', currency: EUR },
  membership_uuid_owner: A.uuid,
  allocations: [
    { membership_uuid: A.uuid, amount: { value: '-6.00', currency: EUR }, type: 'AMOUNT' },
    { membership_uuid: B.uuid, amount: { value: '-4.00', currency: EUR }, type: 'AMOUNT' },
  ],
  type_transaction: 'NORMAL',
  status: 'ACTIVE',
  date: DATE,
});

console.log('\nLo que no debe salir a la red\n');

// La API responde 400 «the amounts of the allocations that you provided do not
// sum up to the amount of the entry». Comprobado contra el servidor: por eso
// se para antes, y diciendo cuánto falta.
esperaError('partes fijadas que no llegan al total', () => P.createExpense({
  uuid: UUID, description: 'Cojo', amount: 10, payerUuid: A.uuid,
  split: P.split([A], { [A.uuid]: 7.5 }), currency: EUR, date: DATE,
}), 'Faltan 2.50 por asignar');

esperaError('partes fijadas que se pasan del total', () => P.createExpense({
  uuid: UUID, description: 'Pasado', amount: 10, payerUuid: A.uuid,
  split: P.split([A, B], { [A.uuid]: 8, [B.uuid]: 5 }), currency: EUR, date: DATE,
}), 'se pasan en 3.00');

esperaError('fijar más de lo que hay, dejando gente sin parte', () => P.createExpense({
  uuid: UUID, description: 'Pasado', amount: 10, payerUuid: A.uuid,
  split: P.split([A, B], { [A.uuid]: 12 }), currency: EUR, date: DATE,
}), 'más que el total');

esperaError('transferencia a uno mismo', () => P.createReimbursement({
  uuid: UUID, description: 'Yo a mí', amount: 5,
  payerUuid: A.uuid, receiverUuid: A.uuid, currency: EUR, date: DATE,
}), 'dos personas distintas');

console.log('\nEl reparto que se enseña antes de guardar\n');

const reparto = P.previewSplit(39.9, 4);
check('39,90 entre 4 sin perder céntimos', reparto, [9.98, 9.98, 9.97, 9.97]);
check('y suman exactamente el total', Math.round(reparto.reduce((a, b) => a + b, 0) * 100), 3990);

console.log(fallos === 0 ? '\nTodo correcto' : `\n${fallos} fallos`);
process.exit(fallos === 0 ? 0 : 1);
