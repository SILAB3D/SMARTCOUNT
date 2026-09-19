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
| Renombrar grupo, emoji, miembros | `PUT  /v1/user/{uid}/registry/{id}` |
| Archivar / desarchivar / quitar | `POST /v1/user/{uid}/registry-synchronization` |

**Convenios de importe** (fáciles de equivocar):

- los importes van como **cadena, en unidades completas** (`"-12.50"`, no céntimos),
- los **gastos son negativos**, los ingresos positivos,
- `type_transaction`: `NORMAL` (gasto), `INCOME` (ingreso), `BALANCE` (reembolso
  entre miembros — el tipo natural para un Bizum entre gente del grupo),
- **los gastos y las transferencias van en negativo; los ingresos, en positivo**,
- fecha: `yyyy-MM-dd HH:mm:ss.SSSSSS`,
- cada `allocation` lleva su `membership_uuid` y, o bien `type: AMOUNT` con el
  importe (una parte fijada a mano), o bien `type: RATIO` con `share_ratio: 1`
  y **sin importe**, y entonces el servidor reparte lo que quede,
- las asignaciones **tienen que sumar el total** o la API responde 400.

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
| **Gasto** | `NORMAL` | pagado por, fecha, repartido entre, categoría | sí, en negativo |
| **Ingreso** | `INCOME` | recibido por, fecha, repartido entre, categoría | sí, en positivo |
| **Transferencia** | `BALANCE` | de, a, fecha | no: una sola asignación |

Una transferencia no lleva categoría porque no es un gasto de nada: es dinero
que cambia de manos dentro del grupo. Y exige dos personas distintas — la hoja
no deja guardar si coinciden.

**Al editar, el tipo queda fijo.** La API edita cada tipo por su propio camino
(el signo del importe y la forma de las asignaciones dependen de él), así que
cambiar de tipo es borrar y volver a crear, no editar.

### El reparto: a partes iguales o por cantidades

El reparto por cantidades no se inventó aquí: se copió. Creando un movimiento
de cada tipo **desde la app oficial** y leyéndolos después por la API se ve
exactamente cómo los guarda, y es esto:

| Movimiento creado en Tricount | total | propietario | asignaciones |
|---|---|---|---|
| Gasto igualitario entre tres | −10,00 | A | A −3,33 · B −3,33 · C −3,34, las tres `RATIO 1` |
| Gasto desigual | −10,00 | A | A −7,50 (**`AMOUNT`**) · C −2,50 (`RATIO 1`) |
| Ingreso igualitario | +10,00 | A | las tres `RATIO 1` |
| Ingreso desigual | +10,00 | A | B 8,00 (**`AMOUNT`**) · A 2,00 (`RATIO 1`) |
| Transferencia | **−10,00** | A | B −10,00 (`RATIO 1`) · A 0,00 (`AMOUNT`) |

De ahí salen las tres reglas que sigue el cliente:

1. **Solo las partes que fijas a mano van como `AMOUNT`.** Las demás viajan
   como `RATIO 1` **sin importe** y es el servidor quien reparte lo que queda.
   Antes esta app mandaba todo en `AMOUNT` con el reparto calculado en el
   móvil: funcionaba, pero un gasto repartido a partes iguales quedaba
   guardado igual que uno donde alguien hubiera escrito las cantidades a mano,
   y el céntimo suelto lo colocaba el cliente en vez de quien lleva la cuenta.
2. **La transferencia va en negativo.** Iba en positivo. El balance salía
   igual —`Stats` trabaja con valores absolutos y su propia tabla de signos—
   pero el apunte no era el mismo que ve el resto del grupo desde Tricount.
3. **Las asignaciones tienen que sumar el total.** La API responde 400 («the
   amounts of the allocations that you provided do not sum up to the amount of
   the entry») y por eso la hoja no deja guardar un reparto descuadrado: dice
   cuánto falta. Mientras quede alguien sin cantidad fijada no hace falta
   cuadrar nada — ese se lleva el resto, que es justo lo que hace Tricount.

La hoja enseña las dos formas: **a partes iguales**, con lo que le toca a cada
uno al lado de su nombre, y **por cantidades**, con un campo por persona donde
dejar en blanco significa «repártete lo que sobre».

