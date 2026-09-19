package com.silab.smartcount.data.repo

import android.content.Context
import com.silab.smartcount.data.api.Tricount

/**
 * Los grupos archivados, anotados en el móvil.
 *
 * Hace falta porque la API no los devuelve: en cuanto un grupo se archiva
 * desaparece de `/registry` y tampoco aparece pidiéndolo por estado
 * (comprobado). Sin guardar aquí su enlace y su nombre, archivar sería
 * perderlo — no habría forma de listarlo para desarchivarlo, ni de saber
 * siquiera que existió.
 *
 * Se guarda lo mínimo para poder volver: el nombre para enseñarlo y el token
 * público, que es con lo que se vuelve a entrar.
 */
class ArchivedGroups(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("archived_groups", Context.MODE_PRIVATE)

    data class Entry(val token: String, val title: String, val emoji: String?, val archivedAt: Long)

    fun all(): List<Entry> =
        prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX)) return@mapNotNull null
            val parts = (value as? String)?.split(SEP) ?: return@mapNotNull null
            Entry(
                token = key.removePrefix(PREFIX),
                title = parts.getOrNull(0).orEmpty(),
                emoji = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                archivedAt = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            )
        }.sortedByDescending { it.archivedAt }

    fun remember(t: Tricount) {
        if (t.publicToken.isBlank()) return
        prefs.edit()
            .putString(
                PREFIX + t.publicToken,
                listOf(t.title, t.emoji.orEmpty(), System.currentTimeMillis().toString())
                    .joinToString(SEP)
            )
            .apply()
    }

    fun forget(token: String) {
        prefs.edit().remove(PREFIX + token).apply()
    }

    private companion object {
        const val PREFIX = "archived_"
        /** Un carácter que no aparece en un nombre de grupo ni en un emoji. */
        const val SEP = "\u001F"
    }
}
