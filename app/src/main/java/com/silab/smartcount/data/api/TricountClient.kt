package com.silab.smartcount.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class TricountException(message: String, val code: Int? = null) : Exception(message)

/**
 * Cliente de la API interna de Tricount (bunq).
 *
 * OJO: no es una API pública ni documentada. Puede cambiar sin aviso y su uso
 * queda fuera de los términos de servicio de Tricount. Uso personal, bajo tu
 * propia responsabilidad.
 *
 * Autenticación: se registra una "instalación" con un UUID y una clave pública
 * RSA en PKCS#1; la respuesta devuelve un token de sesión y el user id.
 */
class TricountClient(
    private val credentials: CredentialStore,
    private val http: OkHttpClient = defaultHttp(),
    /**
     * Último retoque a cada grupo recién leído. Se usa para rellenar quién eres
     * tú en el grupo cuando la API no lo dice (ver MemberIdentity). Va aquí, y
     * no en cada pantalla, para que también lo aprovechen la caché del widget y
     * la asignación rápida desde la notificación, que no pasan por la interfaz.
     */
    private val resolveIdentity: (Tricount) -> Tricount = { it }
) {

    companion object {
        const val BASE_URL = "https://api.tricount.bunq.com"
        const val USER_AGENT = "com.bunq.tricount.android:RELEASE:7.0.7:3174:ANDROID:13:C"

        private val JSON_MEDIA = "application/json".toMediaType()

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun apiDate(date: Date): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.US).format(date)

        /**
         * El reparto igualitario, tal y como se enseña antes de guardar. El
         * que manda es el del servidor — las asignaciones van en RATIO y las
         * calcula él —, pero la hoja tiene que decir cuánto le toca a cada uno
         * mientras escribes, y esta es la misma cuenta.
         */
        fun previewSplit(total: Double, n: Int): List<Double> {
            require(n > 0)
            val cents = Math.round(abs(total) * 100.0)
            val base = cents / n
            val extra = (cents % n).toInt()
            return (0 until n).map { i -> (base + if (i < extra) 1L else 0L) / 100.0 }
        }

        /** Extrae el token público de un enlace tipo https://tricount.com/es/tABC123 */
        fun extractPublicToken(input: String): String? {
            val trimmed = input.trim()
            val direct = Regex("^[A-Za-z0-9]{8,40}$").find(trimmed)?.value
            val fromUrl = Regex("tricount\\.com/(?:[a-z]{2}/)?([A-Za-z0-9]{8,40})")
                .find(trimmed)?.groupValues?.getOrNull(1)
            return fromUrl ?: direct
        }
    }

    @Volatile private var sessionToken: String? = null
    @Volatile private var userId: Int? = null

    val isAuthenticated: Boolean get() = sessionToken != null && userId != null

    // -----------------------------------------------------------------------
    // Infraestructura HTTP
    // -----------------------------------------------------------------------

    private fun newRequest(url: String): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("app-id", credentials.appId)
            .header("X-Bunq-Client-Request-Id", UUID.randomUUID().toString())
        sessionToken?.let { b.header("X-Bunq-Client-Authentication", it) }
        return b
    }

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw TricountException("HTTP ${resp.code}: ${body.take(400)}", resp.code)
            }
            if (body.isBlank()) buildJsonObject { }
            else json.parseToJsonElement(body).jsonObject
        }
    }

    private fun JsonObject.responseArray(): JsonArray =
        this["Response"] as? JsonArray
            ?: throw TricountException("Respuesta inesperada: ${this.toString().take(300)}")

    private fun JsonObject.extractId(): Int =
        (responseArray().firstOrNull() as? JsonObject)
            ?.obj("Id")?.int("id")
            ?: throw TricountException("No se pudo leer el id de la respuesta")

    private fun requireUser(): Int =
        userId ?: throw TricountException("Sesión no iniciada")

    private fun jsonBody(obj: JsonObject) = obj.toString().toRequestBody(JSON_MEDIA)

    // -----------------------------------------------------------------------
    // Sesión
    // -----------------------------------------------------------------------

    /** Registra la instalación y abre sesión. Devuelve el user id. */
    suspend fun authenticate(): Int {
        val payload = buildJsonObject {
            put("app_installation_uuid", credentials.appId)
            put("client_public_key", credentials.publicKeyPem)
            put("device_description", "Android")
        }
        val req = newRequest("$BASE_URL/v1/session-registry-installation")
            .post(jsonBody(payload))
            .build()

        val data = execute(req)
        var token: String? = null
        var uid: Int? = null
        data.responseArray().forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            o.obj("Token")?.str("token")?.let { token = it }
            o.obj("UserPerson")?.int("id")?.let { uid = it }
        }
        if (token == null || uid == null) {
            throw TricountException("No se pudo autenticar")
        }
        sessionToken = token
        userId = uid
        return uid!!
    }

    suspend fun ensureAuthenticated() {
        if (!isAuthenticated) authenticate()
    }

    /** Reintenta una vez tras re-autenticar si la sesión ha caducado. */
    private suspend fun <T> withSession(block: suspend () -> T): T {
        ensureAuthenticated()
        return try {
            block()
        } catch (e: TricountException) {
            if (e.code == 401 || e.code == 403) {
                sessionToken = null
                authenticate()
                block()
            } else throw e
        }
    }

    // -----------------------------------------------------------------------
    // Grupos (tricounts)
    // -----------------------------------------------------------------------

    /** Todos los tricounts sincronizados con esta cuenta. */
    suspend fun listTricounts(): List<Tricount> = withSession {
        val req = newRequest("$BASE_URL/v1/user/${requireUser()}/registry").get().build()
        execute(req).responseArray().mapNotNull { item ->
            (item as? JsonObject)?.obj("Registry")?.let(Tricount::parse)?.let(resolveIdentity)
        }
    }

    suspend fun getTricount(id: Int): Tricount =
        listTricounts().firstOrNull { it.id == id }
            ?: throw TricountException("No se encontró el tricount $id")

    /** Lectura por token público, sin unirse al grupo. */
    suspend fun peekTricount(publicToken: String): Tricount = withSession {
        val url = "$BASE_URL/v1/user/${requireUser()}/registry".toHttpUrl()
            .newBuilder()
            .addQueryParameter("public_identifier_token", publicToken)
            .build()
        val req = newRequest(url.toString()).get().build()
        execute(req).responseArray()
            .mapNotNull { (it as? JsonObject)?.obj("Registry") }
            .firstOrNull()?.let(Tricount::parse)?.let(resolveIdentity)
            ?: throw TricountException("No se encontró ningún tricount con ese enlace")
    }

    /**
     * Añade el tricount a esta cuenta a partir de su enlace público.
     * Es lo que habilita crear, editar y borrar movimientos en él.
     */
    suspend fun joinTricount(publicToken: String): Tricount = withSession {
        val data = syncRegistry(active = listOf(publicToken))

        var joinedId: Int? = null
        data.responseArray().forEach { item ->
            val sync = (item as? JsonObject)?.obj("RegistrySynchronization") ?: return@forEach
            sync.arr("all_registry_active")?.forEach { reg ->
                val o = reg as? JsonObject ?: return@forEach
                if (o.str("public_identifier_token") == publicToken) {
                    joinedId = o.int("id")
                }
            }
        }
        joinedId?.let { getTricount(it) } ?: peekTricount(publicToken)
    }

    /**
     * Crea un grupo nuevo. Los miembros se pueden dar ya en la creación, que
     * es una llamada menos que crearlo y luego añadirlos.
     */
    suspend fun createTricount(
        title: String,
        currency: String = "EUR",
        description: String = "",
        memberNames: List<String> = emptyList(),
        emoji: String? = null
    ): Int = withSession {
        val clean = memberNames.map { it.trim() }.filter { it.isNotEmpty() }
        val payload = buildJsonObject {
            put("title", title.trim())
            put("currency", currency)
            put("description", description)
            emoji?.let { put("emoji", it) }
            if (clean.isNotEmpty()) {
                putJsonArray("memberships") { clean.forEach { add(newMembershipJson(it)) } }
            }
        }
        val req = newRequest("$BASE_URL/v1/user/${requireUser()}/registry")
            .post(jsonBody(payload))
            .build()
        execute(req).extractId()
    }

    // -----------------------------------------------------------------------
    // Gastos
    // -----------------------------------------------------------------------

    /**
     * Reparte un importe entre N personas sin perder céntimos: el resto de la
     * división se distribuye de uno en uno entre los primeros miembros, así la
     * suma de las partes es exactamente el total.
     *
     * Ya no construye ninguna petición — de eso se encarga el servidor cuando
     * las asignaciones van en RATIO —, pero sigue haciendo falta para
     * **enseñar** el reparto antes de guardarlo: la hoja de alta dice cuánto le
     * toca a cada uno mientras escribes.
     */
    internal fun splitEvenly(total: Double, n: Int): List<Double> = previewSplit(total, n)

    /**
     * Las asignaciones de un movimiento, con la forma que escribe la app
     * oficial: AMOUNT con su importe para las partes fijadas a mano, y RATIO 1
     * sin importe para las que se reparten lo que queda.
     */
    private fun allocationsJson(split: Split, sign: Double, currency: String): JsonArray =
        buildJsonArray {
            split.members.forEach { m ->
                add(buildJsonObject {
                    put("membership_uuid", m.uuid)
                    val fixed = split.fixed[m.uuid]
                    if (fixed == null) {
                        put("type", "RATIO")
                        put("share_ratio", 1)
                    } else {
                        putJsonObject("amount") {
                            put("value", (sign * abs(fixed)).money())
                            put("currency", currency)
                        }
                        put("type", "AMOUNT")
                    }
                })
            }
        }

    /**
     * Comprueba el reparto antes de salir a la red. El servidor también lo
     * comprueba — responde 400 "the amounts of the allocations that you
     * provided do not sum up to the amount of the entry" —, pero desde aquí se
     * puede decir cuánto falta en vez de enseñar el error crudo de la API.
     */
    private fun Split.validate(total: Double) {
        require(members.isNotEmpty()) { "Hay que repartir el movimiento entre al menos una persona" }
        if (free.isEmpty()) {
            val diff = total - fixedTotal
            require(abs(diff) < 0.005) {
                if (diff > 0) "Faltan ${diff.money()} por asignar"
                else "Las partes se pasan en ${(-diff).money()} del total"
            }
        } else {
            require(fixedTotal <= total + 0.005) {
                "Las partes fijadas suman ${fixedTotal.money()}, más que el total ${total.money()}"
            }
        }
    }

    private fun Double.money(): String = String.format(Locale.US, "%.2f", this)

    /**
     * Crea un gasto. `amount` llega en positivo; la API los almacena en
     * negativo. El reparto lo describe [Split]: a partes iguales mientras
     * nadie fije cantidades.
     */
    suspend fun createExpense(
        tricount: Tricount,
        description: String,
        amount: Double,
        payer: Member,
        split: Split,
        category: Category? = null,
        categoryCustom: String? = null,
        date: Date = Date()
    ): Int = withSession {
        val total = abs(amount)
        split.validate(total)

        val payload = buildJsonObject {
            put("uuid", UUID.randomUUID().toString())
            put("description", description)
            putJsonObject("amount") {
                put("value", (-total).money())
                put("currency", tricount.currency)
            }
            put("membership_uuid_owner", payer.uuid)
            put("allocations", allocationsJson(split, -1.0, tricount.currency))
            put("type_transaction", TxType.NORMAL.name)
            put("status", "ACTIVE")
            put("date", apiDate(date))
            when {
                categoryCustom != null -> {
                    put("category", "OTHER")
                    put("category_custom", categoryCustom)
                }
                category != null -> put("category", category.apiValue)
            }
        }
        postEntry(tricount, payload)
    }

    /** Ingreso: importes en positivo, `receiver` es quien lo recibe. */
    suspend fun createIncome(
        tricount: Tricount,
        description: String,
        amount: Double,
        receiver: Member,
        split: Split,
        category: Category? = null,
        categoryCustom: String? = null,
        date: Date = Date()
    ): Int = withSession {
        val total = abs(amount)
        split.validate(total)

        val payload = buildJsonObject {
            put("uuid", UUID.randomUUID().toString())
            put("description", description)
            putJsonObject("amount") {
                put("value", total.money())
                put("currency", tricount.currency)
            }
            put("membership_uuid_owner", receiver.uuid)
            put("allocations", allocationsJson(split, 1.0, tricount.currency))
            put("type_transaction", TxType.INCOME.name)
            put("status", "ACTIVE")
            put("date", apiDate(date))
            when {
                categoryCustom != null -> {
                    put("category", "OTHER")
                    put("category_custom", categoryCustom)
                }
                category != null -> put("category", category.apiValue)
            }
        }
        postEntry(tricount, payload)
    }

    /**
     * Reembolso / transferencia entre dos miembros (tipo BALANCE). Es el tipo
     * natural para un Bizum entre personas del grupo.
     *
     * Va en **negativo**, con quien recibe llevándose el importe entero en una
     * asignación RATIO y quien paga a cero en una AMOUNT: es la forma exacta
     * que escribe la app oficial, leída de sus propios movimientos y
     * reproducida contra la API. Antes se mandaba en positivo; el balance
     * salía igual — Stats trabaja con valores absolutos y su propia tabla de
     * signos — pero el apunte no era el mismo que ve el resto del grupo desde
     * Tricount.
     */
    suspend fun createReimbursement(
        tricount: Tricount,
        payer: Member,
        receiver: Member,
        amount: Double,
        description: String = "Reembolso",
        date: Date = Date()
    ): Int = withSession {
        require(payer.uuid != receiver.uuid) {
            "Una transferencia necesita dos personas distintas"
        }
        val total = abs(amount)
        val payload = buildJsonObject {
            put("uuid", UUID.randomUUID().toString())
            put("description", description)
            putJsonObject("amount") {
                put("value", (-total).money())
                put("currency", tricount.currency)
            }
            put("membership_uuid_owner", payer.uuid)
            putJsonArray("allocations") {
                add(buildJsonObject {
                    put("membership_uuid", receiver.uuid)
                    putJsonObject("amount") {
                        put("value", (-total).money())
                        put("currency", tricount.currency)
                    }
                    put("type", "RATIO")
                    put("share_ratio", 1)
                })
                add(buildJsonObject {
                    put("membership_uuid", payer.uuid)
                    putJsonObject("amount") {
                        put("value", "0")
                        put("currency", tricount.currency)
                    }
                    put("type", "AMOUNT")
                })
            }
            put("type_transaction", TxType.BALANCE.name)
            put("status", "ACTIVE")
            put("date", apiDate(date))
        }
        postEntry(tricount, payload)
    }

    private suspend fun postEntry(tricount: Tricount, payload: JsonObject): Int {
        val req = newRequest("$BASE_URL/v1/user/${requireUser()}/registry/${tricount.id}/registry-entry")
            .post(jsonBody(payload))
            .build()
        return execute(req).extractId()
    }

    /**
     * Edita un movimiento existente. Lo que llegue a null conserva el valor
     * que ya tenía. La API no hace merge parcial, así que se reenvía el objeto
     * entero.
     *
     * **El tipo no se toca**: el signo del importe y la forma de las
     * asignaciones dependen de él, así que pasar de gasto a transferencia es
     * borrar y volver a crear, no editar.
     */
    suspend fun editTransaction(
        tricount: Tricount,
        tx: Transaction,
        description: String? = null,
        amount: Double? = null,
        owner: Member? = null,
        split: Split? = null,
        /** Solo en las transferencias: quién recibe el dinero. */
        counterpart: Member? = null,
        category: Category? = null,
        categoryCustom: String? = null,
        date: Date? = null
    ) = withSession {
        val newDescription = description ?: tx.description
        val newAmountAbs = amount?.let { abs(it) } ?: tx.amount.abs
        val newOwnerUuid = owner?.uuid ?: tx.ownerUuid
        // El ingreso va en positivo; el gasto y la transferencia, en negativo.
        val sign = if (tx.type == TxType.INCOME) 1.0 else -1.0

        val allocations = if (tx.type == TxType.BALANCE) {
            val receiverUuid = counterpart?.uuid
                ?: tx.allocations.firstOrNull { it.membershipUuid != tx.ownerUuid }?.membershipUuid
                ?: throw TricountException("La transferencia no dice quién recibe el dinero")
            require(receiverUuid != newOwnerUuid) {
                "Una transferencia necesita dos personas distintas"
            }
            buildJsonArray {
                add(buildJsonObject {
                    put("membership_uuid", receiverUuid)
                    putJsonObject("amount") {
                        put("value", (-newAmountAbs).money())
                        put("currency", tricount.currency)
                    }
                    put("type", "RATIO")
                    put("share_ratio", 1)
                })
                add(buildJsonObject {
                    put("membership_uuid", newOwnerUuid)
                    putJsonObject("amount") {
                        put("value", "0")
                        put("currency", tricount.currency)
                    }
                    put("type", "AMOUNT")
                })
            }
        } else {
            // Sin reparto nuevo se reconstruye el que ya tenía, conservando
            // **cuáles** estaban fijados a mano: rehacerlo como un reparto
            // igualitario convertiría en igualitario, sin avisar, un gasto que
            // alguien repartió a propósito de otra manera.
            val effective = split ?: Split(
                members = tx.allocations.mapNotNull { tricount.memberByUuid(it.membershipUuid) },
                fixed = tx.allocations
                    .filter { it.type == "AMOUNT" }
                    .associate { it.membershipUuid to it.amount.abs }
            )
            effective.validate(newAmountAbs)
            allocationsJson(effective, sign, tricount.currency)
        }

        val payload = buildJsonObject {
            put("description", newDescription)
            putJsonObject("amount") {
                put("value", (sign * newAmountAbs).money())
                put("currency", tricount.currency)
            }
            put("membership_uuid_owner", newOwnerUuid)
            put("allocations", allocations)
            put("type_transaction", tx.type.name)
            put("status", tx.status)
            // Sin fecha nueva se conserva la suya. Antes se mandaba la de hoy,
            // así que corregir una errata en la descripción movía el gasto al
            // día en que lo corregías.
            put("date", date?.let { apiDate(it) } ?: tx.date)
            when {
                categoryCustom != null -> {
                    put("category", "OTHER")
                    put("category_custom", categoryCustom)
                }
                category != null -> {
                    put("category", category.apiValue)
                    put("category_custom", "")
                }
                else -> {
                    tx.category?.let { put("category", it) }
                    tx.categoryCustom?.let { put("category_custom", it) }
                }
            }
        }

        val txId = tx.id ?: throw TricountException("El movimiento no tiene id")
        val req = newRequest("$BASE_URL/v1/user/${requireUser()}/registry/${tricount.id}/registry-entry/$txId")
            .put(jsonBody(payload))
            .build()
        execute(req)
        Unit
    }

    suspend fun deleteTransaction(tricount: Tricount, transactionId: Int) = withSession {
        val req = newRequest("$BASE_URL/v1/user/${requireUser()}/registry/${tricount.id}/registry-entry/$transactionId")
            .delete()
            .build()
        execute(req)
        Unit
    }

    // -----------------------------------------------------------------------
    // El grupo: título, emoji, miembros y archivado
    // -----------------------------------------------------------------------

    /**
     * Un miembro nuevo, con la forma que exige la API: `alias` no es un nombre
     * suelto sino un *pointer*, con su `type`, su `value` y su `name`.
     *
     * Mandarlo como `alias.display_name` — que es como lo hacía esta app — lo
     * rechaza con «Superfluous field "display_name"». Es decir: la conversión
     * a grupo de ahorro nunca llegó a crear el miembro «Ingresos». No se notó
     * porque el grupo con el que se probó ya lo tenía.
     */
    private fun newMembershipJson(name: String): JsonObject {
        val uuid = UUID.randomUUID().toString()
        return buildJsonObject {
            put("uuid", uuid)
            putJsonObject("alias") {
                put("type", "UUID")
                put("value", uuid)
                put("name", name)
            }
            put("status", "ACTIVE")
        }
    }

    /** A los que ya están les basta su uuid: el resto lo conserva el servidor. */
    private fun keepMembershipJson(m: Member): JsonObject = buildJsonObject {
        put("uuid", m.uuid)
    }

    private fun renamedMembershipJson(m: Member, name: String): JsonObject = buildJsonObject {
        put("uuid", m.uuid)
        putJsonObject("alias") {
            put("type", "UUID")
            put("value", m.uuid)
            put("name", name)
        }
        put("status", m.status)
    }

    private suspend fun putRegistry(tricountId: Int, payload: JsonObject) {
        val req = newRequest("$BASE_URL/v1/user/${requireUser()}/registry/$tricountId")
            .put(jsonBody(payload))
            .build()
        execute(req)
    }

    /** Renombra el grupo, le cambia el emoji, o las dos cosas. */
    suspend fun updateTricount(
        tricount: Tricount,
        title: String? = null,
        emoji: String? = null
    ) = withSession {
        require(title != null || emoji != null) { "No hay nada que cambiar" }
        putRegistry(tricount.id, buildJsonObject {
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
            emoji?.let { put("emoji", it) }
        })
    }

    /**
     * Añade miembros. La lista se manda **entera**: los que ya estaban van
     * solo con su uuid y los nuevos con su alias completo.
     */
    suspend fun addMembers(tricount: Tricount, names: List<String>) = withSession {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        require(clean.isNotEmpty()) { "Hace falta al menos un nombre" }
        putRegistry(tricount.id, buildJsonObject {
            putJsonArray("memberships") {
                tricount.members.forEach { add(keepMembershipJson(it)) }
                clean.forEach { add(newMembershipJson(it)) }
            }
        })
    }

    suspend fun renameMember(tricount: Tricount, member: Member, name: String) = withSession {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "El nombre no puede quedar vacío" }
        putRegistry(tricount.id, buildJsonObject {
            putJsonArray("memberships") {
                tricount.members.forEach {
                    add(if (it.uuid == member.uuid) renamedMembershipJson(it, clean) else keepMembershipJson(it))
                }
            }
        })
    }

    /**
     * **Quitar un miembro no se puede.** No es que falte por hacer: la API no
     * lo permite por ninguna de las vías que tiene. Omitirlo de la lista de
     * `memberships` lo deja intacto, mandarlo con `status: INACTIVE` lo
     * devuelve como ACTIVE, y el borrado directo de la pertenencia responde
     * «Route not found». Comprobado contra la API con un grupo de usar y
     * tirar, con miembros recién creados y sin ningún movimiento a su nombre.
     *
     * Así que aquí no hay un `removeMember` que no funcione: un botón que no
     * hace nada y no lo dice es peor que no tener botón. Se renombra —eso sí
     * va— y quien necesite quitar a alguien lo hace desde la app oficial.
     */

    /**
     * La sincronización: la misma llamada con la que se entra a un grupo sirve
     * para archivarlo y para quitarlo. Solo se mencionan los grupos que
     * cambian — las listas no son el estado completo, son instrucciones.
     */
    private suspend fun syncRegistry(
        active: List<String> = emptyList(),
        archived: List<String> = emptyList(),
        deleted: List<String> = emptyList()
    ): JsonObject {
        fun JsonArrayBuilder.tokens(list: List<String>) =
            list.forEach { add(buildJsonObject { put("public_identifier_token", it) }) }

        val payload = buildJsonObject {
            putJsonArray("all_registry_active") { tokens(active) }
            putJsonArray("all_registry_archived") { tokens(archived) }
            putJsonArray("all_registry_deleted") { tokens(deleted) }
        }
        val req = newRequest("$BASE_URL/v1/user/${requireUser()}/registry-synchronization")
            .post(jsonBody(payload))
            .build()
        return execute(req)
    }

    /**
     * Archiva el grupo y lo desarchiva.
     *
     * Un grupo archivado **desaparece de `/registry`**: la API deja de
     * devolverlo, así que sin guardar su enlace no habría forma de traerlo de
     * vuelta. Por eso [com.silab.smartcount.data.repo.ArchivedGroups] anota el
     * token en el móvil antes de archivar.
     */
    suspend fun setArchived(tricount: Tricount, archived: Boolean) = withSession {
        val token = tricount.publicToken
        require(token.isNotBlank()) { "Este grupo no tiene enlace público" }
        if (archived) syncRegistry(archived = listOf(token)) else syncRegistry(active = listOf(token))
        Unit
    }

    suspend fun restoreArchived(publicToken: String) = withSession {
        syncRegistry(active = listOf(publicToken))
        Unit
    }

    /**
     * Quita el grupo de esta instalación. **No** lo borra para los demás: el
     * grupo sigue existiendo y se vuelve a entrar con su enlace. Borrarlo de
     * verdad (DELETE /registry/{id}) se lo dejamos a la app oficial: desde
     * aquí sería un botón que destruye los datos de más gente.
     */
    suspend fun unsyncTricount(tricount: Tricount) = withSession {
        val token = tricount.publicToken
        require(token.isNotBlank()) { "Este grupo no tiene enlace público" }
        syncRegistry(deleted = listOf(token))
        Unit
    }
}
// Los helpers obj/arr/str/int viven en Models.kt (mismo paquete).