**La fecha se elige.** Antes no: todo movimiento se guardaba con la de hoy, así
que apuntar el sábado la cena del viernes la colocaba en el día equivocado. Y
al editar se conserva la que tenía, en vez de moverla al día de la corrección.

## Balance y liquidación

La pestaña **Balance** de un grupo tiene dos mitades: el saldo de cada persona y
el plan para dejarlo a cero.

**El saldo** se calcula en el móvil a partir de los movimientos descargados.
Cada uno suma a favor de quien pone el dinero y en contra de quien se lo lleva,
pero con qué signo depende del tipo, y el signo con el que la API los guarda no
sirve para deducirlo: el gasto y la transferencia van en negativo y el ingreso
en positivo, pero el ingreso es el único de los tres que cuenta al revés. Por
eso `Stats` trabaja con **valores absolutos** y repone el signo él mismo:

| Tipo | Quién es el propietario | Efecto en el balance |
|---|---|---|
| **Gasto** | quien paga | queda a favor; los del reparto, a deber |
| **Ingreso** | quien cobra | **pasa a deber** ese dinero; los del reparto, a favor |
| **Transferencia** | quien envía | queda a favor; quien recibe, a deber |

El ingreso va al revés que el gasto a propósito: si el casero devuelve la fianza
a una sola persona, ese dinero es del grupo y quien lo tiene en el bolsillo se
lo debe al resto.

Que sea insensible al signo guardado no es casualidad ni suerte: es lo que ha
permitido igualar la transferencia a la forma de la app oficial —que la escribe
en negativo— sin tocar una línea del balance. Hay una comprobación que lo fija:
las dos representaciones dan exactamente los mismos saldos.

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

## Cómo se navega

Cinco pestañas, y cada una recuerda dónde la dejaste. Eso plantea dos
preguntas que antes no tenían respuesta: cómo se vuelve al principio de una
pestaña, y qué hace el botón atrás del móvil.

- **Un toque** en una pestaña va a ella y la deja como estaba: si habías
  dejado un grupo abierto, sigue abierto.
- **Dos toques seguidos** en la misma pestaña vuelven a su ventana principal.
  El primer toque no puede esperar a ver si llega el segundo — sería un
  retardo en el gesto más frecuente de la app —, así que cambia de pestaña ya
  y es el segundo el que cierra lo que hubiera abierto.
- **El botón atrás** deshace un paso de la pestaña actual: cierra la hoja si
  hay una, y si no, el grupo abierto. En la ventana principal cierra la app.

Las hojas inferiores no necesitan nada especial: `ModalBottomSheet` se queda
con el gesto mientras está abierta, así que cerrarla es siempre el primer paso
atrás. Las sub-pestañas de dentro de una pantalla (Movimientos/Balance,
Categoría/Persona/Mes) **no** cuentan como ventanas: son dos vistas de lo
mismo, no dos sitios.


## Las pestañas

| Pestaña | Qué es |
|---|---|
| **Grupos** | Rejilla de dos columnas con buscador. Al tocar un grupo se abre |
| **Ahorro** | Solo los grupos de ahorro, con ingresos, gastos y balance de cada uno y del conjunto |
| **Estadísticas** | En qué se va el dinero, del grupo entero o solo tu parte |
| **Bandeja** | Lo detectado, en tres cajones |
| **Ajustes** | Apartados plegables; arriba, la actualización cuando la hay |

La rejilla sustituye a la tira horizontal de chips que había antes: con más de
tres o cuatro grupos había que desplazarla a ciegas para encontrar el que se
busca, y no decía nada de cada uno. Cada ficha lleva ya la cifra que define al
grupo — lo que te deben, o el balance si es de ahorro — que es a lo que se
entraba. Los grupos de ahorro van sobre un **fondo teñido de azul**: se
distinguen de un vistazo sin leer la etiqueta, que es lo que se le pide a una
rejilla. Y en cuanto hay más de tres grupos aparece un **buscador** por título,
que ignora mayúsculas y tildes.

