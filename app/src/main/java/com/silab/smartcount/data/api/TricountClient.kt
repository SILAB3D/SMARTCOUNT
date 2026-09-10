package com.silab.smartcount.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
     * Es lo que habilita crear/editar/borrar gastos en él.
     */
    suspend fun joinTricount(publicToken: String): Tricount = withSession {
        val payload = buildJsonObject {
            putJsonArray("all_registry_active") {
                add(buildJsonObject { put("public_identifier_token", publicToken) })
            }
            putJsonArray("all_registry_archived") {}
            putJsonArray("all_registry_deleted") {}
        }
        val req = newRequest("$BASE_URL/v1/user/${requireUser()}/registry-synchronization")
            .post(jsonBody(payload))
            .build()

        val data = execute(req)
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

    suspend fun createTricount(title: String, currency: String = "EUR", description: String = ""): Int =
        withSession {
            val payload = buildJsonObject {
                put("title", title)
                put("currency", currency)
                put("description", description)
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
     * Reparte un importe entre N personas SIN perder céntimos: el resto de la
     * división se distribuye de uno en uno entre los primeros miembros, así la
     * suma de las partes es exactamente el total. Sin esto, un gasto de 39,90 €
     * entre 4 genera 4 × 9,98 = 39,92 y el balance del grupo se desvía céntimo
     * a céntimo en cada gasto no divisible.
     */
    internal fun splitEvenly(total: Double, n: Int): List<Double> {
        require(n > 0)
        val cents = Math.round(abs(total) * 100.0)
        val base = cents / n
        val extra = (cents % n).toInt()
        return (0 until n).map { i -> (base + if (i < extra) 1L else 0L) / 100.0 }
    }

    private fun allocationsJson(
        members: List<Member>,
        amounts: List<Double>,
        currency: String
    ): JsonArray = buildJsonArray {
        members.forEachIndexed { i, m ->
            add(buildJsonObject {
                put("membership_uuid", m.uuid)
                putJsonObject("amount") {
                    put("value", amounts[i].money())
                    put("currency", currency)
                }
                put("type", "AMOUNT")
            })
        }
    }

    private fun Double.money(): String = String.format(Locale.US, "%.2f", this)


    /**
     * Crea un gasto. `amount` en positivo; la API almacena los gastos en negativo.
     * El reparto es a partes iguales entre `splitAmong`.
     */
    suspend fun createExpense(
        tricount: Tricount,
        description: String,
        amount: Double,
        payer: Member,
        splitAmong: List<Member>,
        category: Category? = null,
        categoryCustom: String? = null,
        date: Date = Date()
    ): Int = withSession {
        require(splitAmong.isNotEmpty()) { "Hay que repartir el gasto entre al menos una persona" }
        val total = abs(amount)
        val parts = splitEvenly(total, splitAmong.size).map { -it }

        val payload = buildJsonObject {
            put("uuid", UUID.randomUUID().toString())
            put("description", description)
            putJsonObject("amount") {
                put("value", (-total).money())
                put("currency", tricount.currency)
            }
            put("membership_uuid_owner", payer.uuid)
            put("allocations", allocationsJson(splitAmong, parts, tricount.currency))
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
        splitAmong: List<Member>,
        category: Category? = null,
        date: Date = Date()
    ): Int = withSession {
        require(splitAmong.isNotEmpty())
        val total = abs(amount)
        val parts = splitEvenly(total, splitAmong.size)

        val payload = buildJsonObject {
            put("uuid", UUID.randomUUID().toString())
            put("description", description)
            putJsonObject("amount") {
                put("value", total.money())
                put("currency", tricount.currency)
            }
            put("membership_uuid_owner", receiver.uuid)
            put("allocations", allocationsJson(splitAmong, parts, tricount.currency))
            put("type_transaction", TxType.INCOME.name)
            put("status", "ACTIVE")
            put("date", apiDate(date))
            category?.let { put("category", it.apiValue) }
        }
        postEntry(tricount, payload)
    }

    /**
     * Reembolso / transferencia entre dos miembros (tipo BALANCE).
     * Es el tipo natural para un Bizum entre personas del grupo.
     */
    suspend fun createReimbursement(
        tricount: Tricount,
        payer: Member,
        receiver: Member,
        amount: Double,
        description: String = "Reembolso",
        date: Date = Date()
    ): Int = withSession {
        val total = abs(amount)
        val payload = buildJsonObject {
            put("uuid", UUID.randomUUID().toString())
            put("description", description)
            putJsonObject("amount") {
                put("value", total.money())
                put("currency", tricount.currency)
            }
            put("membership_uuid_owner", payer.uuid)
            putJsonArray("allocations") {
                add(buildJsonObject {
                    put("membership_uuid", receiver.uuid)
                    putJsonObject("amount") {
                        put("value", total.money())
                        put("currency", tricount.currency)
                    }
                    put("type", "AMOUNT")
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
     * Edita un gasto existente. Los parámetros a null conservan el valor actual.
     * Nota: la API no hace merge parcial, así que reenviamos el objeto completo.
     */
    suspend fun editTransaction(
        tricount: Tricount,
        tx: Transaction,
        description: String? = null,
        amount: Double? = null,
        payer: Member? = null,
        splitAmong: List<Member>? = null,
        category: Category? = null,
        categoryCustom: String? = null,
        date: Date? = null
    ) = withSession {
        val newDescription = description ?: tx.description
        val newAmountAbs = amount?.let { abs(it) } ?: tx.amount.abs
        val newPayerUuid = payer?.uuid ?: tx.ownerUuid
        val sign = if (tx.type == TxType.NORMAL) -1.0 else 1.0

        val members = splitAmong
            ?: tx.allocations.mapNotNull { tricount.memberByUuid(it.membershipUuid) }

        val allocations = if (members.isNotEmpty()) {
            allocationsJson(members, splitEvenly(newAmountAbs, members.size).map { sign * it }, tricount.currency)
        } else {
            buildJsonArray {
                tx.allocations.forEach { a ->
                    add(buildJsonObject {
                        put("membership_uuid", a.membershipUuid)
                        putJsonObject("amount") {
                            put("value", a.amount.value)
                            put("currency", a.amount.currency)
                        }
                        put("type", a.type)
                    })
                }
            }
        }

        val payload = buildJsonObject {
            put("description", newDescription)
            putJsonObject("amount") {
                put("value", (sign * newAmountAbs).money())
                put("currency", tricount.currency)
            }
            put("membership_uuid_owner", newPayerUuid)
            put("allocations", allocations)
            put("type_transaction", tx.type.name)
            put("status", tx.status)
            put("date", apiDate(date ?: Date()))
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

        val txId = tx.id ?: throw TricountException("El gasto no tiene id")
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
    // Miembros
    // -----------------------------------------------------------------------

    suspend fun addMembers(tricount: Tricount, names: List<String>) = withSession {
        val payload = buildJsonObject {
            putJsonArray("memberships") {
                tricount.members.forEach { m ->
                    add(buildJsonObject {
                        put("uuid", m.uuid)
                        putJsonObject("alias") { put("display_name", m.displayName) }
                        put("status", m.status)
                    })
                }
                names.forEach { n ->
                    add(buildJsonObject {
                        putJsonObject("alias") { put("display_name", n) }
                        put("status", "ACTIVE")
                    })
                }
            }
        }
        val req = newRequest("$BASE_URL/v1/user/${requireUser()}/registry/${tricount.id}")
            .put(jsonBody(payload))
            .build()
        execute(req)
        Unit
    }
}
// Los helpers obj/arr/str/int viven en Models.kt (mismo paquete).
