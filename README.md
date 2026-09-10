# SmartCount

App Android (Kotlin + Compose) que se conecta a **Tricount** para:

- ver tus grupos en una rejilla, sus movimientos y el balance de cada miembro,
- **crear, editar y eliminar gastos** sin abrir Tricount,
- leer un grupo como **grupo de ahorro**: ingresos, gastos y lo que queda,
- **detectar automáticamente notificaciones de Bizum y transferencias**, dejarlas en
  una bandeja de entrada y enviarlas a uno o varios grupos (siempre con confirmación).

---

## Aviso importante

Tricount (propiedad de bunq) **no tiene API pública**. Esta app usa la **API interna**
que emplea la app oficial de Android. Consecuencias:

- puede dejar de funcionar tras cualquier actualización del servidor,
- su uso queda fuera de los términos de servicio de Tricount,
- es para uso personal; no la publiques en Google Play.

## Protocolo (documentado a partir de ingeniería inversa de la app oficial)

Base: `https://api.tricount.bunq.com`
User-Agent: `com.bunq.tricount.android:RELEASE:7.0.7:3174:ANDROID:13:C`

**Autenticación.** No hay usuario ni contraseña: se registra una *instalación*.

```
POST /v1/session-registry-installation
{ "app_installation_uuid": "<uuid v4>",
  "client_public_key": "<clave pública RSA 2048 en PKCS#1 PEM>",
  "device_description": "Android" }
```

Devuelve un `Token` (cabecera `X-Bunq-Client-Authentication`) y un `UserPerson.id`.
Cabeceras en todas las llamadas: `app-id`, `X-Bunq-Client-Request-Id`, `User-Agent`.

> Java genera la clave pública en X.509/SPKI, pero la API exige **PKCS#1**
> (`BEGIN RSA PUBLIC KEY`). `util/Pkcs1.kt` construye ese DER a mano
> (`SEQUENCE { INTEGER modulus, INTEGER exponent }`) para no depender de BouncyCastle.
> Salida verificada byte a byte contra la de OpenSSL/`cryptography`.

**Endpoints usados**

| Acción | Método y ruta |
|---|---|
| Listar grupos | `GET /v1/user/{uid}/registry` |
| Leer por enlace público | `GET /v1/user/{uid}/registry?public_identifier_token=…` |
| Unirse a un grupo | `POST /v1/user/{uid}/registry-synchronization` |
| Crear grupo | `POST /v1/user/{uid}/registry` |
| Crear movimiento | `POST /v1/user/{uid}/registry/{id}/registry-entry` |
| Editar movimiento | `PUT  …/registry-entry/{txId}` |
| Borrar movimiento | `DELETE …/registry-entry/{txId}` |

**Convenios de importe** (fáciles de equivocar):

- los importes van como **cadena, en unidades completas** (`"-12.50"`, no céntimos),
- los **gastos son negativos**, los ingresos positivos,
- `type_transaction`: `NORMAL` (gasto), `INCOME` (ingreso), `BALANCE` (reembolso
  entre miembros — el tipo natural para un Bizum entre gente del grupo),
- fecha: `yyyy-MM-dd HH:mm:ss.SSSSSS`,
- cada `allocation` lleva el `membership_uuid` y su parte, con el mismo signo que el total.

**Identidad.** Las credenciales (uuid + clave) se guardan cifradas con la keystore
del dispositivo. Si las borras pierdes el acceso a los grupos sincronizados con esa
instalación: hay que volver a unirse con el enlace público. Ajustes permite exportarlas.

**`membership_uuid_active` llega `null` en los grupos a los que te uniste por
enlace.** Es el campo que dice cuál de los miembros eres tú, y de él salía el
balance que la pantalla enseña en grande. Sin él `linkedMember` es `null`, el
balance se queda en 0,00 y parece que la app no sabe calcularlo — el motivo real
de que "en algunos grupos no se detecte el balance". Comprobado contra la API:
un grupo unido por enlace devuelve sus dos miembros con nombre y uuid, y
`"membership_uuid_active": null`.

No se puede adivinar siempre: en un grupo de cinco personas no hay forma de saber
cuál eres. `MemberIdentity` lo resuelve en tres pasos, del más fiable al menos:

1. lo que diga la API,
2. lo que hayas elegido a mano (se guarda solo en este móvil),
3. una deducción, y **solo cuando es inequívoca**: el miembro que se llama como tú
   en el banco (Ajustes → *Tu nombre*), o el otro de un grupo de dos donde uno es
   la fuente de ingresos.

Si ninguno acierta, el grupo lo pregunta en vez de mentir con un cero. La
resolución se aplica en el cliente, no en cada pantalla: así también la
aprovechan la caché del widget y la asignación rápida desde la notificación,
que no pasan por la interfaz.

## Movimientos: gasto, ingreso y transferencia

La hoja de alta ofrece los tres tipos que entiende Tricount, y cada uno cambia
los campos que pide, porque no comparten forma:

| Tipo | API | Campos | Se reparte |
|---|---|---|---|
| **Gasto** | `NORMAL` | pagado por, repartido entre, categoría | sí, en negativo |
| **Ingreso** | `INCOME` | recibido por, repartido entre, categoría | sí, en positivo |
| **Transferencia** | `BALANCE` | de, a | no: una sola asignación |

Una transferencia no lleva categoría porque no es un gasto de nada: es dinero
que cambia de manos dentro del grupo. Y exige dos personas distintas — la hoja
no deja guardar si coinciden.

**Al editar, el tipo queda fijo.** La API edita cada tipo por su propio camino
(el signo del importe y la forma de las asignaciones dependen de él), así que
cambiar de tipo es borrar y volver a crear, no editar.

## Balance y liquidación

La pestaña **Balance** de un grupo tiene dos mitades: el saldo de cada persona y
el plan para dejarlo a cero.

**El saldo** se calcula en el móvil a partir de los movimientos descargados.
Cada uno suma a favor de quien pone el dinero y en contra de quien se lo lleva,
pero con qué signo depende del tipo, y ahí es donde la API engaña: guarda los
gastos en negativo y los ingresos en positivo, pero el reembolso `BALANCE`
**también en positivo** aunque cuente como un gasto. De ahí el signo explícito
de `Stats.signOf`:

| Tipo | Quién es el propietario | Efecto en el balance |
|---|---|---|
| **Gasto** | quien paga | queda a favor; los del reparto, a deber |
| **Ingreso** | quien cobra | **pasa a deber** ese dinero; los del reparto, a favor |
| **Transferencia** | quien envía | queda a favor; quien recibe, a deber |

El ingreso va al revés que el gasto a propósito: si el casero devuelve la fianza
a una sola persona, ese dinero es del grupo y quien lo tiene en el bolsillo se
lo debe al resto.

El saldo se calcula **por uuid** y solo después se agrupa por nombre, porque dos
miembros pueden llamarse igual y sumarlos antes daría el saldo de los dos juntos.

**La liquidación** empareja al mayor deudor con el mayor acreedor hasta que no
queda nadie descuadrado, lo que da el menor número de pagos posible. No es solo
informativa: cada pago se registra tocándolo, y *Saldar todo* registra el plan
entero de una vez. Lo que se crea es una **transferencia** normal (`BALANCE`),
no un apunte aparte — el plan es un cálculo, no un dato, así que en cuanto el
pago existe como movimiento el saldo se recalcula solo y ese pago desaparece del
plan. El resto del grupo lo ve igual desde la app oficial de Tricount.

Los pagos se crean uno a uno y en orden. Si uno falla, los anteriores quedan
hechos: son movimientos válidos por sí mismos, y el plan que queda después ya
solo propone lo que falte.

## Las pestañas

| Pestaña | Qué es |
|---|---|
| **Grupos** | Rejilla de dos columnas con todos los grupos y su cifra. Al tocar uno se abre |
| **Ahorro** | Solo los grupos de ahorro, con ingresos, gastos y balance de cada uno y del conjunto |
| **Estadísticas** | Separadas en grupos normales y grupos de ahorro |
| **Bandeja** | Lo detectado, agrupado en movimientos y no-movimientos |
| **Ajustes** | Detección, avisos, silenciados, grupos de ahorro y actualizaciones |

La rejilla sustituye a la tira horizontal de chips que había antes: con más de
tres o cuatro grupos había que desplazarla a ciegas para encontrar el que se
busca, y no decía nada de cada uno. Cada ficha lleva ya la cifra que define al
grupo — lo que te deben, o el balance si es de ahorro — que es a lo que se
entraba.

## Grupos de ahorro