Dentro de un grupo normal, bajo la cifra grande van **mis gastos y los gastos
del grupo**, en el mismo formato que las tres cifras de un grupo de ahorro.
«Mis gastos» es tu parte del reparto, no lo que has adelantado: es la cifra que
contesta a «¿cuánto me está costando a mí esto?». Antes ahí había una línea de
texto que solo daba el total.

Cada movimiento de la lista dice **quién lo hizo y a quién afecta**: «Ana pagó
· entre Ana y Beto», «Ana → Beto» en una transferencia, «Ana pagó · entre
todos» cuando entran todos y «entre 5 personas» cuando son muchos para
nombrarlos. Era la mitad de la información de un gasto compartido y no estaba:
la fila decía «Ana · 3 sep» y había que abrir el movimiento para saber si esos
40 € eran de los cinco o solo de dos.

La pestaña de **Estadísticas** pregunta dos cosas distintas y ahora deja elegir
cuál: el gasto **de todo el grupo** o **solo tu parte**, acotado a un mes, a un
año o a todo. La distribución por categoría se dibuja como un anillo con su
leyenda —nombre, porcentaje e importe de cada porción—, y a partir de la sexta
categoría las demás se juntan en «Otros», porque un anillo de doce porciones no
se lee. Los colores del anillo son una paleta propia, aparte del verde y el
rojo del dinero: aquí el color identifica una categoría, no dice si algo va
bien o mal. Está comprobada para daltonismo sobre los dos fondos, y aun así el
color nunca va solo — cada porción tiene su nombre y su cifra en la leyenda.

## Grupos de ahorro

Un grupo de ahorro **no es un tipo de grupo de Tricount**: es un grupo normal
leído de otra manera, y la marca vive solo en este móvil. Para Tricount sigue
siendo un grupo con sus movimientos, así que la app oficial lo abre sin
enterarse de nada. Se convierte, y se revierte, desde *Gestionar* en el propio
grupo.

Dos papeles hacen todo el trabajo:

- una **fuente de ingresos**: un miembro que suele llamarse *Ingresos*,
- una **fuente de gastos**: quien saca el dinero, que normalmente eres tú,
- lo que sale de la fuente de ingresos son los ingresos,
- lo demás son los gastos, y el **balance** es la resta.

Los dos se eligen a mano desde el grupo. El de gastos antes no: se daba por
hecho que eras tú, y en un grupo donde la API no dice cuál de los miembros
eres — que son casi todos, los que se unieron por enlace — no había forma de
decírselo.

Con los papeles puestos, **todo lo que crea la app en un grupo de ahorro los
respeta**: un gasto va de quien gasta hacia quien gasta, y un ingreso de la
fuente de ingresos hacia quien gasta. Se impone en el único sitio por el que
pasan todas las altas, y no en cada pantalla, porque antes la hoja del grupo sí
lo hacía pero la bandeja y la notificación no: asignar un recibo a un grupo de
ahorro creaba un gasto repartido como en cualquier otro grupo, y el balance del
grupo salía mal.

Al convertir un grupo se añade el miembro *Ingresos* **solo si no hay ya una
fuente de ingresos**. Se aceptan varias formas del nombre — *Ingresos*,
*Ingreso*, *Income*, *Nómina* — porque los grupos reales no respetan la
convención al pie de la letra.

> **El alta de miembros estaba rota.** Se mandaba `alias.display_name`, y la
> API lo rechaza con «Superfluous field "display_name"»: `alias` no es un
> nombre suelto sino un *pointer*, con su `type`, su `value` y su `name`. Es
> decir, convertir un grupo que no tuviera ya un miembro llamado *Ingresos*
> fallaba. No se notó porque el grupo con el que se probó lo tenía.

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
ingresos y gastos, y debajo cada grupo con su ficha. El total solo se enseña si
todos los grupos comparten moneda; sumar euros y libras daría una cifra falsa,
así que en ese caso se dice y se remite a cada grupo.

## Gestionar un grupo

Desde *Gestionar*, dentro del grupo: renombrarlo, cambiarle el emoji, añadir y
renombrar miembros, convertirlo en grupo de ahorro, archivarlo o quitarlo de la
app. Y desde la rejilla, **crear un grupo nuevo** con sus miembros, que antes
solo se podía pegando el enlace de uno que ya existiera.

