package com.silab.smartcount.data.repo

import android.content.Context
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.Tricount
import java.text.Normalizer

/**
 * Quién eres **tú** dentro de cada grupo.
 *
 * La API devuelve `membership_uuid_active` con el miembro vinculado a esta
 * instalación, y de ahí sale el balance que la pantalla enseña en grande. Pero
 * ese campo llega **null** en los grupos a los que esta instalación se unió por
 * enlace público sin reclamar ninguna identidad — que son justo los que se
 * añaden desde SmartCount. Sin él, `linkedMember` es null, tu balance se queda
 * en 0,00 y parece que la app no sabe calcularlo: es el motivo de que "en
 * algunos grupos no se detecte el balance".
 *
 * La solución no puede ser adivinar siempre: en un grupo de cinco personas no
 * hay forma de saber cuál eres. Así que se resuelve en tres pasos, del más
 * fiable al menos:
 *
 *  1. lo que diga la API, si lo dice,
 *  2. lo que hayas elegido a mano en este móvil (y aquí se queda),
 *  3. una deducción sólo cuando es inequívoca (ver [guess]).
 *
 * Si ninguno acierta, la pantalla del grupo lo pregunta en vez de mentir con
 * un cero.
 */
class MemberIdentity(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("member_identity", Context.MODE_PRIVATE)

    /** Tu nombre en el banco, que también sirve para reconocerte en el grupo. */
    private val bankPrefs = context.applicationContext
        .getSharedPreferences("bank_registry", Context.MODE_PRIVATE)

    fun stored(groupId: Int): String? = prefs.getString(key(groupId), null)

    fun set(groupId: Int, membershipUuid: String?) {
        prefs.edit().apply {
            if (membershipUuid == null) remove(key(groupId)) else putString(key(groupId), membershipUuid)
        }.apply()
    }

    /**
     * Devuelve el grupo con `activeMembershipUuid` relleno si se ha podido
     * averiguar. Trabajar sobre el modelo y no sobre cada pantalla evita tener
     * que acordarse de resolver la identidad en los seis sitios que la usan
     * (hoja de gasto, balance, caché del widget, asignación rápida…).
     */
    fun resolve(t: Tricount): Tricount {
        if (t.memberByUuid(t.activeMembershipUuid) != null) return t
        val chosen = stored(t.id)?.takeIf { uuid -> t.memberByUuid(uuid) != null }
            ?: guess(t)?.uuid
            ?: return t
        return t.copy(activeMembershipUuid = chosen)
    }

    /**
     * Deducción, sólo cuando no hay ambigüedad posible:
     *
     *  - el miembro que se llama como tú en el banco (Ajustes → *Tu nombre*),
     *  - o, en un grupo de dos donde uno es la fuente de ingresos de un grupo
     *    de ahorro, forzosamente eres el otro.
     *
     * Con tres desconocidos no se deduce nada: se pregunta.
     */
    fun guess(t: Tricount): Member? {
        val active = t.members.filter { it.status == "ACTIVE" }
        val ownName = bankPrefs.getString("own_name", null)?.takeIf { it.isNotBlank() }
        if (ownName != null) {
            active.firstOrNull { sameName(it.displayName, ownName) }?.let { return it }
        }
        val income = Savings.incomeMember(t)
        if (income != null && active.size == 2) {
            return active.firstOrNull { it.uuid != income.uuid }
        }
        return null
    }

    private fun key(groupId: Int) = "group_$groupId"

    private companion object {
        /** "IVÁN GARCÍA" y "Iván" son la misma persona; el orden tampoco importa. */
        fun sameName(a: String, b: String): Boolean {
            val x = fold(a)
            val y = fold(b)
            if (x.isBlank() || y.isBlank()) return false
            if (x == y) return true
            val xs = x.split(" ").filter { it.isNotBlank() }.toSet()
            val ys = y.split(" ").filter { it.isNotBlank() }.toSet()
            return xs.isNotEmpty() && ys.isNotEmpty() && (xs.containsAll(ys) || ys.containsAll(xs))
        }

        fun fold(value: String): String =
            Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