Un grupo de ahorro **no es un tipo de grupo de Tricount**: es un grupo normal
leído de otra manera, y la marca vive solo en este móvil. Para Tricount sigue
siendo un grupo con sus movimientos, así que la app oficial lo abre sin
enterarse de nada. Se convierte, y se revierte, con el botón **Ahorro** del
propio grupo o desde Ajustes.

La convención es la que hace el trabajo:

- una **fuente de ingresos**: un miembro que suele llamarse *Ingresos*,
- una **fuente de gastos**: tú,
- lo que sale de la fuente de ingresos son los ingresos,
- lo demás son los gastos, y el **balance** es la resta.

Al convertir un grupo se añade el miembro *Ingresos* **solo si no hay ya una
fuente de ingresos**. Se aceptan varias formas del nombre — *Ingresos*,
*Ingreso*, *Income*, *Nómina* — porque los grupos reales no respetan la
convención al pie de la letra: el grupo con el que se probó esto tiene el
miembro en singular, y exigir el plural exacto lo dejaba fuera y le añadía un
segundo miembro que no hacía falta. Y cuando el nombre no se parece a ninguna
de esas formas, la fuente **se elige a mano** desde el propio grupo.

**Se mira el propietario de cada movimiento, no su tipo.** Es lo que distingue
de qué lado viene el dinero sea cual sea el tipo con el que se creó: un ingreso
registrado como `INCOME` y un reembolso `BALANCE` de *Ingresos* hacia ti son la
misma cosa para el ahorro, y filtrar por tipo dejaría fuera uno de los dos.

Qué cambia en la pantalla del grupo:

- la cifra grande es el **balance**, no lo que te deben — en un grupo de ahorro
  no hay deudas que saldar,
- debajo, **ingresos, gastos y balance** en tres cifras,
- cada movimiento se pinta según de qué lado viene el dinero: **verde** lo que
  entra, **rojo** lo que sale. En un grupo normal se quedan en negro, porque
  allí un gasto no es una mala noticia sino el material del que está hecho el
  grupo, y pintarlo todo de rojo no informaría de nada,
- desaparece la pestaña *Balance*, que no significa nada aquí,
- la hoja de alta se queda en dos botones, *Gasto* e *Ingreso*, y sin selectores
  de miembros: los papeles ya están decididos.

La pestaña **Ahorro** los junta todos: el balance del conjunto arriba, con sus
ingresos y gastos, y debajo cada grupo con sus tres cifras y una barra que dice
cuánto de lo ingresado sigue ahí. El total solo se enseña si todos los grupos
comparten moneda; sumar euros y libras daría una cifra falsa, así que en ese
caso se dice y se remite a cada grupo.

## Detección de movimientos

`NotificationListenerService` escucha las notificaciones de las apps de banco que
elijas y `MovementParser` las convierte en movimientos. Nada sale del móvil: lo
detectado va a una bandeja local y solo se envía a Tricount cuando lo confirmas.

El parser está escrito **contra notificaciones reales** de Revolut, Trade Republic
y BBVA, no contra frases inventadas, porque el formato real trae tres trampas que
no se ven hasta que miras los datos:

1. **Android duplica el título** (`"AmazonAmazon"`, `"Conjunta · SUNLU  Conjunta · SUNLU  "`).
   Hay que plegarlo antes de leer nada.
2. **Revolut añade el saldo en la segunda línea** (`"Has gastado 9,99 €\nSaldo de
   EUR: 1.056,42 €"`). Si no se descarta esa línea, el importe que se lee es el
   saldo de la cuenta, no el del movimiento.
3. **Muchos avisos llevan importe y no son un movimiento**: recordatorios de pagos
   futuros, pagos denegados, retenciones, ofertas de fraccionamiento, intereses y
   planes de ahorro. Todos se descartan con reglas explícitas.

Además reconoce importes en formato español (`1.234,56`) e inglés (`1,000.00`,
`€13.89`), con el símbolo delante o detrás — Trade Republic notifica en inglés.

**El reparto entre título y texto no es fijo**, así que las reglas se prueban
sobre el texto, sobre el título y sobre la unión en ambos sentidos. Antes de
esto, intercambiar los dos campos hacía caer el reconocimiento de 27/38 a 0.