Tres cosas que la API impone y conviene saber:

- **Quitar un miembro no se puede.** Ni omitiéndolo de la lista (lo conserva),
  ni mandándolo con `status: INACTIVE` (lo devuelve ACTIVE), ni borrando la
  pertenencia (`Route not found`). Comprobado contra la API con miembros
  recién creados y sin ningún movimiento a su nombre. Así que no hay botón: uno
  que no hace nada y no lo dice es peor que no tenerlo. Se renombra —eso sí
  va— y quien necesite quitar a alguien lo hace desde la app oficial.
- **Un grupo archivado desaparece de la API.** No lo devuelve `/registry` ni
  pidiéndolo por estado, pero sí se lee por su enlace público. Por eso, antes
  de archivar, SmartCount anota el enlace y el nombre en el móvil: sin eso,
  archivar sería perderlo. Los archivados salen al final de la rejilla y se
  recuperan de un toque.
- **Quitar un grupo lo quita de esta app, no del mundo.** Se deshace la
  sincronización; el grupo sigue existiendo para el resto y se vuelve a entrar
  con su enlace. Borrarlo de verdad no se ofrece: sería un botón que destruye
  los datos de más gente.


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

La bandeja enseña **tres cajones**, y cualquier notificación se mueve de uno a
otro con un toque, con esa decisión mandando sobre la del parser a partir de
ahí:

| Cajón | Qué hay dentro | Qué se puede hacer |
|---|---|---|
| **Movimientos bancarios** | Lo que el parser reconoció como cargo o abono | Asignarlo a uno o varios grupos; moverlo a otro cajón |
| **Otros eventos** | Lo que llegó de una app que miramos y no parece un movimiento | Marcarlo como movimiento (y su app pasa a vigilada), o como no bancario |
| **No bancarios** | Lo que no tiene nada que ver con el banco | Su app deja de seguirse; se reactiva en Ajustes |

Eran dos cajones y no daban para lo que hay que decidir. Un aviso de tu banco
que no es un cargo y la notificación de un juego no son la misma cosa aunque
las dos «no sean movimientos»: la primera viene de una app que quieres seguir
mirando y la segunda de una que no. Separarlas permite que cada una tenga la
acción que le corresponde.

Enseñar también lo descartado es lo que convierte la bandeja en el sitio donde
se afina el sistema y no solo donde se recogen resultados. El parser se equivoca
en las dos direcciones y las dos equivocaciones no cuestan igual: un aviso
comercial colado entre los movimientos se aparta de un toque, pero **un
movimiento descartado por error se perdía sin dejar rastro** — los avisos de
nómina de BBVA, que llegan sin importe, son el caso de libro. Ahora se rescatan.

Lo ya enviado a Tricount no desaparece: queda en **Enviados**, dentro de la
propia bandeja, para poder mirar atrás.

- El permiso **Acceso a notificaciones** se concede a mano en los ajustes del sistema.
- La lista de bancos es una semilla (Revolut, Trade Republic, BBVA, CaixaBank,
  Santander y otros). Las apps no se quitan, se **desactivan**, y las que
  notifiquen sin estar vigiladas se anotan aparte para poder activarlas de un
  toque: así se encuentra el paquete de tu banco sin saberlo de memoria.
- Deduplicación por hash del contenido en una ventana de 5 minutos: los bancos
  republican la misma notificación al actualizarla.

### Hasta dónde llega la vigilancia

El interruptor de «modo aprendizaje» mezclaba dos preguntas —qué apps mirar y
si descubrir apps nuevas— en un solo sí o no. Ahora son tres escalones:

| Modo | Qué se mira |
|---|---|
| **Apagado** | Solo los bancos que trae la app de fábrica |
| **Selectivo** (por defecto) | Las apps vigiladas que tengas activadas |
| **Completo** | Todas las apps del dispositivo, para descubrir la tuya |

