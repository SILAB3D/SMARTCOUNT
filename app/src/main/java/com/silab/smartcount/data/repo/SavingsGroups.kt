package com.silab.smartcount.data.repo

import android.content.Context
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.Tricount
import java.text.Normalizer

/**
 * Grupos de ahorro.
 *
 * No son un tipo de grupo de Tricount: son un grupo normal **interpretado** de
 * otra manera, y la marca vive solo en este móvil. Para Tricount siguen siendo
 * un grupo de dos miembros con sus movimientos, así que la app oficial los
 * sigue abriendo sin enterarse de nada.
 *
 * La convención es la que hace el trabajo:
 *
 *  - dos miembros, **tú** y uno llamado *Ingresos*,
 *  - lo que creas tú son los **gastos** del grupo,
 *  - lo que crea *Ingresos* hacia ti son los **ingresos**,
 *  - el ahorro es la resta.
 *
 * Se mira el **propietario** de cada movimiento y no su tipo, porque es lo que
 * distingue de qué lado viene el dinero sea cual sea el tipo con el que se creó.
 */
class SavingsGroups(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("savings_groups", Context.MODE_PRIVATE)

    fun ids(): Set<Int> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    fun isSavings(id: Int): Boolean = id in ids()

    fun mark(id: Int, savings: Boolean) {
        val updated = if (savings) ids() + id else ids() - id
        prefs.edit().putStringSet(KEY, updated.map(Int::toString).toSet()).apply()
    }

    private companion object {
        const val KEY = "ids"
    }
}

/** Cifras de un grupo de ahorro. */
data class SavingsSummary(
    val income: Double,
    val spent: Double
) {
    /** Lo ahorrado: lo que ha entrado menos lo que ha salido. Puede ser negativo. */
    val saved: Double get() = income - spent
}

object Savings {

    /** Nombre convenido del miembro que representa el dinero que entra. */
    const val INCOME_MEMBER = "Ingresos"

    fun incomeMember(t: Tricount): Member? =
        t.members.firstOrNull { it.status == "ACTIVE" && matches(it.displayName, INCOME_MEMBER) }

    /** Un grupo está listo para funcionar como ahorro si existe el miembro *Ingresos*. */
    fun isReady(t: Tricount): Boolean = incomeMember(t) != null

    fun summary(t: Tricount): SavingsSummary {
        val incomeUuid = incomeMember(t)?.uuid
        var income = 0.0
        var spent = 0.0
        t.activeTransactions.forEach { tx ->
            val value = tx.amount.abs
            if (incomeUuid != null && tx.ownerUuid == incomeUuid) income += value else spent += value
        }
        return SavingsSummary(income = income, spent = spent)
    }

    /** «Ingresos» y «ingresos» son el mismo miembro; las tildes tampoco cuentan. */
    private fun matches(a: String, b: String) = fold(a) == fold(b)

    private fun fold(value: String): String =
        Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
