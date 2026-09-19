// Qué propone la hoja de asignación para un movimiento detectado:
// AssignPlan.planFor, transliterado del Kotlin.
//
//   node sim/plan_check.js
//
// Antes esta decisión era definitiva —el botón de la notificación creaba el
// movimiento sin abrir la app— y ahora es solo la propuesta que aparece ya
// puesta en la hoja. Se sigue comprobando igual: una propuesta equivocada que
// nadie mire acaba en Tricount de todas formas.
//
// OJO, corrección respecto a la versión anterior de esta comprobación: una
// **transferencia** con alguien del grupo también se propone como reembolso,
// igual que un Bizum. El Kotlin ya lo hacía; esta comprobación y el README
// decían lo contrario y llevaban razón solo sobre el papel — una
// transferencia a alguien del grupo es literalmente dinero que cambia de
// manos entre dos miembros, que es lo que un reembolso significa.

'use strict';

function memberNamed(members, name) {
  if (!name) return null;
  const needle = name.trim().toLowerCase();
  const exact = members.find(m => m.trim().toLowerCase() === needle);
  if (exact) return exact;
  return members.find(m => {
    const a = m.trim().toLowerCase();
    return a && (needle.startsWith(a + ' ') || a.startsWith(needle.split(' ')[0] + ' '));
  }) || null;
}

const PERSON_TO_PERSON = new Set([
  'BIZUM_SENT', 'BIZUM_RECEIVED', 'TRANSFER_SENT', 'TRANSFER_RECEIVED',
]);
const MONEY_IN = new Set([
  'BIZUM_RECEIVED', 'TRANSFER_RECEIVED', 'REFUND', 'JOINT_INCOME', 'INCOME_OTHER',
]);

function planFor(kind, counterparty, members, me) {
  const other = memberNamed(members, counterparty);
  if (PERSON_TO_PERSON.has(kind) && me && other && other !== me) {
    return MONEY_IN.has(kind)
      ? ['REEMBOLSO', other, me]
      : ['REEMBOLSO', me, other];
  }
  return ['GASTO', me, members.slice()];
}

const MEMBERS = ['Ana', 'Ben', 'Cid', 'Dee'];
const ME = 'Ana';

const casos = [
  // [tipo, contraparte, esperado]
  ['BIZUM_SENT', 'Ben', ['REEMBOLSO', 'Ana', 'Ben']],
  ['BIZUM_RECEIVED', 'Ben', ['REEMBOLSO', 'Ben', 'Ana']],
  // el banco da el nombre completo, el grupo solo el nombre de pila
  ['BIZUM_RECEIVED', 'Ben Torres', ['REEMBOLSO', 'Ben', 'Ana']],
  ['BIZUM_SENT', 'BEN TORRES', ['REEMBOLSO', 'Ana', 'Ben']],
  // contraparte que no está en el grupo -> gasto repartido
  ['BIZUM_SENT', 'Farmacia Central', ['GASTO', 'Ana', MEMBERS]],
  ['BIZUM_SENT', null, ['GASTO', 'Ana', MEMBERS]],
  // una transferencia con alguien del grupo es tan reembolso como un Bizum
  ['TRANSFER_RECEIVED', 'Ben', ['REEMBOLSO', 'Ben', 'Ana']],
  ['TRANSFER_SENT', 'Dee', ['REEMBOLSO', 'Ana', 'Dee']],
  // pero con alguien de fuera, no
  ['TRANSFER_SENT', 'Hacienda', ['GASTO', 'Ana', MEMBERS]],
  // un Bizum a ti mismo no puede ser reembolso
  ['BIZUM_SENT', 'Ana', ['GASTO', 'Ana', MEMBERS]],
  // lo que no se reconoció no se interpreta
  ['UNKNOWN', 'Ben', ['GASTO', 'Ana', MEMBERS]],
  ['CARD_SPEND', 'Ben', ['GASTO', 'Ana', MEMBERS]],
];

let ok = 0;
for (const [kind, cp, esperado] of casos) {
  const got = planFor(kind, cp, MEMBERS, ME);
  const bien = JSON.stringify(got) === JSON.stringify(esperado);
  if (bien) ok++;
  const etiqueta = `${kind.padEnd(18)} contraparte=${String(cp).padEnd(18)} -> ${got[0]}`;
  console.log((bien ? 'PASS  ' : 'FALLA ') + etiqueta +
    (bien ? '' : `  esperado ${JSON.stringify(esperado)}  obtenido ${JSON.stringify(got)}`));
}

console.log(`\n${ok}/${casos.length} OK`);
process.exit(ok === casos.length ? 0 : 1);