**Dos niveles de confianza.** Primero las reglas concretas (confianza alta). Si
ninguna encaja pero hay importe y una pista de dirección (`has gastado`,
`recibido`, `adeudo`, `spent`, `suscripción`…), el movimiento se guarda igual
con la dirección deducida y la bandeja lo marca **«revisar»**. Vale más un
movimiento a revisar que un movimiento perdido.

### Sensibilidad por app

`python sim/eval_parser.py` mide el reconocimiento sobre el corpus real, sobre
el mismo corpus con los campos intercambiados, y sobre un juego de **variantes
hipotéticas** (`sim/variantes_hipoteticas.tsv`): redacciones plausibles que aún
no han aparecido en ninguna notificación real, sobre todo los Bizum y
transferencias de BBVA. Están ahí para ejercitar las reglas, no como prueba de
cobertura real — hasta que aparezca una notificación auténtica, esas reglas son
hipótesis.

| App | Corpus real | Comentario |
|---|---|---|
| Revolut | 13 de 14 | El descartado es un recordatorio de pago futuro |
| Trade Republic | 10 de 14 | Los 4 restantes son intereses, plan de ahorro y dos avisos comerciales |
| BBVA | 4 de 10 | Los 6 restantes son pago denegado, retención, oferta de fraccionamiento y tres avisos sin importe (nómina, ingreso en efectivo, ingreso por transferencia) |

Los avisos de BBVA sin importe son un límite real del canal: el banco no lo
pone en la notificación, así que no hay nada que extraer. Aparecen en la
bandeja solo con el modo aprendizaje activado.

**Tipos que distingue**, porque cada uno se convierte en algo distinto en Tricount:

| Tipo | Ejemplo real | Se propone como |
|---|---|---|
| Bizum recibido / enviado | `X sent you a Bizum of 3.50 €` | Reembolso, si la contraparte está en el grupo |
| Transferencia recibida / enviada | `Has enviado 2,50 € a X` | Reembolso, misma condición |
| Pago con tarjeta | `Spent €70.94 at Lefties` | Gasto repartido |
| Gasto en cuenta conjunta | `Nuria ha gastado 75,91 €` (título: `Cuenta Conjunta · Amazon`) | Gasto repartido |
| Recibo domiciliado | `un adeudo de Simyo de 2,50 EUR` | Gasto repartido |
| Devolución | `Devolución aceptada de 269,00 EUR en …` | Gasto repartido (a corregir) |
| Pago ajustado | `Your payment was adjusted to €32.70` | Gasto repartido (gasolineras) |
| Retirada / ingreso en cuenta conjunta | `X ha retirado 29,08 € de vuestra Cuenta Conjunta` | Gasto repartido |

Separa **contraparte** (la persona) de **comercio** (el título de la notificación),
que en las cuentas conjuntas son cosas distintas: quien gasta es una persona, pero
el gasto es en Amazon.

**Movimientos entre tus propias cuentas.** Pasar dinero de un banco a otro llega
como si alguien te hubiera enviado un Bizum. En Ajustes → *Tu nombre en el banco*
pones tu nombre y esos movimientos dejan de llegar a la bandeja; la comparación
ignora tildes, mayúsculas y el orden de los apellidos.

### La bandeja calibra el parser

La bandeja enseña **dos grupos**: lo que se ha reconocido como movimiento
bancario y lo que no. Y cualquiera de los dos se mueve al otro con un toque, con
esa decisión mandando sobre la del parser a partir de ahí.

Enseñar también lo descartado es lo que convierte la bandeja en el sitio donde
se afina el sistema y no solo donde se recogen resultados. El parser se equivoca
en las dos direcciones y las dos equivocaciones no cuestan igual: un aviso
comercial colado entre los movimientos se aparta de un toque, pero **un
movimiento descartado por error se perdía sin dejar rastro** — los avisos de
nómina de BBVA, que llegan sin importe, son el caso de libro. Ahora se rescatan.

Para que haya algo que calibrar, las notificaciones de las apps vigiladas se
guardan **aunque el parser no las entienda**, no solo en modo aprendizaje. El
volumen queda acotado porque solo vienen de las apps que has elegido. La chapa
de la pestaña cuenta únicamente los movimientos: contarlo todo la llenaría de
avisos comerciales que nadie va a asignar.

- El permiso **Acceso a notificaciones** se concede a mano en los ajustes del sistema.
- La lista de bancos es una semilla (Revolut, Trade Republic, BBVA, CaixaBank,
  Santander y otros); si el tuyo no aparece o cambió de package, activa **Modo
  aprendizaje** en Ajustes: registra todas las notificaciones para que puedas
  identificar el paquete y añadirlo.