En los dos primeros se respeta siempre lo que hayas apagado a mano: desactivar
un banco significa desactivarlo, no «desactivarlo salvo en modo apagado».

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
dejan de avisar y de llegar a la bandeja. Se callan desde la hoja del
movimiento — *No volver a avisar de «X»* — y se reactivan en Ajustes. La
comparación de nombres ignora tildes, mayúsculas y puntuación, para que
«Filmin » y «filmin» sean el mismo comercio.

Estuvo en la propia notificación, para poder callar una suscripción desde la
pantalla de bloqueo. Ya no: Android enseña tres acciones y las tres se las
llevan los grupos, así que ese botón se añadía y no se veía. Dentro de la app,
además, se lee el nombre entero del comercio antes de silenciarlo.

## Notificación al detectar un movimiento

Cuando el `NotificationListenerService` reconoce un movimiento, SmartCount
lanza su propia notificación accionable:

```
SMARTCOUNT
Bizum recibido 18,00 € · Ben Torres
«entradas» · ¿a qué grupo lo llevas?
[ Piso Salamanca ]  [ Ahorro: Casa ]  [ Elegir… ]
```

Tres botones, que son las tres cosas que se hacen con un cargo recién
detectado: llevarlo al **grupo normal más reciente**, al **grupo de ahorro más
reciente**, o decidirlo tú.

**Los tres abren la app con el movimiento ya preparado; ninguno lo envía a
Tricount por su cuenta.** Antes el botón de un grupo lo creaba desde la
pantalla de bloqueo y ofrecía deshacerlo treinta segundos. Era más rápido, pero
un cargo casi nunca llega listo para guardar —hay que mirar entre quién se
reparte, si la descripción del banco vale, si el importe es el bueno— y lo que
se ganaba en un toque se perdía luego corrigiendo desde dentro. Ahora el botón
elige el grupo y la app abre la hoja con esa elección puesta.

**Reciente** es el último grupo en el que pasó algo, contando tanto su último
movimiento como el día en que entró en la app: un grupo recién añadido no tiene
movimientos todavía y es justo donde vas a querer llevar lo siguiente. Se mira
`created` y no `date`, porque la fecha la pone quien crea el movimiento y puede
ser de hace meses. Si no hay grupos de ahorro, ese botón no aparece.

Son tres botones y no cuatro porque **Android enseña como mucho tres
acciones**: la cuarta se añade y no se ve — que es lo que le pasaba a «No
avisar de X», que llevaba tiempo sin aparecer sin que nadie se diera cuenta.
Silenciar un comercio se hace desde la hoja del movimiento, que es además donde
se lee su nombre entero.

La hoja llega con una propuesta hecha (`AssignPlan`): un pago entre personas
—Bizum o transferencia— con alguien que está en el grupo se propone como
reembolso de uno a otro, porque eso es literalmente lo que pasó; lo demás, como
gasto repartido. El emparejamiento de nombres tolera que el banco diga «BEN
TORRES» y el grupo solo «Ben». Desde ahí el movimiento puede ir a **varios
grupos a la vez** y con **la persona que elijas** en cada uno: el recibo de la
luz va al piso y al grupo de ahorro, y hacerlo dos veces obligaba a repetir
importe y descripción a mano.

## Actualización automática

Un `git push` a `main` acaba convertido en una actualización instalada en el
móvil, sin descargar ningún APK a mano.

```
push a main → GitHub Actions compila y FIRMA la APK → Release v<name>-b<code>
                                                          │
        la app: al arrancar y una vez al día, consulta, avisa, descarga e instala
```

**Android no deja instalar en silencio** a una app normal: eso exige ser *device
owner* o app de sistema. Comprobar, descargar y preparar sí es automático; el
último paso es siempre un diálogo del sistema que confirma la persona.

**Se comprueba también en segundo plano, una vez al día** (`WorkManager`, con
red). Hasta ahora solo se miraba al abrir la app, así que una versión publicada
el lunes podía descubrirse el viernes: justo al revés de lo que se le pide a un
canal cuyo objetivo es que un push acabe instalado. Cuando hay algo, lo dice con
una notificación en su propio canal, con un botón que abre la app y **empieza la
descarga sin pedir otro toque**. Avisa **una sola vez por versión**: un aviso
diario de la misma actualización enseñaría a ignorarlo, que es la única forma
segura de que el aviso importante pase desapercibido.

