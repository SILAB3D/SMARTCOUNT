package com.silab.smartcount.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.repo.Stats
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme

// ===========================================================================
// Pestaña 3 · Estadísticas
// ===========================================================================

/**
 * Dos estadísticas distintas bajo el mismo nombre, porque las preguntas no se
 * parecen: de un grupo normal se quiere saber **en qué se va el dinero**, y de
 * los de ahorro **cuánto queda y desde cuándo**. Mezclarlas producía medias
 * sin sentido — el "total gastado" de un grupo de ahorro incluía la nómina.
 */
@Composable
fun StatsScreen(vm: MainViewModel, state: UiState, modifier: Modifier = Modifier) {
    val c = SmartTheme.colors
    val normal = state.normalGroups
    val savings = state.savingsGroups
    var scope by remember { mutableStateOf(0) }

    // Sin grupos de ahorro no hay nada que separar: se enseña la de siempre.
    val showScopes = savings.isNotEmpty()
    val effectiveScope = if (showScopes) scope else 0

    LazyColumn(
        modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { ScreenTitle("Estadísticas") }

        if (showScopes) {
            item {
                SegmentedTabs(listOf("Grupos", "Ahorro"), effectiveScope) { scope = it }
                Spacer(Modifier.height(16.dp))
            }
        }

        if (effectiveScope == 0) {
            normalStats(this, vm, state, normal)
        } else {
            savingsStats(this, vm, savings)
        }
    }
}

// ---------------------------------------------------------------------------
// Grupos normales: en qué se va el dinero
// ---------------------------------------------------------------------------

private fun normalStats(
    scope: androidx.compose.foundation.lazy.LazyListScope,
    vm: MainViewModel,
    state: UiState,
    groups: List<Tricount>
) = with(scope) {
    val t = groups.firstOrNull { it.id == state.selectedId } ?: groups.firstOrNull()

    if (t == null) {
        item {
            Column(Modifier.padding(ScreenPadding)) {
                Text(
                    "Sin grupos normales",
                    style = MaterialTheme.typography.titleLarge,
                    color = SmartTheme.colors.primaryText
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Añade un grupo en la pestaña Grupos para ver en qué se va el dinero.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartTheme.colors.secondaryText
                )
            }
        }
        return@with
    }

    item {
        if (groups.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(groups, key = { it.id }) { g ->
                    PillChip("${g.emoji ?: ""} ${g.title}".trim(), g.id == t.id) { vm.select(g.id) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Hero(formatMoney(Stats.totalSpent(t), t.currency), "Total gastado en ${t.title}")
        Spacer(Modifier.height(4.dp))
        HeroCaption(
            "Tu parte ${formatMoney(Stats.myShare(t), t.currency)} · " +
                "media ${formatMoney(Stats.averagePerTransaction(t), t.currency)} por gasto"
        )
        Spacer(Modifier.height(20.dp))
    }

    item { BreakdownTabs(t) }
}

/** Las tres lecturas de un grupo: por categoría, por persona y por mes. */
@Composable
private fun BreakdownTabs(t: Tricount) {
    val c = SmartTheme.colors
    var tab by remember(t.id) { mutableStateOf(0) }
    val rows = remember(t, tab) {
        when (tab) {
            0 -> Stats.byCategory(t)
            1 -> Stats.byPayer(t)
            else -> Stats.byMonth(t).map { monthLabel(it.first) to it.second }
        }
    }
    val max = rows.maxOfOrNull { it.second } ?: 1.0

    Column {
        SegmentedTabs(listOf("Categoría", "Persona", "Mes"), tab) { tab = it }
        Spacer(Modifier.height(12.dp))
        if (rows.isEmpty()) {
            Text("Sin datos todavía", color = c.secondaryText, modifier = Modifier.padding(ScreenPadding))
        }
        rows.forEach { (label, value) ->
            AmountBarRow(label, formatMoney(value, t.currency), (value / max).toFloat())
        }
    }
}

// ---------------------------------------------------------------------------
// Grupos de ahorro: cuánto queda
// ---------------------------------------------------------------------------

private fun savingsStats(
    scope: androidx.compose.foundation.lazy.LazyListScope,
    vm: MainViewModel,
    groups: List<Tricount>
) = with(scope) {
    val total = vm.savingsTotal(groups)
    val currency = groups.map { it.currency }.distinct().singleOrNull()

    item {
        Hero(
            amount = if (currency == null) "—" else formatMoney(total.saved, currency, signed = true),
            label = if (currency == null) {
                "Los grupos usan monedas distintas: mira cada uno"
            } else {
                "Ahorrado entre todos los grupos"
            },
            amountColor = when {
                currency == null -> SmartTheme.colors.secondaryText
                total.saved < 0 -> SmartTheme.colors.negative
                else -> SmartTheme.colors.positive
            }
        )
        Spacer(Modifier.height(12.dp))
        if (currency != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Figure(
                    "Ingresos", formatMoney(total.income, currency),
                    SmartTheme.colors.positive, Modifier.weight(1f)
                )
                Figure(
                    "Gastos", formatMoney(total.spent, currency),
                    SmartTheme.colors.negative, Modifier.weight(1f)
                )
                Figure(
                    "Balance", formatMoney(total.saved, currency, signed = true),
                    if (total.saved < 0) SmartTheme.colors.negative else SmartTheme.colors.primaryText,
                    Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        SectionHeader("Balance por grupo")
    }

    val maxSaved = groups.maxOfOrNull { kotlin.math.abs(vm.savingsSummary(it).saved) } ?: 1.0
    items(groups, key = { "saved-${it.id}" }) { g ->
        val s = vm.savingsSummary(g)
        AmountBarRow(
            label = "${g.emoji ?: ""} ${g.title}".trim(),
            value = formatMoney(s.saved, g.currency, signed = true),
            fraction = if (maxSaved > 0) (kotlin.math.abs(s.saved) / maxSaved).toFloat() else 0f,
            valueColor = if (s.saved < 0) SmartTheme.colors.negative else SmartTheme.colors.primaryText
        )
    }

    // Lo que sale, mes a mes, sumando todos los grupos de ahorro: la serie que
    // dice si el ritmo de gasto sube o baja. Los ingresos quedan fuera a
    // propósito — una nómina en un mes concreto aplastaría la escala.
    val byMonth = groups
        .flatMap { g ->
            g.activeTransactions
                .filterNot { vm.isIncome(g, it) }
                .map { it.date.take(7) to it.amount.abs }
        }
        .groupBy({ it.first }, { it.second })
        .map { (month, values) -> month to values.sum() }
        .sortedBy { it.first }

    if (byMonth.isNotEmpty()) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("Gasto por mes")
        }
        val maxMonth = byMonth.maxOf { it.second }
        items(byMonth, key = { "month-${it.first}" }) { (month, value) ->
            AmountBarRow(
                label = monthLabel(month),
                value = formatMoney(value, currency ?: groups.first().currency),
                fraction = (value / maxMonth).toFloat()
            )
        }
    }
}

/** Etiqueta, importe y barra de proporción: la fila de toda la pestaña. */
@Composable
private fun AmountBarRow(
    label: String,
    value: String,
    fraction: Float,
    valueColor: androidx.compose.ui.graphics.Color? = null
) {
    val c = SmartTheme.colors
    Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = c.primaryText,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor ?: c.primaryText
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth()) { ProportionBar(fraction) }
    }
}
