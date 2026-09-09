package com.silab.smartcount.data.cache

import android.content.Context
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.repo.Stats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val members: List<CachedMember> = emptyList()
) {
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
}

class GroupCache(context: Context) {

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
        old.copy(
            groups = groups.map { t ->
                val myName = t.linkedMember?.displayName
                CachedGroup(
                    id = t.id,
                    title = t.title,
                    emoji = t.emoji,
                    currency = t.currency,
                    myBalance = myName?.let { Stats.balances(t)[it] } ?: 0.0,
                    total = Stats.totalSpent(t),
                    myMembershipUuid = t.activeMembershipUuid,
                    members = t.members
                        .filter { it.status == "ACTIVE" }
                        .map { CachedMember(it.uuid, it.displayName) }
                )
            },
            selectedId = selectedId ?: old.selectedId
        )
    }

    fun savePendingCount(count: Int) = update { it.copy(pendingInbox = count) }

    companion object {
        private const val KEY = "snapshot"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