Dentro de la app, la actualización aparece **arriba del todo en Ajustes**, y
solo cuando la hay.

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

Requisitos: **JDK 17 o superior**, **Android SDK** con la plataforma **API 35**
y **Node 18+**. Gradle lo descarga el wrapper.

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
npm install
npm run check:payloads   # las peticiones que se mandan a la API
npm run check:balance    # balance neto y plan de liquidación
npm run check:plan       # la propuesta de la hoja de asignación
npx playwright install chromium
npm run check:ui         # 166 comprobaciones sobre el prototipo, en claro y oscuro
npm run check            # todas las anteriores de una vez

# Contra la API de verdad, en un grupo de usar y tirar: crea un movimiento de
# cada tipo, lo relee, comprueba la forma y lo borra.
npm run check:api -- https://tricount.com/<token-de-tu-grupo-de-pruebas>

# El parser sigue comprobándose en Python, que es donde vive su referencia
python sim/parser_check.py    # 38 notificaciones reales
python sim/eval_parser.py     # sensibilidad y variantes hipotéticas
```

> Las comprobaciones de balance, liquidación, payloads y asignación estaban en
> Python y se han pasado a Node, que es lo que ya hacía falta para la interfaz:
> una dependencia menos que instalar para poder ejecutarlas. Las del parser se
> quedan en Python porque su referencia (`sim/parser_ref.py`) **es** el
> contraste contra el que se valida `MovementParser.kt`, y traducirla crearía
> una tercera copia de las mismas reglas.

## Verificación

- **Payloads**: las peticiones de los cinco tipos de movimiento se comparan con
  la forma exacta que produce la app oficial —leída de sus propios movimientos—
  y se comprueba que lo que la API rechaza no llega a salir. 14/14
  (`sim/payload_check.js`).
- **Contra la API de verdad**: `sim/roundtrip.js` crea un movimiento de cada
  tipo en un grupo de pruebas con las mismas peticiones que manda la app, lo
  relee tal y como lo ha guardado el servidor, comprueba tipo, signo, reparto y
  que las asignaciones suman el total, y lo borra. 28/28, y el grupo queda como
  estaba. Es la comprobación que no puede hacer la anterior: aquella compara
  contra una forma conocida, y esta pregunta al servidor si esa forma es la que
  él entiende.
- **PKCS#1**: el PEM generado se valida byte a byte contra OpenSSL.
- **Parser de notificaciones**: 38 notificaciones reales de Revolut, Trade Republic
  y BBVA (`sim/corpus_notificaciones.tsv`, con los nombres de personas
  anonimizados), comprobando tipo, importe y contraparte de cada una. 38/38.
  `sim/parser_ref.py` es la referencia del parser y `MovementParser.kt` su
  transcripción: si tocas uno, toca el otro y ejecuta `python sim/parser_check.py`.
- **Sensibilidad**: `python sim/eval_parser.py` compara el reconocimiento con
  los campos en orden y con el título y el texto intercambiados (debe ser
  idéntico), y pasa las 16 variantes hipotéticas. 0 escapes en los tres.
- **Balance y liquidación**: 13 escenarios en `sim/balance_check.js` — el signo de
  cada tipo, el céntimo suelto de un reparto no divisible, que los saldos suman
  cero, que aplicar el plan deja el grupo en paz, y que cambiar el signo con el
  que se guarda una transferencia no altera ningún balance.
- **Propuesta de asignación**: 12 escenarios de `sim/plan_check.js` (Bizum
  enviado/recibido, nombre completo contra nombre de pila, contraparte ajena al
  grupo, Bizum a ti mismo, transferencias dentro y fuera del grupo).
- **Icono**: el glifo medía 64 × 45 unidades del lienzo de 108 y se leía como una
  marca apaisada, sobre todo junto al texto. Se comprimieron las **posiciones**
  hacia el centro (barra 22..86 → 28..80, asta 38,5 → 41,5, puntos 71 → 68)
  dejando intactos los grosores y el radio de los puntos: 52 × 45, casi cuadrado,
  sin adelgazar ningún trazo. Renderizado a 160/96/64/48/36/24 px y la silueta a
  48/32/24/18 px, sobre claro y oscuro, revisando que siga legible; la animación
  se reprodujo fotograma a fotograma con los mismos interpoladores que usan los
  `animator` XML.
- **Paleta del anillo de categorías**: validada con el comprobador de la guía de
  visualización sobre los dos fondos —banda de luminosidad, croma, separación
  para daltonismo (protan/deuteran/tritan) y contraste—. Pasa en claro y en
  oscuro; el aviso de contraste del modo claro queda cubierto por la leyenda,
  que lleva siempre nombre e importe visibles.
- **Interfaz**: 166 comprobaciones automatizadas sobre un prototipo navegable
  (`prototipo-ui.html`, lanzado por `npm run check:ui`), en claro y oscuro:
  rejilla y buscador, tonalidad de los grupos de ahorro, las dos cifras de un
  grupo normal, quién pagó y a quién afecta, alta con fecha y con reparto por
  cantidades (incluido que no deje guardar un reparto que no suma), navegación
  con doble toque y botón atrás, los papeles de un grupo de ahorro, el anillo de
  categorías con su leyenda y los filtros de ámbito y periodo, los tres cajones
  de la bandeja y lo que cada salto le hace a la app de origen, los ajustes
  plegables con sus tres modos de aprendizaje y el encendido/apagado de apps, la
  notificación de tres botones que **no** crea nada hasta confirmar, los
  widgets, la coherencia de la regla de color, ausencia de scroll horizontal y
  de errores de JS.

> **Céntimos**: repartir 39,90 € entre 4 da 9,975. Redondear cada parte a 9,98
> hace que las partes sumen 39,92 y el balance del grupo se desvíe. Ahora el
> reparto lo hace el servidor (asignaciones en `RATIO`), y el que se enseña
> antes de guardar reparte el resto de uno en uno entre los primeros miembros,
> así la suma es exacta. La simulación comprueba que los saldos del grupo suman
> cero.

## Estructura

```
data/api/     Modelos, cliente HTTP de la API interna, credenciales cifradas
              Split: cómo se reparte un movimiento entre los miembros
