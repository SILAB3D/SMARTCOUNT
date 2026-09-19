package com.silab.smartcount.data.cache

import android.content.Context
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.repo.SavingsGroups
import com.silab.smartcount.data.repo.Stats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Instantánea ligera de los grupos, guardada en disco.
 *
 * La necesitan dos consumidores que no pueden hacer red ni esperar:
 * el widget de la pantalla de inicio (se dibuja en el proceso del launcher) y
 * la notificación de movimiento detectado, que debe ofrecer los grupos al
 * instante, incluso sin cobertura.
 */
@Serializable
data class CachedMember(val uuid: String, val name: String)

@Serializable
data class CachedGroup(
    val id: Int,
    val title: String,
    val emoji: String? = null,
    val currency: String = "EUR",
    val myBalance: Double = 0.0,
    val total: Double = 0.0,
    val myMembershipUuid: String? = null,
    val members: List<CachedMember> = emptyList(),
    /** Los grupos de ahorro se leen distinto: ahorrado en vez de deuda. */
    val savings: Boolean = false,
    val income: Double = 0.0,
    val spent: Double = 0.0,
    /** Los dos papeles de un grupo de ahorro, para crear con ellos sin abrir la app. */
    val incomeUuid: String? = null,
    val spenderUuid: String? = null,
    /** Cuándo se registró el último movimiento del grupo. */
    val lastMovementAt: Long = 0,
    /** Cuándo apareció el grupo en esta app por primera vez. */
    val addedAt: Long = 0
) {
    /** La cifra que representa al grupo: lo ahorrado, o lo que te deben. */
    val headline: Double get() = if (savings) income - spent else myBalance

    /**
     * Lo reciente que es. Un grupo recién añadido cuenta como reciente aunque
     * no tenga movimientos todavía: si lo acabas de meter en la app, es
     * justamente donde vas a querer llevar lo próximo.
     */
    val recency: Long get() = maxOf(lastMovementAt, addedAt)

    fun memberByName(name: String?): CachedMember? {
        if (name.isNullOrBlank()) return null
        val needle = name.trim().lowercase()
        return members.firstOrNull { it.name.trim().lowercase() == needle }
            ?: members.firstOrNull { m ->
                // "Ben Torres" en el Bizum contra "Ben" en el grupo, y viceversa
                val a = m.name.trim().lowercase()
                a.isNotEmpty() && (needle.startsWith("$a ") || a.startsWith("${needle.substringBefore(' ')} "))
            }
    }
}

@Serializable
data class CacheSnapshot(
    val groups: List<CachedGroup> = emptyList(),
    val selectedId: Int? = null,
    val pendingInbox: Int = 0,
    val updatedAt: Long = 0
) {
    val selected: CachedGroup?
        get() = groups.firstOrNull { it.id == selectedId } ?: groups.firstOrNull()

    /**
     * El grupo normal más reciente y el de ahorro más reciente: los dos que
     * ofrece la notificación de un movimiento detectado.
     */
    val mostRecentNormal: CachedGroup?
        get() = groups.filterNot { it.savings }.maxByOrNull { it.recency }

    val mostRecentSavings: CachedGroup?
        get() = groups.filter { it.savings }.maxByOrNull { it.recency }
}

class GroupCache(context: Context) {

    private val context = context.applicationContext

    private val prefs = context.applicationContext
        .getSharedPreferences("group_cache", Context.MODE_PRIVATE)

    fun read(): CacheSnapshot {
        val raw = prefs.getString(KEY, null) ?: return CacheSnapshot()
        return runCatching { json.decodeFromString<CacheSnapshot>(raw) }
            .getOrDefault(CacheSnapshot())
    }

    fun write(snapshot: CacheSnapshot) {
        prefs.edit().putString(KEY, json.encodeToString(CacheSnapshot.serializer(), snapshot)).apply()
    }

    fun update(block: (CacheSnapshot) -> CacheSnapshot) {
        write(block(read()).copy(updatedAt = System.currentTimeMillis()))
    }

    fun saveGroups(groups: List<Tricount>, selectedId: Int?) = update { old ->
        val savings = SavingsGroups(context)
        val now = System.currentTimeMillis()
        old.copy(
            groups = groups.map { t ->
                val isSavings = savings.isSavings(t.id)
                val summary = if (isSavings) savings.summary(t) else null
                // La primera vez que vemos un grupo es "cuando se añadió".
                val addedAt = old.groups.firstOrNull { it.id == t.id }?.addedAt?.takeIf { it > 0 } ?: now
                CachedGroup(
                    id = t.id,
                    title = t.title,
                    emoji = t.emoji,
                    currency = t.currency,
                    myBalance = Stats.balanceOf(t, t.activeMembershipUuid),
                    total = Stats.totalSpent(t),
                    myMembershipUuid = t.activeMembershipUuid,
                    members = t.members
                        .filter { it.status == "ACTIVE" }
                        .map { CachedMember(it.uuid, it.displayName) },
                    savings = isSavings,
                    income = summary?.income ?: 0.0,
                    spent = summary?.spent ?: 0.0,
                    incomeUuid = if (isSavings) savings.incomeMember(t)?.uuid else null,
                    spenderUuid = if (isSavings) savings.spenderMember(t)?.uuid else null,
                    lastMovementAt = lastMovementAt(t),
                    addedAt = addedAt
                )
            },
            selectedId = selectedId ?: old.selectedId
        )
    }

    fun savePendingCount(count: Int) = update { it.copy(pendingInbox = count) }

    companion object {
        private const val KEY = "snapshot"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private val API_DATE = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.US)

        /**
         * Cuándo se registró el último movimiento del grupo.
         *
         * Se mira `created` y no `date`: la fecha la pone quien crea el
         * movimiento y puede ser de hace meses — apuntar hoy la cena del
         * viernes pasado es lo normal —, mientras que `created` dice cuándo
         * pasó de verdad por la app. Para «el grupo más reciente» interesa lo
         * segundo.
         */
        internal fun lastMovementAt(t: Tricount): Long =
            t.activeTransactions.mapNotNull { parseApiDate(it.created.ifBlank { it.date }) }.maxOrNull() ?: 0L

        private fun parseApiDate(raw: String): Long? {
            if (raw.isBlank()) return null
            return runCatching { synchronized(API_DATE) { API_DATE.parse(raw) }?.time }.getOrNull()
        }
    }
}
