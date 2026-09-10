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
     * Con qué signo entra un movimiento al balance.
     *
     * Un gasto deja a favor a quien puso el dinero y a deber a los que se lo
     * reparten; una transferencia entre dos miembros funciona igual, porque
     * quien envía el dinero es el propietario del movimiento.
     *
     * Un ingreso va justo al revés: quien cobra en nombre del grupo — la fianza
     * que devuelve el casero, por ejemplo — se queda ese dinero en el bolsillo y
     * pasa a debérselo al resto. La API guarda los gastos en negativo y los
     * ingresos en positivo; aquí trabajamos con valores absolutos y este signo,
     * porque el reembolso (BALANCE) también se guarda en positivo y sin embargo
     * cuenta como un gasto.
     */
    private fun signOf(tx: Transaction): Double = if (tx.type == TxType.INCOME) -1.0 else 1.0

    /**
     * Balance por miembro, por uuid: positivo = le deben dinero, negativo = debe.
     *
     * Va por uuid y no por nombre porque dos miembros pueden llamarse igual, y
     * el mapa de [balances] los funde en una sola entrada.
     */
    fun balancesByUuid(t: Tricount): Map<String, Double> {
        val result = t.members.associate { it.uuid to 0.0 }.toMutableMap()

        for (tx in t.activeTransactions) {
            val payer = t.memberByUuid(tx.ownerUuid) ?: continue
            val sign = signOf(tx)

            result[payer.uuid] = (result[payer.uuid] ?: 0.0) + sign * tx.amount.abs
            for (alloc in tx.allocations) {
                val m = t.memberByUuid(alloc.membershipUuid) ?: continue
                result[m.uuid] = (result[m.uuid] ?: 0.0) - sign * alloc.amount.abs
            }
        }
        return result.mapValues { r2(it.value) }
    }

    /** El mismo balance, por nombre, para enseñarlo. */
    fun balances(t: Tricount): Map<String, Double> =
        balancesByUuid(t)
            .entries
            .groupBy { t.memberByUuid(it.key)?.displayName ?: "?" }
            .mapValues { (_, entries) -> r2(entries.sumOf { it.value }) }

    /** El balance de un miembro concreto, por uuid. */
    fun balanceOf(t: Tricount, membershipUuid: String?): Double {
        if (membershipUuid == null) return 0.0
        var total = 0.0
        for (tx in t.activeTransactions) {
            val sign = signOf(tx)
            if (tx.ownerUuid == membershipUuid) total += sign * tx.amount.abs
            total -= sign * tx.allocations
                .filter { it.membershipUuid == membershipUuid }
                .sumOf { it.amount.abs }
        }
        return r2(total)
    }

    /**
     * Quién paga a quién para saldar cuentas, minimizando el número de pagos
     * (greedy: se empareja el mayor deudor con el mayor acreedor).
     */
    fun settlementPlan(t: Tricount): List<SettlementLeg> {
        val creditors = mutableListOf<Pair<String, Double>>()
        val debtors = mutableListOf<Pair<String, Double>>()

        balancesByUuid(t).forEach { (uuid, bal) ->
            when {
                bal > 0.005 -> creditors.add(uuid to bal)
                bal < -0.005 -> debtors.add(uuid to -bal)
            }
        }
        val sortedCred = creditors.sortedByDescending { it.second }
        val sortedDeb = debtors.sortedByDescending { it.second }

        val legs = mutableListOf<SettlementLeg>()
        var i = 0
        var j = 0
        var credLeft = sortedCred.getOrNull(0)?.second ?: 0.0
        var debLeft = sortedDeb.getOrNull(0)?.second ?: 0.0

        while (i < sortedCred.size && j < sortedDeb.size) {
            val pay = minOf(credLeft, debLeft)
            if (pay > 0.005) {
                val from = sortedDeb[j].first
                val to = sortedCred[i].first
                legs.add(
                    SettlementLeg(
                        fromUuid = from,
                        fromName = t.memberByUuid(from)?.displayName ?: "?",
                        toUuid = to,
                        toName = t.memberByUuid(to)?.displayName ?: "?",
                        amount = r2(pay)
                    )
                )
            }
            credLeft -= pay
            debLeft -= pay
            if (credLeft <= 0.005) { i++; credLeft = sortedCred.getOrNull(i)?.second ?: 0.0 }
            if (debLeft <= 0.005) { j++; debLeft = sortedDeb.getOrNull(j)?.second ?: 0.0 }
        }
        return legs
    }

    /** ¿Está el grupo en paz? Es lo mismo que no quedar ningún pago pendiente. */
    fun isSettled(t: Tricount): Boolean = settlementPlan(t).isEmpty()

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
