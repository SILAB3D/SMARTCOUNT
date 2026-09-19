// Comprobaciones automatizadas sobre el prototipo navegable, en claro y
// oscuro. No sustituyen a probar la app en un móvil: comprueban las reglas de
// comportamiento que sí se pueden escribir —qué aparece, qué cambia al tocar
// algo, qué no debe pasar— sobre una maqueta que sigue la misma lógica.
//
//   npm run check:ui
//
// El botón atrás del móvil no existe en un navegador: el prototipo lo expone
// como window.__back() y aquí se llama así.

const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const OUT = path.join(__dirname, 'shots');
fs.mkdirSync(OUT, { recursive: true });
const URL = require('url').pathToFileURL(path.resolve(__dirname, '..', 'prototipo-ui.html')).href;

const fails = [];
function check(name, cond, extra = '') {
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (cond ? '' : ' :: ' + extra));
  if (!cond) fails.push(name);
}

(async () => {
  const browser = await chromium.launch();
  for (const theme of ['dark', 'light']) {
    const ctx = await browser.newContext({
      viewport: { width: 402, height: 874 },
      deviceScaleFactor: 2,
      colorScheme: theme
    });
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    await page.goto(URL);
    const shot = n => page.screenshot({ path: `${OUT}/${theme}-${n}.png` });
    const wait = ms => page.waitForTimeout(ms);

    // ---- Pantalla de carga ----
    await wait(120);
    check(`[${theme}] la animación de carga se muestra al abrir`,
      (await page.locator('#splash').count()) === 1);
    await shot('00-carga');
    await page.waitForFunction(() => window.__splashMs != null, null, { timeout: 4000 });
    const splashMs = await page.evaluate(() => window.__splashMs);
    check(`[${theme}] la animación dura 1 s`, splashMs >= 1000 && splashMs < 1120,
      `${Math.round(splashMs)} ms`);
    await page.waitForFunction(() => !document.getElementById('splash'), null, { timeout: 3000 });
    await wait(120);

    // =====================================================================
    // Pestaña Grupos: rejilla, buscador y tinte
    // =====================================================================
    await shot('01-grupos');
    check(`[${theme}] sin scroll horizontal`, !(await page.evaluate(() =>
      document.documentElement.scrollWidth > document.documentElement.clientWidth)));

    const cards = await page.locator('.card').count();
    check(`[${theme}] la rejilla enseña todos los grupos`, cards === 3, `tarjetas=${cards}`);
    check(`[${theme}] cada ficha lleva su cifra`,
      (await page.locator('.card .cn').count()) === cards);

    // El grupo de ahorro se distingue sin leer la etiqueta.
    const tintDistinto = await page.evaluate(() => {
      const sav = document.querySelector('.card.savings');
      const nor = document.querySelector('.card:not(.savings)');
      if (!sav || !nor) return false;
      return getComputedStyle(sav).backgroundColor !== getComputedStyle(nor).backgroundColor;
    });
    check(`[${theme}] el grupo de ahorro tiene otra tonalidad`, tintDistinto);
    check(`[${theme}] y además lo dice`,
      (await page.locator('.card.savings .tag').textContent()).includes('AHORRO'));

    // Buscador: fragmentos y sin tildes.
    await page.fill('#g-search', 'lisb');
    await wait(150);
    check(`[${theme}] el buscador filtra por un fragmento del título`,
      (await page.locator('.card').count()) === 1);
    await page.fill('#g-search', 'AHORRO CASA');
    await wait(150);
    check(`[${theme}] el buscador ignora mayúsculas`,
      (await page.locator('.card').count()) === 1);
    await page.fill('#g-search', 'zzz');
    await wait(150);
    check(`[${theme}] sin resultados lo dice en vez de quedarse en blanco`,
      (await page.locator('text=Ningún grupo se llama así').count()) === 1);
    await page.fill('#g-search', '');
    await wait(150);

    // =====================================================================
    // Un grupo abierto
    // =====================================================================
    await page.click('#card-7');
    await wait(200);
    await shot('02-grupo');
    check(`[${theme}] al tocar una ficha se abre el grupo`,
      (await page.locator('#back-btn').count()) === 1);

    const figs = await page.locator('.fig .fl').allTextContents();
    check(`[${theme}] un grupo normal enseña mis gastos y los del grupo`,
      figs.includes('Mis gastos') && figs.includes('Gastos del grupo'), figs.join('|'));
    const misGastos = await page.locator('.fig').nth(0).locator('.fv').textContent();
    const totalGrupo = await page.locator('.fig').nth(1).locator('.fv').textContent();
    check(`[${theme}] mis gastos son menos que los del grupo`,
      parseFloat(misGastos) < parseFloat(totalGrupo), `${misGastos} / ${totalGrupo}`);

    // Quién lo hizo y a quién afecta.
    const sub = await page.locator('.row .s').first().textContent();
    check(`[${theme}] cada movimiento dice quién pagó`, /pag[óo]|recibió|→/.test(sub), sub);
    check(`[${theme}] y a quién afecta`, /entre|para/.test(sub), sub);

    // ---- Alta de movimiento: tipo, fecha y reparto ----
    const txAntes = await page.locator('.row').count();
    await page.click('button:has-text("Añadir movimiento")');
    await wait(350);
    await shot('03-alta');
    check(`[${theme}] la hoja ofrece los tres tipos`,
      (await page.locator('[data-kind]').count()) === 3);
    check(`[${theme}] la hoja tiene fecha`, (await page.locator('#date-row').count()) === 1);
    const fecha0 = await page.locator('#date-row .v').textContent();
    await page.click('#date-row');
    await wait(150);
    check(`[${theme}] la fecha se puede cambiar`,
      (await page.locator('#date-row .v').textContent()) !== fecha0);

    await page.fill('#f-amount', '30');
    await page.fill('#f-desc', 'Prueba reparto');
    await wait(200);
    const porPersona = await page.locator('.mrow .mv').first().textContent();
    check(`[${theme}] el reparto igualitario dice cuánto le toca a cada uno`,
      porPersona.includes('7,50'), porPersona);

    // Reparto por cantidades: fijar una y que el resto se reparta.
    await page.click('#mode-amounts');
    await wait(200);
    check(`[${theme}] el reparto por cantidades da un campo por persona`,
      (await page.locator('.mrow input').count()) === 4);
    await page.fill('[data-amt="u-ana"]', '15');
    await wait(200);
    const nota = await page.locator('#split-note').textContent();
    check(`[${theme}] dice cuánto queda para los demás`, nota.includes('15,00'), nota.trim());
    check(`[${theme}] con partes libres el guardado sigue disponible`,
      !(await page.locator('#save-btn').isDisabled()));

    // Fijarlas todas sin cuadrar: la API lo rechazaría, así que no se deja.
    await page.fill('[data-amt="u-ben"]', '5');
    await page.fill('[data-amt="u-cid"]', '5');
    await page.fill('[data-amt="u-dee"]', '1');
    await wait(250);
    const nota2 = await page.locator('#split-note').textContent();
    check(`[${theme}] avisa de lo que falta cuando se fijan todas`,
      nota2.includes('Faltan'), nota2.trim());
    check(`[${theme}] y no deja guardar un reparto que no suma`,
      await page.locator('#save-btn').isDisabled());
    await page.fill('[data-amt="u-dee"]', '5');
    await wait(250);
    check(`[${theme}] cuadradas, vuelve a dejar guardar`,
      !(await page.locator('#save-btn').isDisabled()));

    await page.click('#save-btn');
    await wait(400);
    check(`[${theme}] el movimiento se añade a la lista`,
      (await page.locator('.row').count()) > txAntes);
    await shot('04-alta-hecha');

    // Editar conserva el reparto desigual.
    await page.click('text=Prueba reparto');
    await wait(350);
    check(`[${theme}] al editar, el reparto desigual sigue siéndolo`,
      (await page.locator('#mode-amounts.on').count()) === 1);
    await page.click('text=Cancelar');
    await wait(250);

    // =====================================================================
    // Navegación: atrás, doble toque y memoria por pestaña
    // =====================================================================
    let back = await page.evaluate(() => window.__back());
    check(`[${theme}] atrás cierra el grupo abierto`, back === 'screen');
    await wait(200);
    check(`[${theme}] y deja la rejilla a la vista`,
      (await page.locator('.card').count()) === 3);

    await page.click('#card-7');
    await wait(200);
    await page.click('[data-tab="stats"]');
    await wait(200);
    await page.click('[data-tab="groups"]');
    await wait(200);
    check(`[${theme}] cada pestaña recuerda dónde la dejaste`,
      (await page.locator('#back-btn').count()) === 1);

    // Dos toques seguidos en la pestaña vuelven a su ventana principal.
    await page.click('[data-tab="groups"]');
    await page.click('[data-tab="groups"]');
    await wait(250);
    check(`[${theme}] el doble toque vuelve a la ventana principal`,
      (await page.locator('.card').count()) === 3);

    // Un solo toque no debe hacerlo.
    await page.click('#card-7');
    await wait(200);
    await page.click('[data-tab="groups"]');
    await wait(500);
    await page.click('[data-tab="groups"]');
    await wait(250);
    check(`[${theme}] dos toques lentos no cuentan como doble toque`,
      (await page.locator('#back-btn').count()) === 1);
    await page.evaluate(() => window.__back());
    await wait(200);

    // La hoja se lleva el gesto antes que la pantalla.
    await page.click('#card-7');
    await wait(200);
    await page.click('button:has-text("Añadir movimiento")');
    await wait(350);
    back = await page.evaluate(() => window.__back());
    check(`[${theme}] atrás cierra primero la hoja`, back === 'sheet');
    back = await page.evaluate(() => window.__back());
    check(`[${theme}] el siguiente atrás cierra el grupo`, back === 'screen');
    await wait(150);
    back = await page.evaluate(() => window.__back());
    check(`[${theme}] en la ventana principal, atrás cierra la app`, back === 'exit');

    // =====================================================================
    // Pestaña Ahorro
    // =====================================================================
    await page.click('[data-tab="savings"]');
    await wait(250);
    await shot('05-ahorro');
    const savFigs = await page.locator('.fig .fl').allTextContents();
    check(`[${theme}] ahorro enseña ingresos, gastos y balance`,
      savFigs.join('|') === 'Ingresos|Gastos|Balance', savFigs.join('|'));
    const saved = await page.evaluate(() =>
      window.__stats.savingsSummary(window.__G.find(x => x.id === 9)));
    check(`[${theme}] el balance es ingresos menos gastos`,
      Math.abs(saved.saved - (saved.income - saved.spent)) < 0.005);

    await page.click('#card-9');
    await wait(250);
    check(`[${theme}] el grupo de ahorro dice quién gasta`,
      (await page.locator('#spender-row').count()) === 1);
    await page.click('button:has-text("Añadir movimiento")');
    await wait(350);
    check(`[${theme}] en ahorro no se ofrece transferencia`,
      (await page.locator('[data-kind]').count()) === 2);
    check(`[${theme}] ni selectores de miembros: los papeles están decididos`,
      (await page.locator('.mrow').count()) === 0);
    await page.fill('#f-amount', '20');
    await page.fill('#f-desc', 'Gasto de ahorro');
    await wait(200);
    await page.click('#save-btn');
    await wait(400);
    const ahorroOk = await page.evaluate(() => {
      const g = window.__G.find(x => x.id === 9);
      const tx = g.transactions.find(t => t.description === 'Gasto de ahorro');
      const sp = g.members.find(m => m.name !== 'Ingresos');
      return tx && tx.ownerUuid === sp.uuid &&
        tx.allocations.length === 1 && tx.allocations[0].membershipUuid === sp.uuid;
    });
    check(`[${theme}] el gasto de ahorro va de quien gasta hacia quien gasta`, ahorroOk);
    await page.evaluate(() => window.__back());
    await wait(200);

    // =====================================================================
    // Estadísticas: anillo, ámbito y periodo
    // =====================================================================
    await page.click('[data-tab="stats"]');
    await wait(300);
    await shot('06-estadisticas');
    check(`[${theme}] la distribución por categoría es un anillo`,
      (await page.locator('#donut svg circle').count()) > 1);
    const leyenda = await page.locator('.legend').count();
    check(`[${theme}] el anillo lleva leyenda con nombre e importe`, leyenda > 1, `filas=${leyenda}`);
    check(`[${theme}] la leyenda dice el porcentaje de cada categoría`,
      (await page.locator('.legend .lp').first().textContent()).includes('%'));

    // La regla de color de la app: verde y rojo son del dinero, y el anillo
    // identifica categorías. Si compartieran color, una categoría cualquiera
    // se leería como un saldo a favor o en contra.
    const paletaLimpia = await page.evaluate(() => {
      const cs = getComputedStyle(document.documentElement);
      const pos = cs.getPropertyValue('--pos').trim().toLowerCase();
      const neg = cs.getPropertyValue('--neg').trim().toLowerCase();
      const strokes = [...document.querySelectorAll('#donut svg circle')]
        .map(c => (c.getAttribute('stroke') || '').toLowerCase());
      return strokes.length > 0 && !strokes.some(x => x === pos || x === neg);
    });
    check(`[${theme}] el anillo no reutiliza el verde y el rojo del dinero`, paletaLimpia);

    const totalGrupoStat = await page.locator('.hero .num').textContent();
    await page.click('button.pill:has-text("Mi parte")');
    await wait(300);
    const totalMio = await page.locator('.hero .num').textContent();
    check(`[${theme}] "mi parte" da una cifra distinta a la del grupo`,
      totalMio !== totalGrupoStat, `${totalGrupoStat} -> ${totalMio}`);
    check(`[${theme}] y menor, porque es solo lo tuyo`,
      parseFloat(totalMio) < parseFloat(totalGrupoStat));
    await shot('07-estadisticas-mias');

    await page.click('button.pill:has-text("Todo el grupo")');
    await wait(250);
    const todoElTiempo = await page.locator('.hero .num').textContent();
    await page.click('.pills:nth-of-type(2) button.pill >> nth=1');
    await wait(300);
    const unTramo = await page.locator('.hero .num').textContent();
    check(`[${theme}] filtrar por periodo cambia la cifra`,
      unTramo !== todoElTiempo, `${todoElTiempo} -> ${unTramo}`);
    await page.click('button.pill:has-text("Todo")');
    await wait(250);

    // En ahorro también hay categorías, y sin contar los ingresos.
    await page.click('button.pill:has-text("Ahorro")');
    await wait(300);
    const donutAhorro = await page.locator('#donut').count();
    check(`[${theme}] los grupos de ahorro también tienen su distribución`, donutAhorro === 1);
    const sinNomina = await page.locator('.legend .ll').allTextContents();
    check(`[${theme}] y no cuentan la nómina como gasto`,
      !sinNomina.some(t => t.toLowerCase().includes('nómina')), sinNomina.join('|'));

    // =====================================================================
    // Bandeja: tres cajones
    // =====================================================================
    await page.click('[data-tab="inbox"]');
    await wait(300);
    await shot('08-bandeja');
    const secciones = await page.locator('[data-section]').allTextContents();
    // El tercer cajón solo aparece cuando hay algo dentro: al empezar nadie
    // ha marcado nada como no bancario. Que se llene se comprueba más abajo.
    check(`[${theme}] la bandeja separa movimientos de lo demás`,
      secciones.join('|') === 'Movimientos bancarios|Otros eventos', secciones.join('|'));

    const contador = await page.locator('.hero .num').textContent();
    const bancarios = await page.evaluate(() =>
      window.__INBOX().filter(e => window.__classOf(e) === 'BANK').length);
    check(`[${theme}] el contador solo cuenta los movimientos`,
      parseInt(contador, 10) === bancarios, `${contador} vs ${bancarios}`);

    // Un aviso sin importe cae en "otros eventos" y se puede rescatar.
    await page.click('text=Nómina abonada');
    await wait(350);
    check(`[${theme}] lo descartado se puede marcar como movimiento`,
      (await page.locator('#mark-bank').count()) === 1);
    await page.click('#mark-bank');
    await wait(300);
    const vigilada = await page.evaluate(() =>
      window.__S.banks.known.includes('com.bbva.bbvacontigo') &&
      !window.__S.banks.disabled.includes('com.bbva.bbvacontigo'));
    check(`[${theme}] marcarlo como bancario deja su app vigilada`, vigilada);
    await page.click('text=Cancelar');
    await wait(250);

    // "No bancario" apaga la app de origen: es lo que pediste que hiciera.
    await page.click('text=Tu conductor está llegando');
    await wait(350);
    await page.click('#to-NON_BANK');
    await wait(350);
    const apagada = await page.evaluate(() =>
      window.__S.banks.disabled.includes('com.cabify.rider'));
    check(`[${theme}] marcar "no bancario" deja de seguir su app`, apagada);
    const enNoBancarios = await page.locator('[data-section="NON_BANK"]').count();
    check(`[${theme}] y la notificación pasa al cajón de no bancarios`, enNoBancarios === 1);
    await shot('09-bandeja-tres');

    // =====================================================================
    // Ajustes: apartados plegables
    // =====================================================================
    await page.click('[data-tab="settings"]');
    await wait(300);
    await shot('10-ajustes');
    check(`[${theme}] los apartados aparecen plegados`,
      (await page.locator('[data-secbody]').count()) === 0);
    const apartados = await page.locator('[data-sec]').allTextContents();
    check(`[${theme}] los permisos van juntos en su apartado`,
      apartados.some(t => t.includes('Permisos')), apartados.join('|'));

    check(`[${theme}] la actualización se anuncia arriba del todo`,
      (await page.locator('#update-card').count()) === 1);
    const ordenOk = await page.evaluate(() => {
      const card = document.getElementById('update-card');
      const first = document.querySelector('[data-sec]');
      return card && first && card.compareDocumentPosition(first) & Node.DOCUMENT_POSITION_FOLLOWING;
    });
    check(`[${theme}] y por encima de los apartados`, !!ordenOk);

    await page.click('[data-sec="deteccion"]');
    await wait(250);
    check(`[${theme}] al tocar un apartado se despliega`,
      (await page.locator('[data-secbody="deteccion"]').count()) === 1);
    await shot('11-ajustes-abierto');

    // Modo aprendizaje: tres estados, no un interruptor.
    const modos = [];
    for (let i = 0; i < 3; i++) {
      modos.push((await page.locator('#learn-row .v').textContent()).trim());
      await page.click('#learn-row');
      await wait(200);
    }
    check(`[${theme}] el modo aprendizaje tiene tres estados`,
      new Set(modos).size === 3, modos.join(' → '));
    check(`[${theme}] y vuelve al primero al dar la vuelta`,
      (await page.locator('#learn-row .v').textContent()).trim() === modos[0]);

    // Las apps se desactivan, no se quitan.
    const appsAntes = await page.locator('[id^="app-"]').count();
    await page.click('#app-com-bbva-bbvacontigo');
    await wait(250);
    check(`[${theme}] una app vigilada se apaga en vez de desaparecer`,
      (await page.locator('[id^="app-"]').count()) === appsAntes);
    check(`[${theme}] y se ve que está apagada`,
      (await page.locator('#app-com-bbva-bbvacontigo .v').textContent()).trim() === 'OFF');
    await page.click('#app-com-bbva-bbvacontigo');
    await wait(250);
    check(`[${theme}] y se vuelve a encender igual de fácil`,
      (await page.locator('#app-com-bbva-bbvacontigo .v').textContent()).trim() === 'ON');

    check(`[${theme}] los grupos de ahorro ya no están en Ajustes`,
      !(await page.locator('[data-sec]').allTextContents())
        .some(t => t.includes('Grupos de ahorro')));

    // =====================================================================
    // Notificación de movimiento detectado
    // =====================================================================
    await page.click('[data-sec="pruebas"]');
    await wait(250);
    await page.click('text=Simular movimiento detectado');
    await wait(400);
    await shot('12-notificacion');
    check(`[${theme}] la notificación aparece al detectar el movimiento`,
      (await page.locator('#notif.on').count()) === 1);
    check(`[${theme}] muestra importe y contraparte`,
      (await page.textContent('.nt')).includes('18,00') &&
      (await page.textContent('.nt')).includes('Ben Torres'));
    const acciones = await page.locator('.notif .acts button').count();
    check(`[${theme}] ofrece tres acciones y no más`, acciones === 3, `acciones=${acciones}`);
    check(`[${theme}] una de ellas es el grupo de ahorro más reciente`,
      (await page.locator('#notif-g9').count()) === 1);
    check(`[${theme}] y otra es elegir a mano`,
      (await page.locator('#notif-choose').count()) === 1);

    const antesDeTocar = await page.evaluate(() =>
      window.__G.reduce((n, g) => n + g.transactions.length, 0));
    await page.click('#notif-g7');
    await wait(450);
    const despuesDeTocar = await page.evaluate(() =>
      window.__G.reduce((n, g) => n + g.transactions.length, 0));
    check(`[${theme}] pulsar un grupo no crea nada todavía`,
      despuesDeTocar === antesDeTocar, `${antesDeTocar} -> ${despuesDeTocar}`);
    check(`[${theme}] abre la hoja de asignación`,
      (await page.locator('#push-btn').count()) === 1);
    check(`[${theme}] con el grupo del botón ya elegido`,
      (await page.locator('#assign-g7.on').count()) === 1);
    check(`[${theme}] y propone reembolso, que es lo que era`,
      (await page.locator('button.pill.on:has-text("Reembolso")').count()) === 1);
    await shot('13-asignar');

    await page.click('#push-btn');
    await wait(450);
    const creado = await page.evaluate(() =>
      window.__G.reduce((n, g) => n + g.transactions.length, 0));
    check(`[${theme}] al confirmar sí se crea`, creado === antesDeTocar + 1);
    check(`[${theme}] y sale de la bandeja`,
      (await page.evaluate(() => window.__INBOX().some(e => e.amount === 18))) === false);

    // =====================================================================
    // Widgets y regla de color
    // =====================================================================
    await page.click('[data-tab="settings"]');
    await wait(250);
    // El apartado sigue desplegado de antes: volver a tocarlo lo cerraría.
    if (!(await page.locator('[data-secbody="pruebas"]').count())) {
      await page.click('[data-sec="pruebas"]');
      await wait(200);
    }
    await page.click('text=Ver pantalla de inicio');
    await wait(300);
    await shot('14-widgets');
    check(`[${theme}] el widget de saldo muestra grupo y saldo`,
      (await page.locator('#w-balance .wa').count()) === 1);
    check(`[${theme}] la pantalla de inicio no muestra la barra de la app`,
      (await page.locator('.tabs').count()) === 0);
    await page.click('#w-inbox');
    await wait(250);
    check(`[${theme}] el widget "Bandeja" abre esa pestaña`,
      (await page.locator('[data-tab="inbox"].on').count()) === 1);

    // La regla de color: verde y rojo solo para dinero.
    const brandRgb = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--brand').trim());
    check(`[${theme}] la marca tiene su color propio`, /^#/.test(brandRgb), brandRgb);
    check(`[${theme}] sin errores de JS`, errors.length === 0, errors.join(' | '));
    await ctx.close();
  }
  await browser.close();
  console.log(fails.length ? `\n${fails.length} comprobaciones fallan` : '\nTodas las comprobaciones OK');
  process.exit(fails.length ? 1 : 0);
})();