- Deduplicación por hash del contenido en una ventana de 5 minutos: los bancos
  republican la misma notificación al actualizarla.

## Widgets

Dos widgets escritos con **Glance** (Compose para pantalla de inicio), con el
mismo lenguaje visual que la app y modo claro/oscuro:

- **Saldo del grupo** (3×2): grupo activo, cuánto te deben o debes, y dos
  accesos directos: *+ Gasto* y *Bandeja* (con el número de pendientes).
- **Añadir gasto** (1×1): abre directamente la hoja de nuevo gasto.

Ambos leen `GroupCache`, una instantánea en disco de los grupos y sus balances,
porque el widget se dibuja en el proceso del *launcher* y no puede hacer red.
La caché se reescribe tras cada carga de grupos y tras cada asignación, y
entonces se repintan los widgets.

## Qué avisa y qué no

No todos los movimientos merecen una notificación: una suscripción mensual o el
recibo del móvil no se reparten con nadie. Se controla en dos niveles, porque el
tipo de movimiento no basta — **una suscripción llega como un pago con tarjeta
cualquiera y lo único que la distingue es el comercio**.

**Por tipo** (Ajustes → *De qué te avisamos*). Cada tipo alterna entre tres
estados tocándolo:

| Estado | Efecto |
|---|---|
| **Avisar** | Notificación con los grupos como botones, y a la bandeja |
| **Solo bandeja** | Sin notificación, pero queda disponible para asignarlo |
| **Ignorar** | Ni notificación ni bandeja |

Por defecto avisan los movimientos que suelen compartirse (Bizum, transferencias,
pagos con tarjeta, gastos de cuenta conjunta) y quedan en *solo bandeja* los
recurrentes o los de confianza baja (recibos domiciliados, ingresos y cargos sin
identificar).

**Por origen** (Ajustes → *Silenciados*). Un comercio o una persona silenciados
dejan de avisar y de llegar a la bandeja. Lo importante es cómo se silencian: la
propia notificación trae un botón **«No avisar de Netflix»**, así que la primera
vez que te moleste una suscripción la callas desde la pantalla de bloqueo, sin
abrir la app. El silenciado se confirma con otra notificación que ofrece
**Deshacer** 30 segundos, y en Ajustes se puede reactivar. La comparación de
nombres ignora tildes, mayúsculas y puntuación, para que «Filmin » y «filmin»
sean el mismo comercio.

También está disponible dentro de la app, en la hoja de asignación de cada
movimiento: *No volver a avisar de «X»*.

## Notificación al detectar un movimiento

Cuando el `NotificationListenerService` reconoce un Bizum o una transferencia,
SmartCount lanza su propia notificación accionable:

```
SMARTCOUNT
Bizum recibido 18,00 € · Ben Torres
«entradas» · ¿a qué grupo lo llevas?
[ Piso Salamanca ]  [ Viaje Lisboa ]  [ Elegir… ]
```

- Los dos grupos más probables (el activo primero) van como botones: un toque
  desde la pantalla de bloqueo y listo, sin abrir la app.
- *Elegir…* abre la app en la bandeja, con la hoja de ese movimiento ya abierta,
  y allí el movimiento puede ir a **varios grupos a la vez** y con **la persona
  que elijas** en cada uno. El recibo de la luz va al piso y al grupo de ahorro,
  y hacerlo dos veces obligaba a repetir importe y descripción a mano. Los
  miembros de un grupo no son los del otro, así que *quién paga* y *entre
  quiénes se reparte* se deciden grupo a grupo.

Por defecto **avisa todo movimiento reconocido**. Las dos excepciones no son
movimientos que repartir: lo que mueves entre tus propias cuentas no cambia de
manos, y lo que el parser no supo leer no tiene ni importe que ofrecer.
- *No avisar de X* silencia ese comercio o esa persona para siempre.
- Tras asignar, la notificación se sustituye por una confirmación con
  **Deshacer** durante 30 segundos, que borra el movimiento en Tricount y
  devuelve la entrada a la bandeja.

Qué se crea al pulsar un grupo (`QuickAssignReceiver.planFor`):

