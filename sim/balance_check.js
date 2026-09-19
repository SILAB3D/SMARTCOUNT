// Balance neto y plan de liquidación de Stats.kt. Transliterado del Kotlin.
//
//   node sim/balance_check.js
//
// Lo que se vigila aquí es el **signo de cada tipo de movimiento**, que es
// donde la API no documentada engaña: guarda los gastos en negativo y los
// ingresos en positivo, y el reembolso (BALANCE) también en negativo aunque
// cuente como un gasto. Stats trabaja con valores absolutos y repone el signo
// a mano, así que si se repone mal el balance queda invertido justo en los
// ingresos, que es el caso que menos se mira.

'use strict';

const r2 = v => Math.round(v * 100) / 100;

/** Gasto y transferencia: quien pone el dinero queda a favor. Ingreso: al revés. */
const signOf = tx => (tx.type === 'INCOME' ? -1 : 1);

function balancesByUuid(t) {
  const b = {};
  t.members.forEach(m => { b[m] = 0; });
  for (const tx of t.transactions) {
    const s = signOf(tx);
    b[tx.owner] = (b[tx.owner] || 0) + s * Math.abs(tx.amount);
    for (const [uuid, share] of tx.allocations) {
      b[uuid] = (b[uuid] || 0) - s * Math.abs(share);
    }
  }
  return Object.fromEntries(Object.entries(b).map(([k, v]) => [k, r2(v)]));
}

function settlementPlan(t) {
  const b = balancesByUuid(t);
  const cred = Object.entries(b).filter(([, v]) => v > 0.005).sort((x, y) => y[1] - x[1]);
  const deb = Object.entries(b).filter(([, v]) => v < -0.005)
    .map(([k, v]) => [k, -v]).sort((x, y) => y[1] - x[1]);
  const legs = [];
  let i = 0, j = 0;
  let cl = cred.length ? cred[0][1] : 0;
  let dl = deb.length ? deb[0][1] : 0;
  while (i < cred.length && j < deb.length) {
    const pay = Math.min(cl, dl);
    if (pay > 0.005) legs.push([deb[j][0], cred[i][0], r2(pay)]);
    cl -= pay;
    dl -= pay;
    if (cl <= 0.005) { i++; cl = i < cred.length ? cred[i][1] : 0; }
    if (dl <= 0.005) { j++; dl = j < deb.length ? deb[j][1] : 0; }
  }
  return legs;
}

/**
 * El reparto que hace el servidor cuando las asignaciones van en RATIO. Aquí
 * se calcula para poder construir los movimientos de prueba; en la app ya no
 * viaja en ninguna petición.
 */
function splitEvenly(total, n) {
  const cents = Math.round(Math.abs(total) * 100);
  const base = Math.floor(cents / n);
  const extra = cents % n;
  return Array.from({ length: n }, (_, i) => (base + (i < extra ? 1 : 0)) / 100);
}

const expense = (desc, amount, payer, split) => ({
  type: 'NORMAL', desc, amount: -Math.abs(amount), owner: payer,
  allocations: splitEvenly(amount, split.length).map((p, i) => [split[i], -p]),
});

const income = (desc, amount, receiver, split) => ({
  type: 'INCOME', desc, amount: Math.abs(amount), owner: receiver,
  allocations: splitEvenly(amount, split.length).map((p, i) => [split[i], p]),
});

/** Como la escribe la app oficial: en negativo, quien recibe se lo lleva entero. */
const transfer = (desc, amount, sender, receiver) => ({
  type: 'BALANCE', desc, amount: -Math.abs(amount), owner: sender,
  allocations: [[receiver, -Math.abs(amount)], [sender, 0]],
});

/** La forma antigua, en positivo: debe dar exactamente el mismo balance. */
const transferPositivo = (desc, amount, sender, receiver) => ({
  type: 'BALANCE', desc, amount: Math.abs(amount), owner: sender,
  allocations: [[receiver, Math.abs(amount)], [sender, 0]],
});

const MEMBERS = ['ana', 'ben', 'cid'];
const group = (...txs) => ({ members: MEMBERS, transactions: txs });

