package com.silab.smartcount.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// Helpers de lectura tolerante: la API devuelve estructuras tipo
// [ { "RegistryEntry": {...} }, ... ] y campos que a veces faltan.
// ---------------------------------------------------------------------------

internal fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNullSafe()

internal fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this.content == "null") null else this.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Desenvuelve `{ "RegistryEntry": {...} }` quedándose con el primer valor. */
internal fun JsonObject.unwrapFirst(): JsonObject? =
    values.firstOrNull() as? JsonObject

// ---------------------------------------------------------------------------
// Modelos
// ---------------------------------------------------------------------------

enum class Category(val apiValue: String, val emoji: String, val label: String) {
    TRAVEL("TRAVEL", "🛏", "Alojamiento"),
    ENTERTAINMENT("ENTERTAINMENT", "🎤", "Ocio"),
    GROCERIES("GROCERIES", "🛒", "Compra"),
    HEALTHCARE("HEALTHCARE", "🦷", "Salud"),
    INSURANCE("INSURANCE", "🧯", "Seguros"),
    RENT_AND_UTILITIES("RENT_AND_UTILITIES", "🏠", "Vivienda"),
    FOOD_AND_DRINK("FOOD_AND_DRINK", "🍔", "Restaurantes"),
    SHOPPING("SHOPPING", "🛍", "Compras"),
    TRANSPORT("TRANSPORT", "🚕", "Transporte"),
    OTHER("OTHER", "✋", "Otros");

    companion object {
        fun fromApi(v: String?): Category? = entries.firstOrNull { it.apiValue == v }
    }
}

/** NORMAL = gasto, INCOME = ingreso, BALANCE = reembolso/transferencia entre miembros. */
enum class TxType { NORMAL, INCOME, BALANCE }

data class Money(val value: String, val currency: String) {
    val amount: Double get() = value.toDoubleOrNull() ?: 0.0
    val abs: Double get() = kotlin.math.abs(amount)

    companion object {
        fun parse(o: JsonObject?): Money =
            Money(o?.str("value") ?: "0", o?.str("currency") ?: "EUR")
    }
}

data class Member(
    val id: Int,
    val uuid: String,
    val displayName: String,
    val status: String = "ACTIVE"
) {
    companion object {
        fun parse(o: JsonObject): Member {
            val alias = o.obj("alias")
            val uuid = o.str("uuid").orEmpty()
            return Member(
                id = o.int("id") ?: 0,
                uuid = uuid,
                displayName = alias?.str("display_name") ?: uuid,
                status = o.str("status") ?: "ACTIVE"
            )
        }
    }
}

data class Allocation(
    val membershipUuid: String,
    val amount: Money,
    val type: String = "AMOUNT",
    val shareRatio: Int? = null
) {
    companion object {
        fun parse(o: JsonObject): Allocation {
            val direct = o.str("membership_uuid")
            val uuid = direct ?: o.obj("membership")?.unwrapFirst()?.str("uuid").orEmpty()
            return Allocation(
                membershipUuid = uuid,
                amount = Money.parse(o.obj("amount")),
                type = o.str("type") ?: "AMOUNT",
                shareRatio = o.int("share_ratio")
            )
        }
    }
}

data class Transaction(
    val id: Int?,
    val uuid: String,
    val description: String,
    val amount: Money,
    val ownerUuid: String,
    val allocations: List<Allocation>,
    val date: String,
    val status: String = "ACTIVE",
    val type: TxType = TxType.NORMAL,
    val category: String? = null,
    val categoryCustom: String? = null
) {
    companion object {
        fun parse(o: JsonObject): Transaction {
            val owner = o.str("membership_uuid_owner")
                ?: o.obj("membership_owned")?.unwrapFirst()?.str("uuid").orEmpty()
            return Transaction(
                id = o.int("id"),
                uuid = o.str("uuid").orEmpty(),
                description = o.str("description").orEmpty(),
                amount = Money.parse(o.obj("amount")),
                ownerUuid = owner,
                allocations = o.arr("allocations")
                    ?.mapNotNull { (it as? JsonObject)?.let(Allocation::parse) }
                    ?: emptyList(),
                date = o.str("date").orEmpty(),
                status = o.str("status") ?: "ACTIVE",
                type = runCatching { TxType.valueOf(o.str("type_transaction") ?: "NORMAL") }
                    .getOrDefault(TxType.NORMAL),
                category = o.str("category"),
                categoryCustom = o.str("category_custom")
            )
        }
    }
}

data class Tricount(
    val id: Int,
    val uuid: String,
    val title: String,
    val description: String,
    val currency: String,
    val publicToken: String,
    val members: List<Member> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val emoji: String? = null,
    val status: String = "READ_WRITE",
    val activeMembershipUuid: String? = null
) {
    val isArchived: Boolean get() = status == "READ_ONLY"

    fun memberByUuid(uuid: String?): Member? = members.firstOrNull { it.uuid == uuid }

    /** El miembro vinculado a tu cuenta, si lo hay: por defecto "quién paga". */
    val linkedMember: Member? get() = memberByUuid(activeMembershipUuid)

    val activeTransactions: List<Transaction>
        get() = transactions.filter { it.status == "ACTIVE" }

    companion object {
        fun parse(o: JsonObject): Tricount = Tricount(
            id = o.int("id") ?: 0,
            uuid = o.str("uuid").orEmpty(),
            title = o.str("title").orEmpty(),
            description = o.str("description").orEmpty(),
            currency = o.str("currency") ?: "EUR",
            publicToken = o.str("public_identifier_token").orEmpty(),
            members = o.arr("memberships")
                ?.mapNotNull { (it as? JsonObject)?.unwrapFirst()?.let(Member::parse) }
                ?: emptyList(),
            transactions = o.arr("all_registry_entry")
                ?.mapNotNull { entry ->
                    (entry as? JsonObject)?.get("RegistryEntry")?.let { re ->
                        Transaction.parse(re.jsonObject)
                    }
                }
                ?: emptyList(),
            emoji = o.str("emoji"),
            status = o.str("status") ?: "READ_WRITE",
            activeMembershipUuid = o.str("membership_uuid_active")
        )
    }
}

/**
 * Un pago del plan de liquidación: quién paga a quién y cuánto.
 *
 * Lleva los uuid además de los nombres porque el plan no solo se enseña: desde
 * el balance se puede registrar como transferencia, y para eso hace falta el
 * miembro exacto — dos personas del grupo pueden llamarse igual.
 */
data class SettlementLeg(
    val fromUuid: String,
    val fromName: String,
    val toUuid: String,
    val toName: String,
    val amount: Double
)