| Caso | Movimiento |
|---|---|
| Bizum enviado a alguien del grupo | Reembolso tú → esa persona |
| Bizum recibido de alguien del grupo | Reembolso esa persona → tú |
| Contraparte que no está en el grupo | Gasto repartido entre todos, pagado por ti |
| Transferencia, o tipo desconocido | Gasto repartido entre todos, pagado por ti |

El emparejamiento de nombres tolera que el banco diga «BEN TORRES» y el grupo
solo «Ben». Todo queda en la bandeja como enviado, así que se puede corregir
después en la app.

## Actualización automática

Un `git push` a `main` acaba convertido en una actualización instalada en el
móvil, sin descargar ningún APK a mano.

```
push a main → GitHub Actions compila y FIRMA la APK → Release v<name>-b<code>
                                                          │
        la app, al arrancar: consulta la API, compara, descarga, instala
```

**Android no deja instalar en silencio** a una app normal: eso exige ser *device
owner* o app de sistema. Comprobar, descargar y preparar sí es automático; el
último paso es siempre un diálogo del sistema que confirma la persona.

**El `versionCode` es el número de commits** (`git rev-list --count HEAD`), no la
versión semántica: crece solo y nadie tiene que acordarse de subirlo. La
contrapartida es que no se puede reescribir la historia de `main` — un `rebase`
o un `push --force` que reduzca el número de commits deja las releases nuevas
por debajo de lo ya instalado y dejan de verse como actualizaciones. Hay un
suelo (`versionCodeFloor`) para que un checkout superficial en CI no publique un
`versionCode` 1 y rompa el canal en silencio.

**La versión se lee de la etiqueta de la release** (`v0.1.0-b23` → 23), que ya
viene en la respuesta de la API. Una petición y ningún metadato suelto que
mantener sincronizado.

**La comprobación del arranque se calla sus errores**: sin cobertura, o si
GitHub responde 403, la app sigue funcionando sin molestar. El precio es que un
fallo real se ve igual que «no hay novedades», así que Ajustes lleva una
comprobación **manual** que sí cuenta lo que ocurre — versión encontrada, ya al
día, o el error exacto. No es un adorno: es la única forma de distinguir «no hay
nada» de «está roto».

**El permiso de instalar apps desconocidas se concede fuera de la app**, en una
pantalla del sistema, y nada dentro avisa de que ha cambiado. Se relee en cada
`ON_RESUME`; como la APK ya está descargada, al volver el botón dice
directamente «Instalar».

Requisitos para que el canal funcione:

- **El repositorio debe ser público.** La app consulta la API sin credenciales;
  en uno privado recibiría un 404 y no ofrecería nada nunca. Meter un token en
  la app no es una opción.
- **Una clave de firma propia**, en `keystore.properties` (local) o en los
  secrets `SMARTCOUNT_KEYSTORE_BASE64`, `SMARTCOUNT_STORE_PASSWORD`,
  `SMARTCOUNT_KEY_ALIAS` y `SMARTCOUNT_KEY_PASSWORD` (CI). Si se pierde, ningún
  dispositivo con la app instalada podrá actualizarse nunca más.

## Compilar y probar

Requisitos: **JDK 17 o superior**, **Android SDK** con la plataforma **API 35**,
**Node 18+** (comprobaciones de interfaz) y **Python 3.10+** (comprobaciones del
parser y de los payloads). Gradle lo descarga el wrapper.

```bash
# 1. Apuntar al SDK de Android (o definir ANDROID_HOME)
echo "sdk.dir=C:/Users/TU_USUARIO/AppData/Local/Android/Sdk" > local.properties

# 2. APK de depuración -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug          # gradlew.bat en Windows

# 3. Instalar en un móvil o emulador conectado
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Comprobaciones:

```bash
python sim/parser_check.py    # 38 notificaciones reales
python sim/eval_parser.py     # sensibilidad y variantes hipotéticas
python sim/plan_check.py      # decisión de la asignación rápida
python sim/balance_check.py   # balance neto y plan de liquidación
pip install tricount-api
python sim/payload_check.py   # payloads contra la librería de referencia

