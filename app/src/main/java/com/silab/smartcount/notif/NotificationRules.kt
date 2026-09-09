package com.silab.smartcount.notif

import android.content.Context
import com.silab.smartcount.data.db.DetectedKind
import java.text.Normalizer

/** Qué hacer con un movimiento detectado. */
enum class MovementPolicy {
    NOTIFY,      // notificación con los grupos como botones, y a la bandeja
    INBOX_ONLY,  // sin notificación, pero queda en la bandeja
    IGNORE;      // ni notificación ni bandeja

    val label: String
        get() = when (this) {
            NOTIFY -> "Avisar"
            INBOX_ONLY -> "Solo bandeja"
            IGNORE -> "Ignorar"
        }

    fun next(): MovementPolicy = when (this) {
        NOTIFY -> INBOX_ONLY
        INBOX_ONLY -> IGNORE
        IGNORE -> NOTIFY
    }
}

/**
 * Reglas de aviso. Dos niveles, porque el tipo de movimiento no basta:
 *
 *  - Por **tipo**: los recibos domiciliados o los cargos sin identificar suelen
 *    no merecer una notificación, pero sí quedar a mano en la bandeja.
 *  - Por **origen**: una suscripción llega como un pago con tarjeta normal y
 *    corriente (Netflix, Filmin, Movistar…). Lo único que la distingue es el
 *    comercio, así que se silencia por nombre, y desde la propia notificación.
 */
class NotificationRules(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("notification_rules", Context.MODE_PRIVATE)

    // -- por tipo ---------------------------------------------------------

    fun policyFor(kind: DetectedKind): MovementPolicy {
        val stored = prefs.getString(keyFor(kind), null) ?: return defaultFor(kind)
        return runCatching { MovementPolicy.valueOf(stored) }.getOrDefault(defaultFor(kind))
    }

    fun setPolicy(kind: DetectedKind, policy: MovementPolicy) {
        prefs.edit().putString(keyFor(kind), policy.name).apply()
    }

    private fun keyFor(kind: DetectedKind) = "policy_${kind.name}"

    /**
     * Por defecto avisamos de lo que suele ser compartible y dejamos en la
     * bandeja lo recurrente o lo que el parser no ha sabido identificar.
     */
    private fun defaultFor(kind: DetectedKind): MovementPolicy = when (kind) {
        DetectedKind.DIRECT_DEBIT,
        DetectedKind.INCOME_OTHER,
        DetectedKind.SPEND_OTHER -> MovementPolicy.INBOX_ONLY
        DetectedKind.SELF_TRANSFER -> MovementPolicy.IGNORE
        else -> MovementPolicy.NOTIFY
    }

    /** Los tipos que se muestran en Ajustes, en orden útil. */
    fun configurableKinds(): List<DetectedKind> = listOf(
        DetectedKind.BIZUM_RECEIVED, DetectedKind.BIZUM_SENT,
        DetectedKind.TRANSFER_RECEIVED, DetectedKind.TRANSFER_SENT,
        DetectedKind.CARD_SPEND, DetectedKind.CARD_ADJUSTMENT,
        DetectedKind.DIRECT_DEBIT, DetectedKind.REFUND,
        DetectedKind.JOINT_SPEND, DetectedKind.JOINT_WITHDRAWAL, DetectedKind.JOINT_INCOME,
        DetectedKind.INCOME_OTHER, DetectedKind.SPEND_OTHER
    )

    // -- por origen -------------------------------------------------------

    /** Comercios o personas silenciados: no vuelven a avisar ni a la bandeja. */
    fun mutedSources(): Set<String> = prefs.getStringSet(KEY_MUTED, emptySet()) ?: emptySet()

    fun mute(source: String) {
        val key = normalize(source)
        if (key.isBlank()) return
        prefs.edit().putStringSet(KEY_MUTED, mutedSources() + key).apply()
    }

    fun unmute(source: String) {
        prefs.edit().putStringSet(KEY_MUTED, mutedSources() - normalize(source)).apply()
    }

    fun isMuted(vararg sources: String?): Boolean {
        val muted = mutedSources()
        if (muted.isEmpty()) return false
        return sources.filterNotNull().any { normalize(it) in muted }
    }

    /** Sin tildes, sin mayúsculas y sin ruido, para que "Filmin " y "filmin" sean lo mismo. */
    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Decisión final para un movimiento concreto. */
    fun decide(kind: DetectedKind, merchant: String?, counterparty: String?): MovementPolicy =
        if (isMuted(merchant, counterparty)) MovementPolicy.IGNORE else policyFor(kind)

    companion object {
        private const val KEY_MUTED = "muted_sources"
    }
}