const results = [];
function check(name, got, expected) {
  const ok = JSON.stringify(got) === JSON.stringify(expected);
  results.push(ok);
  console.log((ok ? 'PASS ' : 'FALLA ') + name);
  if (!ok) {
    console.log(`     esperado ${JSON.stringify(expected)}`);
    console.log(`     obtenido ${JSON.stringify(got)}`);
  }
}

// --- Salidas: quien paga queda a favor, los demás a deber -------------------
check('gasto 30 entre 3',
  balancesByUuid(group(expense('Cena', 30, 'ana', MEMBERS))),
  { ana: 20, ben: -10, cid: -10 });

check('gasto no divisible reparte el céntimo suelto',
  balancesByUuid(group(expense('Taxi', 10, 'ana', MEMBERS))),
  { ana: 6.66, ben: -3.33, cid: -3.33 });

// --- Entradas: quien cobra adquiere la deuda -------------------------------
check('ingreso 90 entre 3 invierte el gasto',
  balancesByUuid(group(income('Fianza', 90, 'ana', MEMBERS))),
  { ana: -60, ben: 30, cid: 30 });

check('gasto e ingreso iguales se anulan',
  balancesByUuid(group(expense('Fianza', 90, 'ana', MEMBERS),
    income('Devolución', 90, 'ana', MEMBERS))),
  { ana: 0, ben: 0, cid: 0 });

// --- Adelantos: ajustan solo a las dos personas implicadas ------------------
check('transferencia mueve saldo entre dos',
  balancesByUuid(group(transfer('Bote', 25, 'ben', 'ana'))),
  { ana: -25, ben: 25, cid: 0 });

check('la transferencia salda el gasto que la motiva',
  balancesByUuid(group(expense('Cena', 30, 'ana', MEMBERS),
    transfer('Le pago', 10, 'ben', 'ana'))),
  { ana: 10, ben: 0, cid: -10 });

// El signo con el que se guarda la transferencia cambió al igualarla a la de
// la app oficial. El balance no puede depender de eso: Stats usa valores
// absolutos y su propia tabla de signos, y esto lo comprueba.
check('el signo guardado de la transferencia no altera el balance',
  balancesByUuid(group(transferPositivo('Bote', 25, 'ben', 'ana'))),
  balancesByUuid(group(transfer('Bote', 25, 'ben', 'ana'))));

// --- Los saldos siempre suman cero -----------------------------------------
const mixed = group(
  expense('Cena', 30, 'ana', MEMBERS),
  expense('Taxi', 10, 'ben', MEMBERS),
  income('Fianza', 45, 'cid', MEMBERS),
  transfer('Adelanto', 12.5, 'cid', 'ana'),
);
check('los saldos suman cero',
  Math.abs(Object.values(balancesByUuid(mixed)).reduce((a, b) => a + b, 0)) < 0.005, true);

// --- Liquidación ------------------------------------------------------------
check('plan de un solo pago',
  settlementPlan(group(expense('Cena', 30, 'ana', ['ana', 'ben']))),
  [['ben', 'ana', 15]]);

check('sin deudas no hay plan',
  settlementPlan(group(transfer('Bote', 10, 'ana', 'ben'),
    transfer('Vuelta', 10, 'ben', 'ana'))),
  []);

check('un pago por deudor',
  settlementPlan(group(expense('Cena', 30, 'ana', MEMBERS))),
  [['ben', 'ana', 10], ['cid', 'ana', 10]]);

// El plan liquida de verdad: aplicarlo deja todos los saldos a cero.
const applied = group(...mixed.transactions,
  ...settlementPlan(mixed).map(([frm, to, amt]) => transfer('Liquidación', amt, frm, to)));
check('aplicar el plan deja el grupo en paz',
  Object.values(balancesByUuid(applied)).every(v => Math.abs(v) < 0.005), true);

check('aplicado el plan, no queda nada que saldar', settlementPlan(applied), []);

const ok = results.filter(Boolean).length;
console.log(`\n${ok}/${results.length} comprobaciones OK`);
process.exit(ok === results.length ? 0 : 1);