npm install && npx playwright install chromium
npm run check:ui              # 116 comprobaciones sobre el prototipo
```

## Verificación

- **Payloads**: los 6 tipos de petición (gasto, gasto no divisible, gasto con
  categoría, ingreso, reembolso, rutas/verbos) se comparan campo a campo contra
  una librería de referencia. 6/6.
- **PKCS#1**: el PEM generado se valida byte a byte contra OpenSSL.
- **Parser de notificaciones**: 38 notificaciones reales de Revolut, Trade Republic
  y BBVA (`sim/corpus_notificaciones.tsv`, con los nombres de personas
  anonimizados), comprobando tipo, importe y contraparte de cada una. 38/38.
  `sim/parser_ref.py` es la referencia del parser y `MovementParser.kt` su
  transcripción: si tocas uno, toca el otro y ejecuta `python sim/parser_check.py`.
- **Sensibilidad**: `python sim/eval_parser.py` compara el reconocimiento con
  los campos en orden y con el título y el texto intercambiados (debe ser
  idéntico), y pasa las 16 variantes hipotéticas. 0 escapes en los tres.
- **Balance y liquidación**: 12 escenarios en `sim/balance_check.py` — el signo de
  cada tipo, el céntimo suelto de un reparto no divisible, que los saldos suman
  cero, y que aplicar el plan deja el grupo en paz y sin nada que saldar.
- **Decisión de la asignación rápida**: 10 escenarios de `planFor` (Bizum
  enviado/recibido, nombre completo contra nombre de pila, contraparte ajena al
  grupo, Bizum a ti mismo, transferencias).
- **Icono**: el glifo medía 64 × 45 unidades del lienzo de 108 y se leía como una
  marca apaisada, sobre todo junto al texto. Se comprimieron las **posiciones**
  hacia el centro (barra 22..86 → 28..80, asta 38,5 → 41,5, puntos 71 → 68)
  dejando intactos los grosores y el radio de los puntos: 52 × 45, casi cuadrado,
  sin adelgazar ningún trazo. Renderizado a 160/96/64/48/36/24 px y la silueta a
  48/32/24/18 px,
  sobre claro y oscuro, revisando que siga legible; la animación se reprodujo
  fotograma a fotograma con los mismos interpoladores que usan los `animator` XML.
- **Interfaz**: 116 comprobaciones automatizadas sobre un prototipo navegable
  (`prototipo-ui.html`, lanzado por `npm run check:ui`), en claro y oscuro: navegación entre pestañas,
  alta/edición/borrado de gasto, cálculo por persona, asignación de un Bizum,
  permisos, notificación accionable (asignar, deshacer, "Elegir…"), widgets
  (saldo, "+" y "Bandeja"), duración de la pantalla de carga, coherencia de la
  regla de color (marca contra dinero), etiquetas de los tipos de movimiento,
  reglas de aviso (política por tipo, silenciar un origen y reactivarlo),
  ausencia de scroll horizontal y de errores de JS.

> **Céntimos**: repartir 39,90 € entre 4 da 9,975. Redondear cada parte a 9,98
> hace que las partes sumen 39,92 y el balance del grupo se desvíe. `splitEvenly`
> reparte el resto de uno en uno entre los primeros miembros, así la suma es
> exacta. La simulación comprueba que los saldos del grupo suman cero.

## Estructura

```
data/api/     Modelos, cliente HTTP de la API interna, credenciales cifradas
data/db/      Room: bandeja de movimientos detectados
data/repo/    Stats: balances, plan de liquidación, gasto por categoría/mes/persona
              MemberIdentity: quién eres tú en cada grupo
              SavingsGroups: qué grupos son de ahorro y de dónde vienen sus ingresos
data/cache/   Instantánea de grupos para widgets y notificaciones
notif/        NotificationListenerService, parser de movimientos, registro de bancos,
              reglas de aviso, notificación accionable y receptor de acciones
widget/       Widgets Glance: saldo del grupo y alta rápida
ui/           Compose: una pantalla por pestaña, hojas inferiores, componentes y tema
ui/theme/     Tokens de color (incluida la marca) y tipografía, claro y oscuro
update/       Canal de actualización: consulta de release, descarga e instalación
branding/     Icono en SVG (color y monocromo)
util/         Codificador PKCS#1
```

## Pendiente / ideas

- Reparto por porcentajes o partes desiguales (la API lo soporta con `type: RATIO`).
- Adjuntar la foto del ticket (endpoints de attachment ya existen en la API).
- Auto-asignación aprendida: recordar que "Bizum de Laura" suele ir al grupo "Piso"
  y proponer ese grupo primero en la notificación.
- Widget configurable para fijar un grupo distinto del activo.
