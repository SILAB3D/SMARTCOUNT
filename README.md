# SmartCount

App Android (Kotlin + Compose) que se conecta a **Tricount** para:

- ver tus grupos, sus movimientos y el balance de cada miembro,
- **crear, editar y eliminar gastos** sin abrir Tricount,
- **detectar automáticamente notificaciones de Bizum y transferencias**, dejarlas en
  una bandeja de entrada y enviarlas al grupo que elijas (siempre con confirmación).

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
- *Elegir…* abre la app en la bandeja, con la hoja de ese movimiento ya abierta.
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
- **Liquidación**: 5 escenarios, comprobando que los pagos cuadran con las deudas.
- **Decisión de la asignación rápida**: 10 escenarios de `planFor` (Bizum
  enviado/recibido, nombre completo contra nombre de pila, contraparte ajena al
  grupo, Bizum a ti mismo, transferencias).
- **Icono**: renderizado a 160/96/64/48/36/24 px y la silueta a 48/32/24/18 px,
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
data/cache/   Instantánea de grupos para widgets y notificaciones
notif/        NotificationListenerService, parser de movimientos, registro de bancos,
              reglas de aviso, notificación accionable y receptor de acciones
widget/       Widgets Glance: saldo del grupo y alta rápida
ui/           Compose: pestañas, hojas inferiores, componentes y tema
ui/theme/     Tokens de color (incluida la marca) y tipografía, claro y oscuro
branding/     Icono en SVG (color y monocromo)
util/         Codificador PKCS#1
```

## Pendiente / ideas

- Reparto por porcentajes o partes desiguales (la API lo soporta con `type: RATIO`).
- Adjuntar la foto del ticket (endpoints de attachment ya existen en la API).
- Auto-asignación aprendida: recordar que "Bizum de Laura" suele ir al grupo "Piso"
  y proponer ese grupo primero en la notificación.
- Widget configurable para fijar un grupo distinto del activo.
