package com.silab.smartcount.data.repo

import android.content.Context
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.Transaction
import com.silab.smartcount.data.api.Tricount
import java.text.Normalizer

/**
 * Grupos de ahorro.
 *
 * No son un tipo de grupo de Tricount: son un grupo normal **interpretado** de
 * otra manera, y la marca vive solo en este móvil. Para Tricount siguen siendo
 * un grupo con sus movimientos, así que la app oficial los sigue abriendo sin
 * enterarse de nada.
 *
 * La convención es la que hace el trabajo:
 *
 *  - una **fuente de ingresos**: un miembro que suele llamarse *Ingresos*,
 *  - una **fuente de gastos**: tú,
 *  - lo que sale de la fuente de ingresos es un ingreso, lo demás es gasto,
 *  - el ahorro es la resta.
 *
 * Se mira el **propietario** de cada movimiento y no su tipo, porque es lo que
 * distingue de qué lado viene el dinero sea cual sea el tipo con el que se creó:
 * un `INCOME` y un reembolso `BALANCE` de *Ingresos* hacia ti son lo mismo aquí,
 * y filtrar por tipo dejaría fuera uno de los dos.
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

    /**
     * Qué miembro es la fuente de ingresos, cuando no se llama como manda la
     * convención. Existe porque los grupos reales no la respetan: el miembro
     * puede llamarse «Ingreso», «Nómina» o el nombre de quien aporta el dinero.
     */
    fun incomeMemberUuid(groupId: Int): String? = prefs.getString(incomeKey(groupId), null)

    fun setIncomeMember(groupId: Int, uuid: String?) {
        prefs.edit().apply {
            if (uuid == null) remove(incomeKey(groupId)) else putString(incomeKey(groupId), uuid)
        }.apply()
    }

    /** La fuente de ingresos efectiva: lo elegido a mano, o la convención. */
    fun incomeMember(t: Tricount): Member? =
        incomeMemberUuid(t.id)?.let { t.memberByUuid(it) } ?: Savings.incomeMember(t)

    /** Cifras del grupo con la fuente de ingresos que corresponda. */
    fun summary(t: Tricount): SavingsSummary = Savings.summary(t, incomeMember(t)?.uuid)

    fun isIncome(t: Tricount, tx: Transaction): Boolean =
        incomeMember(t)?.uuid?.let { tx.ownerUuid == it } == true

    private fun incomeKey(groupId: Int) = "income_member_$groupId"

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

    operator fun plus(other: SavingsSummary) =
        SavingsSummary(income + other.income, spent + other.spent)

    companion object {
        val ZERO = SavingsSummary(0.0, 0.0)
    }
}

object Savings {

    /** Nombre convenido del miembro que representa el dinero que entra. */
    const val INCOME_MEMBER = "Ingresos"

    /**
     * Nombres que se aceptan como fuente de ingresos. Son varios a propósito:
     * un grupo creado a mano en Tricount tiene el miembro en singular tan a
     * menudo como en plural, y exigir la forma exacta dejaba fuera grupos que
     * por lo demás cumplen la convención al pie de la letra.
     */
    private val INCOME_NAMES = setOf("ingresos", "ingreso", "income", "nomina", "nominas")

    fun incomeMember(t: Tricount): Member? =
        t.members.firstOrNull { it.status == "ACTIVE" && fold(it.displayName) in INCOME_NAMES }

    /** Un grupo está listo para funcionar como ahorro si hay fuente de ingresos. */
    fun isReady(t: Tricount): Boolean = incomeMember(t) != null

    /**
     * Reparte los movimientos en ingresos y gastos según de quién salen.
     * Sin fuente de ingresos identificada todo cuenta como gasto, que es la
     * lectura honesta: no hay nada marcado como dinero que entra.
     */
    fun summary(t: Tricount, incomeUuid: String? = incomeMember(t)?.uuid): SavingsSummary {
        var income = 0.0
        var spent = 0.0
        t.activeTransactions.forEach { tx ->
            val value = tx.amount.abs
            if (incomeUuid != null && tx.ownerUuid == incomeUuid) income += value else spent += value
        }
        return SavingsSummary(income = income, spent = spent)
    }

    /** «Ingresos» y «ingresos» son el mismo miembro; las tildes tampoco cuentan. */
    private fun fold(value: String): String =
        Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
