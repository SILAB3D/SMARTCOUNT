// Transcripción de los constructores de peticiones de `TricountClient.kt`.
//
// Es una copia, con lo que eso tiene de peligroso: si tocas uno, toca el otro
// y ejecuta `npm run check:payloads`. A cambio, el reparto de un movimiento se
// puede comprobar sin un emulador Android delante, y `roundtrip.js` puede
// mandar exactamente lo mismo que manda la app contra la API de verdad.

'use strict';

/** `String.format(Locale.US, "%.2f", v)` */
function money(v) {
  return (Math.round(v * 100) / 100).toFixed(2);
}

/**
 * El reparto igualitario que enseña la hoja antes de guardar. Ojo: ya no viaja
 * en ninguna petición — las asignaciones sin cantidad fijada van en RATIO y es
 * el servidor quien reparte los céntimos.
 */
function previewSplit(total, n) {
  if (n <= 0) throw new Error('n debe ser > 0');
  const cents = Math.round(Math.abs(total) * 100);
  const base = Math.floor(cents / n);
  const extra = cents % n;
  return Array.from({ length: n }, (_, i) => (base + (i < extra ? 1 : 0)) / 100);
}

/** `Split`: a quién se reparte y con qué cantidades fijadas (en positivo). */
function split(members, fixed = {}) {
  return { members, fixed };
}

function fixedTotal(s) {
  return s.members.reduce((acc, m) => acc + (s.fixed[m.uuid] !== undefined ? Math.abs(s.fixed[m.uuid]) : 0), 0);
}

function freeMembers(s) {
  return s.members.filter(m => s.fixed[m.uuid] === undefined);
}

/**
 * Lo mismo que `Split.validate`. La API responde 400 cuando las asignaciones
 * no suman el total, así que esto solo adelanta el error con un mensaje mejor.
 */
function validateSplit(s, total) {
  if (s.members.length === 0) throw new Error('Hay que repartir el movimiento entre al menos una persona');
  if (freeMembers(s).length === 0) {
    const diff = total - fixedTotal(s);
    if (Math.abs(diff) >= 0.005) {
      throw new Error(diff > 0 ? `Faltan ${money(diff)} por asignar` : `Las partes se pasan en ${money(-diff)} del total`);
    }
  } else if (fixedTotal(s) > total + 0.005) {
    throw new Error(`Las partes fijadas suman ${money(fixedTotal(s))}, más que el total ${money(total)}`);
  }
}

/** AMOUNT para lo fijado a mano, RATIO 1 para lo que se reparte el resto. */
function allocations(s, sign, currency) {
  return s.members.map(m => {
    const fixed = s.fixed[m.uuid];
    if (fixed === undefined) {
      return { membership_uuid: m.uuid, type: 'RATIO', share_ratio: 1 };
    }
    return {
      membership_uuid: m.uuid,
      amount: { value: money(sign * Math.abs(fixed)), currency },
      type: 'AMOUNT',
    };
  });
}

function base(uuid, description, value, currency, ownerUuid, allocs, type, date) {
  return {
    uuid,
    description,
    amount: { value, currency },
    membership_uuid_owner: ownerUuid,
    allocations: allocs,
    type_transaction: type,
    status: 'ACTIVE',
    date,
  };
}

function withCategory(payload, category, categoryCustom) {
  if (categoryCustom) {
    payload.category = 'OTHER';
    payload.category_custom = categoryCustom;
  } else if (category) {
    payload.category = category;
  }
  return payload;
}

/** Gasto: total en negativo, asignaciones en negativo. */
function createExpense({ uuid, description, amount, payerUuid, split: s, currency, category, categoryCustom, date }) {
  const total = Math.abs(amount);
  validateSplit(s, total);
  return withCategory(
    base(uuid, description, money(-total), currency, payerUuid, allocations(s, -1, currency), 'NORMAL', date),
    category, categoryCustom
  );
}

/** Ingreso: total en positivo, asignaciones en positivo. */
function createIncome({ uuid, description, amount, receiverUuid, split: s, currency, category, categoryCustom, date }) {
  const total = Math.abs(amount);
  validateSplit(s, total);
  return withCategory(
    base(uuid, description, money(total), currency, receiverUuid, allocations(s, 1, currency), 'INCOME', date),
    category, categoryCustom
  );
}

/**
 * Transferencia: total en negativo, quien recibe se lleva el importe entero en
 * una asignación RATIO y quien paga queda a cero en una AMOUNT. Es la forma
 * exacta que escribe la app oficial.
 */
function createReimbursement({ uuid, description, amount, payerUuid, receiverUuid, currency, date }) {
  if (payerUuid === receiverUuid) throw new Error('Una transferencia necesita dos personas distintas');
  const total = Math.abs(amount);
  return base(
    uuid, description, money(-total), currency, payerUuid,
    [
      { membership_uuid: receiverUuid, amount: { value: money(-total), currency }, type: 'RATIO', share_ratio: 1 },
      { membership_uuid: payerUuid, amount: { value: '0', currency }, type: 'AMOUNT' },
    ],
    'BALANCE', date
  );
}

module.exports = {
  money, previewSplit, split, validateSplit, allocations,
  createExpense, createIncome, createReimbursement,
};
