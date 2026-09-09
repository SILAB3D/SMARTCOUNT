package com.silab.smartcount.data.repo

import com.silab.smartcount.data.api.Category
import com.silab.smartcount.data.api.SettlementLeg
import com.silab.smartcount.data.api.Transaction
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.api.TxType
import kotlin.math.abs
import kotlin.math.round

/**
 * Cálculos locales de balance y estadísticas. Se hacen en el dispositivo a
 * partir de los movimientos descargados, para no depender de endpoints extra.
 */
object Stats {

    private fun r2(v: Double) = round(v * 100.0) / 100.0

    /**
     * Balance por miembro: positivo = le deben dinero, negativo = debe dinero.
     * La API guarda los gastos en negativo y los ingresos en positivo, así que
     * trabajamos con valores absolutos.
     */
    fun balances(t: Tricount): Map<String, Double> {
        val result = t.members.associate { it.displayName to 0.0 }.toMutableMap()

        for (tx in t.activeTransactions) {
            val payer = t.memberByUuid(tx.ownerUuid) ?: continue
            val total = tx.amount.abs

            result[payer.displayName] = (result[payer.displayName] ?: 0.0) + total
            for (alloc in tx.allocations) {
                val m = t.memberByUuid(alloc.membershipUuid) ?: continue
                result[m.displayName] = (result[m.displayName] ?: 0.0) - alloc.amount.abs
            }
        }
        return result.mapValues { r2(it.value) }
    }

    /**
     * Quién paga a quién para saldar cuentas, minimizando el número de pagos
     * (greedy: se empareja el mayor deudor con el mayor acreedor).
     */
    fun settlementPlan(t: Tricount): List<SettlementLeg> {
        val creditors = ArrayDeque<Pair<String, Double>>()
        val debtors = ArrayDeque<Pair<String, Double>>()

        balances(t).forEach { (name, bal) ->
            when {
                bal > 0.005 -> creditors.add(name to bal)
                bal < -0.005 -> debtors.add(name to -bal)
            }
        }
        val sortedCred = creditors.sortedByDescending { it.second }.toMutableList()
        val sortedDeb = debtors.sortedByDescending { it.second }.toMutableList()

        val legs = mutableListOf<SettlementLeg>()
        var i = 0
        var j = 0
        var credLeft = sortedCred.getOrNull(0)?.second ?: 0.0
        var debLeft = sortedDeb.getOrNull(0)?.second ?: 0.0

        while (i < sortedCred.size && j < sortedDeb.size) {
            val pay = minOf(credLeft, debLeft)
            if (pay > 0.005) {
                legs.add(SettlementLeg(sortedDeb[j].first, sortedCred[i].first, r2(pay)))
            }
            credLeft -= pay
            debLeft -= pay
            if (credLeft <= 0.005) { i++; credLeft = sortedCred.getOrNull(i)?.second ?: 0.0 }
            if (debLeft <= 0.005) { j++; debLeft = sortedDeb.getOrNull(j)?.second ?: 0.0 }
        }
        return legs
    }

    /** Total gastado (solo gastos, sin ingresos ni reembolsos). */
    fun totalSpent(t: Tricount): Double =
        r2(t.activeTransactions.filter { it.type == TxType.NORMAL }.sumOf { it.amount.abs })

    fun byCategory(t: Tricount): List<Pair<String, Double>> =
        t.activeTransactions
            .filter { it.type == TxType.NORMAL }
            .groupBy { tx ->
                tx.categoryCustom
                    ?: Category.fromApi(tx.category)?.let { "${it.emoji} ${it.label}" }
                    ?: "✋ Otros"
            }
            .map { (k, v) -> k to r2(v.sumOf { it.amount.abs }) }
            .sortedByDescending { it.second }

    /** Clave "yyyy-MM" → total. Ordenado cronológicamente. */
    fun byMonth(t: Tricount): List<Pair<String, Double>> =
        t.activeTransactions
            .filter { it.type == TxType.NORMAL }
            .groupBy { it.date.take(7) }
            .map { (k, v) -> k to r2(v.sumOf { it.amount.abs }) }
            .sortedBy { it.first }

    fun byPayer(t: Tricount): List<Pair<String, Double>> =
        t.activeTransactions
            .filter { it.type == TxType.NORMAL }
            .groupBy { t.memberByUuid(it.ownerUuid)?.displayName ?: "?" }
            .map { (k, v) -> k to r2(v.sumOf { it.amount.abs }) }
            .sortedByDescending { it.second }

    /** Lo que "te toca a ti": suma de tus asignaciones en los gastos. */
    fun myShare(t: Tricount): Double {
        val me = t.activeMembershipUuid ?: return 0.0
        return r2(
            t.activeTransactions
                .filter { it.type == TxType.NORMAL }
                .sumOf { tx -> tx.allocations.filter { it.membershipUuid == me }.sumOf { it.amount.abs } }
        )
    }

    fun averagePerTransaction(t: Tricount): Double {
        val txs = t.activeTransactions.filter { it.type == TxType.NORMAL }
        if (txs.isEmpty()) return 0.0
        return r2(txs.sumOf { it.amount.abs } / txs.size)
    }

    fun describe(tx: Transaction, t: Tricount): String {
        val payer = t.memberByUuid(tx.ownerUuid)?.displayName ?: "?"
        val amount = abs(tx.amount.amount)
        return when (tx.type) {
            TxType.NORMAL -> "$payer pagó ${"%.2f".format(amount)} ${t.currency}"
            TxType.INCOME -> "$payer recibió ${"%.2f".format(amount)} ${t.currency}"
            TxType.BALANCE -> {
                val to = tx.allocations
                    .firstOrNull { it.amount.abs > 0 }
                    ?.let { t.memberByUuid(it.membershipUuid)?.displayName }
                    ?: "?"
                "$payer → $to · ${"%.2f".format(amount)} ${t.currency}"
            }
        }
    }
}