data/db/      Room: bandeja de movimientos detectados, en tres cajones
data/repo/    Stats: balances, plan de liquidación, gasto por categoría/mes/persona
              MemberIdentity: quién eres tú en cada grupo
              SavingsGroups: los dos papeles de un grupo de ahorro
              ArchivedGroups: los archivados, que la API ya no devuelve
data/cache/   Instantánea de grupos para widgets y notificaciones
notif/        NotificationListenerService, parser de movimientos, registro de apps
              vigiladas, reglas de aviso, notificación accionable
              AssignPlan: con qué papeles llega un movimiento a la hoja
widget/       Widgets Glance: saldo del grupo y alta rápida
ui/           Compose: una pantalla por pestaña, hojas inferiores, componentes y tema
ui/theme/     Tokens de color (marca, tinte de ahorro y paleta del anillo) y tipografía
update/       Canal de actualización: consulta, descarga, instalación,
              comprobación diaria en segundo plano y aviso
branding/     Icono en SVG (color y monocromo)
util/         Codificador PKCS#1
sim/          Comprobaciones: payloads, balance, asignación, interfaz y API real
```

## Pendiente / ideas

- **Adjuntar la foto del ticket.** La app oficial lo ofrece junto al título, y
  la API tiene endpoints de attachment, pero su forma no está descifrada: hay
  que sacarla a base de prueba y error contra el servidor.
- **Reparto por partes o porcentajes.** La API lo soporta — `RATIO` con
  `share_ratio` mayor que 1 —, y de hecho ya se usa con `share_ratio: 1` para
  las partes iguales. Falta la interfaz.
- **Moneda distinta a la del grupo.** La app oficial deja elegirla en el propio
  movimiento y guarda el cambio (`exchange_rate`).
- Auto-asignación aprendida: recordar que "Bizum de Laura" suele ir al grupo "Piso"
  y proponer ese grupo primero en la notificación.
- Widget configurable para fijar un grupo distinto del activo.
