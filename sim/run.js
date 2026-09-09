const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const OUT = path.join(__dirname, 'shots');
fs.mkdirSync(OUT, { recursive: true });
const URL = require('url').pathToFileURL(path.resolve(__dirname, '..', 'prototipo-ui.html')).href;

const fails = [];
function check(name, cond, extra='') {
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

    // La pantalla de carga dura 1 s exactos
    await page.waitForTimeout(120);
    const splashAt120 = await page.locator('#splash').count();
    check(`[${theme}] la animación de carga se muestra al abrir`, splashAt120 === 1);
    await page.screenshot({ path: `${OUT}/${theme}-00-carga.png` });
    await page.waitForFunction(() => window.__splashMs != null, null, { timeout: 4000 });
    const splashMs = await page.evaluate(() => window.__splashMs);
    check(`[${theme}] la animación dura 1 s`, splashMs >= 1000 && splashMs < 1120,
          `${Math.round(splashMs)} ms`);
    await page.waitForFunction(() => !document.getElementById('splash'), null, { timeout: 3000 });
    check(`[${theme}] la carga se retira y deja ver la app`, true);
    await page.waitForTimeout(120);
    const shot = n => page.screenshot({ path: `${OUT}/${theme}-${n}.png` });

    // ---- Pestaña Grupos ----
    await shot('01-grupos');
    const heroBefore = await page.textContent('.hero .num');

    // horizontal scroll: la app no debe desbordar
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth > document.documentElement.clientWidth);
    check(`[${theme}] sin scroll horizontal`, !overflow);

    // cambio de grupo
    await page.click('text=✈️ Viaje Lisboa');
    await page.waitForTimeout(120);
    const heroLisboa = await page.textContent('.hero .num');
    check(`[${theme}] cambiar de grupo actualiza el hero`, heroLisboa !== heroBefore,
          `${heroBefore} -> ${heroLisboa}`);
    await shot('02-grupo-lisboa');

    await page.click('text=🏠 Piso Salamanca');
    await page.waitForTimeout(120);

    // pestaña interna Balance
    await page.click('button.pill:has-text("Balance")');
    await page.waitForTimeout(200);
    const balanceRows = await page.locator('.row').count();
    check(`[${theme}] balance lista miembros y liquidación`, balanceRows >= 4, `rows=${balanceRows}`);
    // la suma de saldos debe ser ~0
    const sum = await page.evaluate(() => {
      const b = window.__stats.balances(window.__G.find(x => x.id === 7));
      return Object.values(b).reduce((a, c) => a + c, 0);
    });
    check(`[${theme}] los saldos suman cero`, Math.abs(sum) < 0.02, `suma=${sum}`);
    await shot('03-balance');
    await page.click('button.pill:has-text("Movimientos")');
    await page.waitForTimeout(150);

    // ---- Crear gasto ----
    const txBefore = await page.locator('.row').count();
    await page.click('button:has-text("Añadir gasto")');
    await page.waitForTimeout(350);
    await shot('04-hoja-nuevo-gasto');
    const disabled = await page.getAttribute('#save-btn', 'disabled');
    check(`[${theme}] guardar deshabilitado con el formulario vacío`, disabled !== null);

    await page.fill('#f-amount', '24.60');
    await page.waitForTimeout(80);
    await page.fill('#f-desc', 'Cena de prueba');
    await page.waitForTimeout(80);
    await page.click('.sheet .pills button:has-text("🍔 Restaurantes")');
    await page.waitForTimeout(120);
    const perPerson = await page.textContent('.sheet .sub');
    check(`[${theme}] calcula el reparto por persona`, /6,15/.test(perPerson), perPerson);
    await shot('05-hoja-rellena');
    await page.click('#save-btn');
    await page.waitForTimeout(300);
    const txAfter = await page.locator('.row').count();
    check(`[${theme}] el gasto aparece en la lista`, txAfter === txBefore + 1,
          `${txBefore} -> ${txAfter}`);
    const toastTxt = await page.textContent('#toast');
    check(`[${theme}] confirmación visible`, /añadido/i.test(toastTxt), toastTxt);
    await shot('06-gasto-anadido');

    // ---- Editar ----
    await page.click('.row:has-text("Cena de prueba")');
    await page.waitForTimeout(320);
    const amtVal = await page.inputValue('#f-amount');
    check(`[${theme}] el editor precarga el importe`, amtVal === '24.60', amtVal);
    await page.fill('#f-amount', '30.00');
    await page.waitForTimeout(80);
    await page.click('#save-btn');
    await page.waitForTimeout(300);
    const edited = await page.textContent('.row:has-text("Cena de prueba")');
    check(`[${theme}] la edición se refleja`, /30,00/.test(edited), edited.replace(/\s+/g, ' '));

    // ---- Borrar ----
    await page.click('.row:has-text("Cena de prueba")');
    await page.waitForTimeout(320);
    await page.click('.danger');
    await page.waitForTimeout(300);
    const stillThere = await page.locator('.row:has-text("Cena de prueba")').count();
    check(`[${theme}] el gasto se elimina`, stillThere === 0);

    // ---- Estadísticas ----
    await page.click('.tab[data-tab="stats"]');
    await page.waitForTimeout(250);
    await shot('07-estadisticas-categoria');
    const bars = await page.locator('.bar i').count();
    check(`[${theme}] las barras de categoría se dibujan`, bars > 0, `bars=${bars}`);
    await page.click('button.pill:has-text("Persona")');
    await page.waitForTimeout(300);
    await shot('08-estadisticas-persona');
    await page.click('button.pill:has-text("Mes")');
    await page.waitForTimeout(300);
    await shot('09-estadisticas-mes');
    const monthLabels = await page.locator('.statrow .line span').first().textContent();
    check(`[${theme}] los meses salen en español`, /ene|feb|mar|abr|may|jun|jul|ago|sep|oct|nov|dic/.test(monthLabels), monthLabels);

    // ---- Bandeja ----
    await page.click('.tab[data-tab="inbox"]');
    await page.waitForTimeout(250);
    await shot('10-bandeja');
    // tipos de movimiento reconocidos, con su etiqueta
    const inboxText = await page.textContent('.screen');
    for (const label of ['Bizum recibido', 'Pago con tarjeta', 'Gasto en cuenta conjunta',
                         'Recibo domiciliado']) {
      check(`[${theme}] la bandeja etiqueta «${label}»`, inboxText.includes(label));
    }
    const lowRow = await page.textContent('.row:has-text("Suscripción")');
    check(`[${theme}] lo que no encaja en ninguna regla se marca «revisar»`,
          /revisar/.test(lowRow) && /Cargo/.test(lowRow), lowRow.replace(/\s+/g, ' '));
    const cardRow = await page.textContent('.row:has-text("Lefties")');
    check(`[${theme}] un pago con tarjeta muestra el comercio y va en rojo`,
          /Lefties/.test(cardRow) && /-70,94/.test(cardRow), cardRow.replace(/\s+/g, ' '));
    const jointRow = await page.textContent('.row:has-text("Amazon")');
    check(`[${theme}] un gasto conjunto muestra el comercio, no solo la persona`,
          /Amazon/.test(jointRow), jointRow.replace(/\s+/g, ' '));

    const dot = await page.locator('.dot').count();
    check(`[${theme}] la bandeja avisa con punto en la pestaña`, dot === 1);
    const recibido = await page.textContent('.row:has-text("Ben Torres")');
    check(`[${theme}] Bizum recibido en verde y con signo +`, /\+25,00/.test(recibido), recibido.replace(/\s+/g,' '));

    await page.click('button:has-text("Conceder permiso")');
    await page.waitForTimeout(250);
    await shot('11-bandeja-con-permiso');

    // asignar un Bizum
    const inboxBefore = await page.evaluate(() => window.__INBOX().length);
    await page.click('.row:has-text("Ben Torres")');
    await page.waitForTimeout(350);
    const tipoReimb = await page.locator('.sheet .pill.on:has-text("Reembolso")').count();
    check(`[${theme}] un Bizum se propone como reembolso`, tipoReimb === 1);
    await shot('12-asignar-bizum');
    const pushDisabled = await page.getAttribute('#push-btn', 'disabled');
    check(`[${theme}] enviar bloqueado sin destinatario`, pushDisabled !== null);
    // elegir receptor
    const recvPills = page.locator('.sheet .section:has-text("Quién lo recibe") + .pills');
    await recvPills.locator('button:has-text("Ben")').click();
    await page.waitForTimeout(150);
    await shot('13-asignar-completo');
    await page.click('#push-btn');
    await page.waitForTimeout(350);
    const left = await page.evaluate(() => window.__INBOX().length);
    check(`[${theme}] el movimiento sale de la bandeja`, left === inboxBefore - 1,
          `${inboxBefore} -> ${left}`);
    await shot('14-bandeja-tras-enviar');

    // ---- Ajustes ----
    await page.click('.tab[data-tab="settings"]');
    await page.waitForTimeout(250);
    await shot('15-ajustes');
    const granted = await page.textContent('.row:has-text("Acceso a notificaciones")');
    check(`[${theme}] ajustes refleja el permiso concedido`, /Concedido/.test(granted));
    await page.click('.row:has-text("Modo aprendizaje")');
    await page.waitForTimeout(200);
    const learn = await page.textContent('.row:has-text("Modo aprendizaje")');
    check(`[${theme}] modo aprendizaje conmuta`, /ON/.test(learn));

    // ---- Reglas de aviso ----
    const debitPolicy = await page.evaluate(() => {
      const r = [...document.querySelectorAll('.row')]
        .find(x => x.textContent.includes('Recibo domiciliado'));
      return r ? r.querySelector('.v').textContent.trim() : null;
    });
    check(`[${theme}] los recibos domiciliados no avisan por defecto`,
          debitPolicy === 'Solo bandeja', String(debitPolicy));

    await page.click('.row:has-text("Pago con tarjeta")');
    await page.waitForTimeout(200);
    const cardPolicy = await page.evaluate(() => {
      const r = [...document.querySelectorAll('.row')]
        .find(x => x.textContent.includes('Pago con tarjeta'));
      return r.querySelector('.v').textContent.trim();
    });
    check(`[${theme}] la política de un tipo se puede cambiar`,
          cardPolicy === 'Solo bandeja', cardPolicy);
    await page.click('.row:has-text("Pago con tarjeta")');
    await page.click('.row:has-text("Pago con tarjeta")');
    await page.waitForTimeout(200);
    await shot('20-reglas-aviso');

    // silenciar una suscripción desde la bandeja
    await page.click('.tab[data-tab="inbox"]');
    await page.waitForTimeout(250);
    const beforeMute = await page.locator('.row').count();
    await page.click('.row:has-text("Suscripción")');
    await page.waitForTimeout(400);
    await page.click('#mute-btn');
    await page.waitForTimeout(450);
    const afterMute = await page.locator('.row').count();
    check(`[${theme}] silenciar un origen lo saca de la bandeja`,
          afterMute === beforeMute - 1, `${beforeMute} -> ${afterMute}`);
    const mutedToast = await page.textContent('#toast');
    check(`[${theme}] confirma el silenciado`, /silenciado/.test(mutedToast), mutedToast);

    await page.click('.tab[data-tab="settings"]');
    await page.waitForTimeout(250);
    const mutedList = await page.textContent('.screen');
    check(`[${theme}] el silenciado aparece en Ajustes y se puede reactivar`,
          /Suscripci/i.test(mutedList) && /Reactivar/.test(mutedList));
    await page.click('.row:has-text("Reactivar")');
    await page.waitForTimeout(250);
    await page.click('.tab[data-tab="inbox"]');
    await page.waitForTimeout(250);
    const restored = await page.locator('.row').count();
    check(`[${theme}] al reactivarlo vuelve a la bandeja`, restored === beforeMute,
          `${afterMute} -> ${restored}`);
    await page.click('.tab[data-tab="settings"]');
    await page.waitForTimeout(200);

    // nombre propio: filtra los movimientos entre tus cuentas
    await page.click('.row:has-text("Tu nombre en el banco")');
    await page.waitForTimeout(350);
    await page.fill('#f-ownname', 'Alex Ruiz Moreno');
    await page.waitForTimeout(80);
    await page.click('#save-ownname');
    await page.waitForTimeout(320);
    const nameRow = await page.textContent('.row:has-text("Tu nombre en el banco")');
    check(`[${theme}] se guarda tu nombre para ignorar tus propios traspasos`,
          /Alex Ruiz Moreno/.test(nameRow), nameRow.replace(/\s+/g, ' '));

    // ---- Coherencia de marca ----
    const brandHex = theme === 'dark' ? 'rgb(74, 108, 255)' : 'rgb(51, 85, 230)';
    const learnColor = await page.evaluate(() => {
      const row = [...document.querySelectorAll('.row')]
        .find(r => r.textContent.includes('Modo aprendizaje'));
      return getComputedStyle(row.querySelector('.v')).color;
    });
    check(`[${theme}] los estados que no son dinero usan el azul de marca`,
          learnColor === brandHex, learnColor);
    const aboutMark = await page.locator('.mark').count();
    check(`[${theme}] la marca aparece en Ajustes`, aboutMark >= 1, `marcas=${aboutMark}`);

    await page.click('.tab[data-tab="stats"]');
    await page.waitForTimeout(250);
    const barColor = await page.evaluate(() =>
      getComputedStyle(document.querySelector('.bar i')).backgroundColor);
    check(`[${theme}] las barras de estadísticas usan el azul de marca`,
          barColor === brandHex, barColor);

    await page.click('.tab[data-tab="inbox"]');
    await page.waitForTimeout(200);
    const moneyColors = await page.evaluate(() => {
      const v = [...document.querySelectorAll('.row .v')].map(e => getComputedStyle(e).color);
      return v;
    });
    check(`[${theme}] los importes siguen en verde/rojo, no en azul`,
          moneyColors.length > 0 && moneyColors.every(c => c !== brandHex),
          moneyColors.join(' '));
    const dotColor = await page.evaluate(() =>
      getComputedStyle(document.querySelector('.dot')).backgroundColor);
    check(`[${theme}] el aviso de la pestaña usa el azul de marca`,
          dotColor === brandHex, dotColor);


    // ---- Notificación de movimiento detectado ----
    await page.click('.tab[data-tab="settings"]');
    await page.waitForTimeout(200);
    await page.click('.row:has-text("Simular movimiento detectado")');
    await page.waitForTimeout(450);
    await shot('16-notificacion');
    // la barra de pestañas nunca debe salirse de la pantalla
    const tabsInView = await page.evaluate(() => {
      const r = document.querySelector('.tabs').getBoundingClientRect();
      return r.bottom <= window.innerHeight + 1 && r.top >= 0;
    });
    check(`[${theme}] la barra de pestañas queda siempre visible`, tabsInView);
    const notifBox = await page.evaluate(() => {
      const r = document.querySelector('.notif').getBoundingClientRect();
      return { top: r.top, right: r.right, w: window.innerWidth };
    });
    check(`[${theme}] la notificación se ve entera`,
          notifBox.top >= 0 && notifBox.right <= notifBox.w + 1, JSON.stringify(notifBox));
    const notifVisible = await page.locator('.notif.on').count();
    check(`[${theme}] la notificación aparece al detectar el movimiento`, notifVisible === 1);
    const notifTxt = await page.textContent('.notif');
    check(`[${theme}] la notificación muestra importe y contraparte`,
          /18,00/.test(notifTxt) && /Ben Torres/.test(notifTxt), notifTxt.replace(/\s+/g,' '));
    const groupBtns = await page.locator('.notif .acts button.primary').count();
    check(`[${theme}] ofrece los grupos como botones`, groupBtns === 2, `botones=${groupBtns}`);

    // asignar desde la notificación, sin abrir la app
    const beforeAssign = await page.evaluate(() =>
      window.__G.find(x => x.id === 7).transactions.length);
    await page.click('#notif-g7');
    await page.waitForTimeout(350);
    await shot('17-notificacion-confirmada');
    const afterAssign = await page.evaluate(() =>
      window.__G.find(x => x.id === 7).transactions.length);
    check(`[${theme}] el movimiento se crea en el grupo`, afterAssign === beforeAssign + 1);
    const doneTxt = await page.textContent('.notif');
    check(`[${theme}] un Bizum de un miembro se crea como reembolso`, /reembolso/.test(doneTxt), doneTxt.replace(/\s+/g,' '));
    check(`[${theme}] la confirmación nombra el grupo`, /Piso Salamanca/.test(doneTxt));

    // deshacer
    await page.click('#undo-btn');
    await page.waitForTimeout(350);
    const afterUndo = await page.evaluate(() =>
      window.__G.find(x => x.id === 7).transactions.length);
    check(`[${theme}] deshacer revierte el movimiento`, afterUndo === beforeAssign);
    const backToOffer = await page.locator('.notif .acts button.primary').count();
    check(`[${theme}] tras deshacer vuelve a ofrecer los grupos`, backToOffer === 2);
    await page.click('.notif .acts button:has-text("Elegir…")');
    await page.waitForTimeout(400);
    const sheetOpen = await page.locator('.sheet.on').count();
    check(`[${theme}] "Elegir…" abre la hoja de asignación`, sheetOpen === 1);
    await page.click('.sheet-head span:has-text("Cancelar")');
    await page.waitForTimeout(320);

    // ---- Widgets ----
    await page.click('.tab[data-tab="settings"]');
    await page.waitForTimeout(200);
    await page.click('.row:has-text("Ver pantalla de inicio")');
    await page.waitForTimeout(300);
    await shot('18-widgets');
    const wBalance = await page.textContent('#w-balance');
    check(`[${theme}] el widget de saldo muestra grupo y saldo`,
          /Piso Salamanca/.test(wBalance) && /€/.test(wBalance), wBalance.replace(/\s+/g,' '));
    const tabsHidden = await page.locator('.tabs').count();
    check(`[${theme}] la pantalla de inicio no muestra la barra de la app`, tabsHidden === 0);
    await page.click('#w-quickadd');
    await page.waitForTimeout(420);
    const addSheet = await page.locator('.sheet.on:has-text("Nuevo gasto")').count();
    check(`[${theme}] el widget "+" abre la hoja de nuevo gasto`, addSheet === 1);
    await shot('19-widget-abre-gasto');
    await page.click('.sheet-head span:has-text("Cancelar")');
    await page.waitForTimeout(320);
    await page.click('.tab[data-tab="settings"]');
    await page.waitForTimeout(150);
    await page.click('.row:has-text("Ver pantalla de inicio")');
    await page.waitForTimeout(250);
    await page.click('#w-inbox');
    await page.waitForTimeout(300);
    const onInbox = await page.locator('.tab.on[data-tab="inbox"]').count();
    check(`[${theme}] el widget "Bandeja" abre esa pestaña`, onInbox === 1);

    check(`[${theme}] sin errores de JS`, errors.length === 0, errors.join(' | '));
    await ctx.close();
  }
  await browser.close();
  console.log('\n' + (fails.length ? `${fails.length} fallos: ${fails.join(', ')}` : 'Todas las comprobaciones OK'));
  process.exit(fails.length ? 1 : 0);
})();
