# Funciones de SmartCount y cómo se consiguen en código

Mapa de cada cosa que hace la app hacia el código que la hace. El README cuenta
**qué** hace la app y por qué; este documento cuenta **dónde** está y **cómo**
está resuelto, para poder tocar algo sin tener que leerlo todo antes.

Rutas relativas a `app/src/main/java/com/silab/smartcount/`.

---

## Índice

1. [Arquitectura en una página](#1-arquitectura-en-una-página)
2. [Hablar con Tricount](#2-hablar-con-tricount)
3. [Grupos](#3-grupos)
4. [Movimientos: gasto, ingreso, reembolso](#4-movimientos-gasto-ingreso-reembolso)
5. [Balance y liquidación](#5-balance-y-liquidación)
6. [Quién eres tú en cada grupo](#6-quién-eres-tú-en-cada-grupo)
7. [Grupos de ahorro](#7-grupos-de-ahorro)
8. [Estadísticas](#8-estadísticas)
9. [Detección de movimientos por notificación](#9-detección-de-movimientos-por-notificación)
10. [La bandeja](#10-la-bandeja)
11. [Notificación accionable](#11-notificación-accionable)
12. [Ajustes](#12-ajustes)
13. [Widgets](#13-widgets)
14. [Navegación y entradas a la app](#14-navegación-y-entradas-a-la-app)
15. [Tema y componentes](#15-tema-y-componentes)
16. [Actualización automática](#16-actualización-automática)
17. [Build, firma y versiones](#17-build-firma-y-versiones)

---

## 1. Arquitectura en una página

Una sola actividad, Compose, un `ViewModel` con un `StateFlow`, y tres sitios
donde vive el estado: la API de Tricount (la verdad), Room (la bandeja) y
`SharedPreferences` (las decisiones locales).

| Capa | Archivo | Qué guarda |
|---|---|---|
| Red | [`data/api/TricountClient.kt`](app/src/main/java/com/silab/smartcount/data/api/TricountClient.kt) | Todo el protocolo: sesión, grupos, movimientos |
| Modelo | [`data/api/Models.kt`](app/src/main/java/com/silab/smartcount/data/api/Models.kt) | `Tricount`, `Member`, `Transaction`, `Allocation`, `Split` |
| Bandeja | [`data/db/Inbox.kt`](app/src/main/java/com/silab/smartcount/data/db/Inbox.kt) | Room: `InboxEntry`, `InboxDao`, `AppDatabase` |
| Preferencias | `data/repo/*.kt`, `notif/BankRegistry.kt`, `notif/NotificationRules.kt` | Ahorro, identidad, archivados, apps vigiladas, políticas |
| Caché widget | [`data/cache/GroupCache.kt`](app/src/main/java/com/silab/smartcount/data/cache/GroupCache.kt) | JSON plano para pintar el widget sin red |
| Estado UI | [`ui/MainViewModel.kt`](app/src/main/java/com/silab/smartcount/ui/MainViewModel.kt) | `UiState` único + flujos derivados de Room |
| Pantallas | `ui/*Screen.kt` | Grupos, Ahorro, Estadísticas, Bandeja, Ajustes |

El estado de la interfaz es un único `data class UiState`
([`MainViewModel.kt:31`](app/src/main/java/com/silab/smartcount/ui/MainViewModel.kt#L31)),
expuesto como `StateFlow`. Las pantallas lo consumen con
`collectAsStateWithLifecycle()` y no guardan nada propio salvo lo efímero (qué
hoja está abierta, qué texto lleva un campo).

Todas las acciones del ViewModel pasan por `launchGuarded`, que centraliza el
`loading`, el `try/catch` y el mensaje de error. Por eso ninguna función del
ViewModel tiene `try` propio.

`SmartCountApp` ([`SmartCountApp.kt`](app/src/main/java/com/silab/smartcount/SmartCountApp.kt))
es el `Application`: construye la base de datos y los canales de notificación
una sola vez, y es a quien preguntan el listener y los widgets, que no tienen
ViewModel.

---

## 2. Hablar con Tricount

No hay API pública: el cliente imita a la app oficial. Todo el protocolo está en
un único archivo, `TricountClient.kt`.

**Identidad de instalación.** `CredentialStore`
([`data/api/CredentialStore.kt`](app/src/main/java/com/silab/smartcount/data/api/CredentialStore.kt))
genera una vez un UUID de instalación y un par RSA de 2048 bits, y los guarda en
`SharedPreferences`. La API quiere la clave pública en **PKCS#1**, no en el
X.509 que devuelve Java, así que `util/Pkcs1.kt` escribe el DER a mano
(`SEQUENCE { modulus, publicExponent }`) en vez de arrastrar BouncyCastle.

**Sesión.** `authenticate()`
([`TricountClient.kt:145`](app/src/main/java/com/silab/smartcount/data/api/TricountClient.kt#L145))
hace `POST /v1/session-registry-installation` con el UUID y la clave, y saca de
la respuesta el `Token` y el `UserPerson.id`. Cada petición lleva los cabeceras
`app-id`, `X-Bunq-Client-Request-Id` y `X-Bunq-Client-Authentication`
(`newRequest`). El `User-Agent` finge ser la app oficial (`USER_AGENT`).

**Caducidad.** `withSession { }` envuelve cada llamada: si sale un 401 o 403,
borra el token, reautentica y reintenta **una** vez. Es la razón de que ninguna
pantalla tenga que pensar en sesiones.

**Parseo.** Las respuestas vienen envueltas en `Response: [...]` con cada objeto
bajo una clave distinta. Los ayudantes de `Models.kt` (`str`, `int`, `obj`,
`arr`, `unwrapFirst`) y `responseArray()` / `extractId()` absorben esa forma
para que los modelos se construyan con `Tricount.parse(json)`.

---

## 3. Grupos

Pantalla: [`ui/GroupsScreen.kt`](app/src/main/java/com/silab/smartcount/ui/GroupsScreen.kt).

| Función | Código |
|---|---|
| Listar | `TricountClient.listTricounts()` → `GET /v1/user/{id}/registry`; `MainViewModel.refresh()` |
| Abrir uno | `getTricount(id)` → `GET /v1/registry/{id}`; `vm.openGroup(id)` pone `openGroupId` en `UiState` |
| Crear | `createTricount(...)` (`TricountClient.kt:242`), hoja `CreateGroupSheet` |
| Unirse por enlace | `extractPublicToken()` saca el token de `tricount.com/es/tXXXX`; `peekTricount()` lo mira antes de entrar y `joinTricount()` entra. Entrada: `vm.addByLink()` |
| Buscar | `foldForSearch()` (`GroupsScreen.kt:64`) normaliza acentos y mayúsculas |
| Renombrar / emoji | `updateTricount()`; `vm.renameGroup()`, `vm.setGroupEmoji()` |
| Miembros | `addMembers()`, `renameMember()` |
| Archivar | `setArchived()` + `ArchivedGroups.remember()`, que guarda el token público para poder volver |
| Restaurar | `restoreArchived(token)` desde lo que guardó `ArchivedGroups` |
| Quitar de la lista | `unsyncTricount()` |

El grupo archivado desaparece del listado de la API, así que sin guardar su
token localmente no habría forma de volver a él: eso es todo lo que hace
[`data/repo/ArchivedGroups.kt`](app/src/main/java/com/silab/smartcount/data/repo/ArchivedGroups.kt).

---

## 4. Movimientos: gasto, ingreso, reembolso

Tres formas distintas en la API, una sola hoja en la interfaz: `ExpenseSheet`
([`ui/Screens.kt:209`](app/src/main/java/com/silab/smartcount/ui/Screens.kt#L209)),
que produce un `MovementDraft` (`Screens.kt:160`).

- **Gasto** → `createExpense()` (`TxType.NORMAL`): un pagador, N participantes.
- **Ingreso** → `createIncome()` (`TxType.INCOME`).
- **Reembolso** → `createReimbursement()` (`TxType.BALANCE`): de uno a uno.

El reparto se manda en **ratios**, no en importes: el servidor reparte los
céntimos. Para poder enseñar el reparto *mientras* se escribe, sin ir al
servidor, `previewSplit(total, n)` (`TricountClient.kt`, companion) hace la misma
cuenta en céntimos y da el sobrante a los primeros.

El reparto desigual es `Split` (`Models.kt:225`): miembros con importe fijo y
miembros libres; `remainder(total)` es lo que queda por repartir entre los
libres. La hoja lo pinta con `MemberSplitRow` (`Screens.kt:601`) y valida antes
de dejar enviar.

Editar y borrar: `editTransaction()` y `deleteTransaction()`, desde
`vm.editMovement()` y `vm.deleteExpense()`.

---

## 5. Balance y liquidación

Todo en [`data/repo/Stats.kt`](app/src/main/java/com/silab/smartcount/data/repo/Stats.kt),
sin red: se calcula sobre las transacciones ya descargadas.

- `balancesByUuid(t)`: por cada movimiento, suma lo pagado y resta lo asignado.
- `settlementPlan(t)` (`Stats.kt:82`): algoritmo voraz. Separa acreedores y
  deudores, los ordena de mayor a menor y va casando el mayor con el mayor
  hasta agotarlos. Da el **mínimo número de pagos** en la práctica y siempre
  como mucho *n−1*. El umbral de 0.005 € evita arrastrar céntimos de redondeo.
- `isSettled(t)`: el plan está vacío.

Liquidar desde la app crea un reembolso por cada tramo:
`vm.settle(tricount, legs)` (`MainViewModel.kt:352`) recorre los `SettlementLeg`
llamando a `createReimbursement()`. La interfaz es `BalanceSection`
(`GroupsScreen.kt:790`).

---

## 6. Quién eres tú en cada grupo

La API no siempre dice cuál de los miembros eres. Sin eso no hay balance
personal ni "quién pagó" por defecto.

[`data/repo/MemberIdentity.kt`](app/src/main/java/com/silab/smartcount/data/repo/MemberIdentity.kt)
lo resuelve **en el modelo, no en la pantalla**: `resolve(t)` se pasa como
`resolveIdentity` al construir el `TricountClient`, de modo que cualquier grupo
recién leído ya viene con `activeMembershipUuid` relleno — también los que lee
la caché del widget o la asignación desde la notificación, que no pasan por la
interfaz.

El orden es: lo que el usuario eligió a mano (`stored`) → `guess(t)` → nada.
`guess` solo deduce cuando no cabe duda: el miembro que se llama como tú en el
banco (comparando nombres con `fold`, que quita acentos y admite orden distinto
y nombre parcial), o, en un grupo de dos donde uno es la fuente de ingresos de
un grupo de ahorro, el otro. Con tres desconocidos no adivina: pregunta
(`IdentityRow` en `GroupsScreen.kt:619`).

---

## 7. Grupos de ahorro

Un grupo normal de Tricount usado al revés: un miembro ficticio es la *fuente de
ingresos* y otro es *quien gasta*. Lo que en un grupo normal es un balance, aquí
es un saldo ahorrado.

- Marcar el grupo y elegir los dos miembros:
  [`data/repo/SavingsGroups.kt`](app/src/main/java/com/silab/smartcount/data/repo/SavingsGroups.kt)
  (`mark`, `setIncomeMember`, `setSpenderMember`), en `SharedPreferences`.
- `Savings.summary(t, incomeUuid)` calcula ingresado, gastado y disponible.
- `isReady(t)`: no se puede operar hasta que los dos papeles están asignados.
- Pantalla: [`ui/SavingsScreen.kt`](app/src/main/java/com/silab/smartcount/ui/SavingsScreen.kt);
  el total de todos los grupos es `vm.savingsTotal(groups)`.

Un grupo de ahorro cambia el resto de la app: la hoja de asignación no ofrece
reparto entre miembros (`state.isSavings(id)` decide), las estadísticas usan
`savingsStats()` en vez de `normalStats()` (`StatsScreen.kt:161` y `:195`), y el
movimiento se registra a nombre de quien gasta o desde la fuente de ingresos
según la dirección.

---

## 8. Estadísticas

Pantalla [`ui/StatsScreen.kt`](app/src/main/java/com/silab/smartcount/ui/StatsScreen.kt),
cálculo en `Stats.kt`. Todo local.

`periodsOf(groups)` saca los meses que existen; `expenses(t, incomeUuid, period)`
filtra la base sobre la que trabajan el resto: `byCategory`, `byCategoryAcross`,
`totalSpent`, `byMonth`, `byPayer`, `myShare`, `averagePerTransaction`.

El donut de categorías se dibuja a mano con `Canvas` (`CategoryDonut`,
`StatsScreen.kt:325`) y las barras con `ProportionBar` (`Components.kt:276`): no
hay librería de gráficos.

---

## 9. Detección de movimientos por notificación

El motor está en `notif/`.

**Escuchar.** `BankNotificationListener` es un `NotificationListenerService`
declarado en el manifiesto con el permiso
`BIND_NOTIFICATION_LISTENER_SERVICE`. El usuario lo concede a mano; `hasAccess()`
lo comprueba leyendo `Settings.Secure.enabled_notification_listeners`.

**Qué apps.** `BankRegistry` guarda tres conjuntos: los paquetes por defecto, los
añadidos y los desactivados; `watchedPackages()` es la resta. Lo que notifica sin
estar vigilado se anota en `seen()` — así se encuentra el paquete de tu banco en
Ajustes sin saberlo de memoria. `LearnMode` permite ampliar temporalmente la
vigilancia a todo.

**Parsear.** `MovementParser.parse(title, text, ownName)`
([`notif/MovementParser.kt:222`](app/src/main/java/com/silab/smartcount/notif/MovementParser.kt#L222)),
en tres niveles:

1. `findAmount()` — sin importe no hay movimiento.
2. Una tabla de `RULES` (regex → `DetectedKind` + grupo de captura con el
   nombre), probada sobre el texto, el título y la unión en los dos órdenes,
   porque los bancos no ponen los campos siempre igual. Si encaja →
   `Confidence.HIGH`.
3. Si no encaja ninguna, solo pistas de dirección (`MONEY_IN_HINTS` /
   `MONEY_OUT_HINTS`) → `INCOME_OTHER` o `SPEND_OTHER`, `Confidence.LOW`.

Extras: `collapseDoubled()` arregla las notificaciones que repiten el texto,
`cleanName()` normaliza el nombre de la contraparte, e `isSelf()` marca como
`SELF_TRANSFER` lo que va de tus cuentas a tus cuentas. Los 14 tipos posibles son
el enum `DetectedKind` (`Inbox.kt:18`), con `isMoneyIn` y `label`.

**Decidir qué hacer.** `NotificationRules.decide(kind, merchant, counterparty)`
devuelve una `MovementPolicy`: `NOTIFY`, `INBOX_ONLY` o `IGNORE`. La política es
configurable por tipo (Ajustes) y hay una lista de orígenes silenciados
(`mutedSources`).

**Guardar.** `onNotificationPosted` calcula una `dedupeKey` (SHA-256 de
paquete+título+texto, 32 caracteres) y descarta lo repetido dentro de 5 minutos
(`countRecentWithKey`), porque los bancos republican la misma notificación al
actualizarla. Lo que **no** se supo parsear también se guarda: es lo que permite
rescatar lo que el parser dejó fuera por error.

---

## 10. La bandeja

Pantalla [`ui/InboxScreen.kt`](app/src/main/java/com/silab/smartcount/ui/InboxScreen.kt),
datos en Room.

`InboxEntry` guarda el crudo (`rawTitle`, `rawText`) junto a lo interpretado, de
modo que reclasificar nunca pierde información. Tres estados (`InboxStatus`:
`PENDING`, `PUSHED`, `IGNORED`) y tres cajones (`InboxClass`: `BANK`, `OTHER`,
`NON_BANK`). El DAO expone flujos (`observeByStatus`, `observeHistory`) que el
ViewModel convierte en `StateFlow` (`vm.inbox`, `vm.inboxHistory`).

**Asignar.** `AssignSheet` (`InboxScreen.kt:288`) es la hoja grande:

- La propuesta de partida sale de `AssignPlan.planFor(entry, t)`
  ([`notif/AssignPlan.kt:51`](app/src/main/java/com/silab/smartcount/notif/AssignPlan.kt#L51)),
  que decide si el movimiento parece un reembolso o un gasto repartido y a qué
  miembro corresponde el nombre detectado (`memberNamed`). Propone; no decide:
  todo se puede cambiar.
- Los grupos se eligen en una lista desplegable con selección múltiple
  (`GroupPicker`, `InboxScreen.kt:765`): el mismo movimiento puede crearse en
  varios grupos a la vez.
- Al enviar, `vm.pushInboxEntry(entry, targets, ...)` (`MainViewModel.kt:472`)
  crea un movimiento por grupo con la llamada que corresponda y marca la entrada
  como `PUSHED`.

**Calibrar.** Las acciones del pie (`FooterAction`, `InboxScreen.kt:855`) son las
salidas: mover a otro cajón (`vm.classifyInboxEntry`), que incluye "es un
movimiento bancario" para rescatar lo mal descartado y "no es bancario · dejar de
seguir", que además llama a `BankRegistry.stopWatching(pkg)`; e ignorar
(`vm.ignoreInboxEntry`).

---

## 11. Notificación accionable

`DetectionNotifier.notify()`
([`notif/DetectionNotifier.kt:85`](app/src/main/java/com/silab/smartcount/notif/DetectionNotifier.kt#L85))
construye la notificación con un botón por grupo candidato, leídos de la caché
(`candidateGroups(GroupCache)`) porque en ese momento no hay red garantizada ni
ViewModel vivo.

Cada botón es un `PendingIntent` con `ACTION_OPEN_INBOX` y los extras del
movimiento y del grupo. Detalle que importa: **cada botón lleva un
`requestCode` distinto**; con el mismo, Android reutilizaría los extras del
primer `PendingIntent` y los tres botones acabarían haciendo lo mismo.

Al abrirse la app, `MainActivity.handleShare()` traduce esos extras a
`vm.focusInboxEntry(InboxFocus(entryId, groupId))`, y un `LaunchedEffect` en
`InboxScreen` abre la hoja de ese movimiento con ese grupo ya marcado.

---

## 12. Ajustes

[`ui/SettingsScreen.kt`](app/src/main/java/com/silab/smartcount/ui/SettingsScreen.kt),
en secciones plegables (`settingsSection`, `:356`):

- Acceso a notificaciones (lleva a los ajustes del sistema).
- Apps vigiladas: activas, vistas y añadibles — todo sobre `BankRegistry`.
- Tu nombre en el banco: lo usa `MovementParser.isSelf` y `MemberIdentity.guess`.
- Sensibilidad por tipo de movimiento: `NotificationRules.setPolicy`, ciclando
  con `MovementPolicy.next()`.
- Orígenes silenciados: `mutedSources` / `unmute`.
- Actualización: `UpdateCard` (`:394`).

---

## 13. Widgets

Glance (Compose para widgets), en `widget/`.

- `BalanceWidget`: grupo activo, saldo y accesos a "+ Gasto" y a la bandeja.
- `QuickAddWidget`: un botón, `ACTION_NEW_EXPENSE`.

Un widget no puede esperar a la red, así que **no** llama a la API: lee
`GroupCache`, un JSON plano en `SharedPreferences` que la app reescribe con
`saveGroups()` en cada refresco y con `savePendingCount()` cada vez que entra
algo en la bandeja. `SmartWidgets.refresh(context)` es la única forma de
repintarlos y la llaman tanto el ViewModel como el listener.

---

## 14. Navegación y entradas a la app

Una sola actividad, `launchMode="singleTask"`, sin librería de navegación: el
estado de navegación son dos campos (`openGroupId`, `openSavingsId`) y una
variable de pestaña en `AppRoot` ([`MainActivity.kt:154`](app/src/main/java/com/silab/smartcount/MainActivity.kt#L154)).

Cinco pestañas (`enum Tab`). El botón atrás y el doble toque en la pestaña
vuelven a la raíz con `popToRoot()`; las hojas inferiores se cierran solas
porque el sistema les entrega el gesto antes.

Entradas desde fuera, todas por `handleShare(intent)`:

| Intent | Origen | Efecto |
|---|---|---|
| `ACTION_SEND` | compartir un enlace de Tricount | `vm.addByLink()` |
| `NEW_EXPENSE` | widget | pestaña Grupos + `vm.requestNewExpense()` |
| `OPEN_INBOX` | notificación de detección | pestaña Bandeja + `focusInboxEntry` |
| `UPDATE` | notificación de versión nueva | `updateVm.checkAndStart()` |

El splash dura exactamente lo que la animación del icono (1 s) mediante
`setKeepOnScreenCondition`, y se instala **antes** de `super.onCreate` o no llega
a verse.

---

## 15. Tema y componentes

[`ui/theme/Theme.kt`](app/src/main/java/com/silab/smartcount/ui/theme/Theme.kt)
define `SmartColors`, una paleta propia servida por `CompositionLocal`
(`SmartTheme.colors`) en vez del `ColorScheme` de Material: la app tiene su
propio lenguaje visual y claro/oscuro salen de la misma estructura.

Los componentes compartidos están en
[`ui/Components.kt`](app/src/main/java/com/silab/smartcount/ui/Components.kt):
`PrimaryButton` (píldora llena, acción principal), `SecondaryButton`, `PillChip`
(selección animada con `animateColorAsState`), `SmartRow`, `SectionHeader`,
`Initials`, `ProportionBar`, `BrandMark` y los formateadores `formatMoney` /
`formatTxDate`.

La jerarquía visual de la hoja de asignación es un ejemplo del criterio: el
envío es `PrimaryButton` (lleno), y las salidas son `FooterAction`, la misma
píldora pero hueca — se ven y se pulsan igual de bien sin competir con la acción
principal.

---

## 16. Actualización automática

Sin Play Store: releases de GitHub. Explicado a fondo en
[`AUTOACTUALIZACION.md`](AUTOACTUALIZACION.md); el resumen de código es:

- `Updater.check()` pide `releases/latest` a la API de GitHub sin credenciales
  (el repositorio debe ser público) y lee la etiqueta con el formato
  `v<versionName>-b<versionCode>`. Devuelve `Available`, `UpToDate` o `Failed`.
- `Updater.download()` baja la APK a la caché con progreso real, escribiendo a
  disco directamente.
- `Updater.install()` la abre con el instalador del sistema a través del
  `FileProvider` declarado en el manifiesto; hace falta el permiso
  `REQUEST_INSTALL_PACKAGES`, que se concede fuera de la app — por eso `AppRoot`
  relee `canInstall()` en cada `ON_RESUME`.
- `UpdateViewModel` es la máquina de estados (`UpdatePhase`: `IDLE`, `CHECKING`,
  `AVAILABLE`, `DOWNLOADING`, `READY`, `FAILED`) y `UpdateSheet` la pinta.
- `UpdateNotifier.schedule()` programa un `UpdateCheckWorker` (WorkManager) para
  la comprobación en segundo plano, con `alreadyNotified()` para no repetir el
  aviso de la misma versión.

La comprobación del arranque se calla los fallos; la manual de Ajustes los
cuenta, que es la única forma de distinguir "no hay nada" de "está roto".

---

## 17. Build, firma y versiones

[`app/build.gradle.kts`](app/build.gradle.kts):

- **`versionCode` = número de commits** (`git rev-list --count HEAD`). Android
  solo actualiza a un `versionCode` mayor; derivarlo de la versión semántica
  obliga a acordarse de subirla y el día que se olvida el canal se rompe en
  silencio. Con los commits crece solo. **Contrapartida: no se puede reescribir
  la historia de `main`** — un `push --force` que reduzca commits deja las
  releases nuevas por debajo de lo instalado. Hay un suelo (`versionCodeFloor`)
  para que un checkout superficial en CI no dé 1 sin avisar.
- **Firma**: `keystore.properties` en local (fuera de git), variables de entorno
  en CI. Android identifica la app por `applicationId` + firma: otra clave no es
  una actualización sino otra app, y la instalación falla con
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Publicación: [`.github/workflows/release.yml`](.github/workflows/release.yml).

Compilar y probar, con los comandos exactos, está en el README (sección
*Compilar y probar*). El parser tiene además su referencia en Python bajo
[`sim/`](sim/).
