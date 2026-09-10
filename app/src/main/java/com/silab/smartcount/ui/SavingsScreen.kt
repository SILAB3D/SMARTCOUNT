package com.silab.smartcount.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.repo.SavingsSummary
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme

// ===========================================================================
// Pestaña 2 · Ahorro
// ===========================================================================

/**
 * Los grupos de ahorro juntos: cuánto ha entrado, cuánto ha salido y qué
 * queda, primero de todos a la vez y luego grupo a grupo.
 *
 * Tienen pestaña propia porque no se leen como los demás. En un grupo normal
 * la pregunta es *quién debe a quién*; aquí no hay deudas — hay un saldo que
 * sube y baja — y mezclarlos en la misma lista obligaba a cambiar de idea en
 * cada fila. Y sumarlos entre sí solo tiene sentido aquí: el ahorro total es
 * una cifra real, mientras que sumar los balances de tres grupos distintos no
 * significa nada.
 */
@Composable
fun SavingsScreen(vm: MainViewModel, state: UiState, modifier: Modifier = Modifier) {
    val c = SmartTheme.colors
    val groups = state.savingsGroups

    val open = groups.firstOrNull { it.id == state.openSavingsId }
    if (open != null) {
        GroupDetail(vm, state, open, modifier) { vm.openSavings(null) }
        return
    }

    val total = vm.savingsTotal(groups)
    // Los grupos de ahorro pueden estar en monedas distintas; sumarlos a ciegas
    // daría una cifra falsa, así que el total solo se enseña cuando comparten
    // moneda, y si no se dice por qué falta.
    val currencies = groups.map { it.currency }.distinct()
    val currency = currencies.singleOrNull()

    LazyColumn(
        modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { ScreenTitle("Ahorro") }

        if (groups.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    Text(
                        "Ningún grupo de ahorro",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.primaryText
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Un grupo de ahorro es un grupo normal leído de otra manera: lo que " +
                            "entra desde la fuente de ingresos son ingresos, lo demás son " +
                            "gastos, y el balance es la resta. Abre un grupo y toca «Ahorro» " +
                            "para convertirlo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.secondaryText
                    )
                }
            }
            return@LazyColumn
        }

        item {
            Hero(
                amount = if (currency == null) "—" else {
                    formatMoney(total.saved, currency, signed = true)
                },
                label = if (currency == null) {
                    "Los grupos usan monedas distintas: mira cada uno"
                } else if (groups.size == 1) {
                    "Balance de tu grupo de ahorro"
                } else {
                    "Balance de tus ${groups.size} grupos de ahorro"
                },
                amountColor = when {
                    currency == null -> c.secondaryText
                    total.saved < 0 -> c.negative
                    else -> c.positive
                }
            )
            Spacer(Modifier.height(12.dp))
            if (currency != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Figure("Ingresos", formatMoney(total.income, currency), c.positive, Modifier.weight(1f))
                    Figure("Gastos", formatMoney(total.spent, currency), c.negative, Modifier.weight(1f))
                    Figure(
                        "Balance",
                        formatMoney(total.saved, currency, signed = true),
                        if (total.saved < 0) c.negative else c.primaryText,
                        Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            SectionHeader("Por grupo")
        }

        items(groups, key = { it.id }) { g ->
            SavingsGroupCard(g, vm.savingsSummary(g)) { vm.openSavings(g.id) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * Una ficha por grupo, en lista: nombre arriba y las tres cifras debajo, con
 * el mismo reparto que la cabecera para que la vista de conjunto y la de
 * detalle se lean igual.
 */
@Composable
private fun SavingsGroupCard(
    group: Tricount,
    summary: SavingsSummary,
    onClick: () -> Unit
) {
    val c = SmartTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .clip(RoundedCornerShape(20.dp))
            .background(c.chipBackground)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${group.emoji ?: ""} ${group.title}".trim(),
                style = MaterialTheme.typography.titleMedium,
                color = c.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${group.activeTransactions.size} mov.",
                style = MaterialTheme.typography.bodySmall,
                color = c.secondaryText
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Ingresos", style = MaterialTheme.typography.bodySmall, color = c.secondaryText)
                Text(
                    formatMoney(summary.income, group.currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.positive,
                    maxLines = 1
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Gastos", style = MaterialTheme.typography.bodySmall, color = c.secondaryText)
                Text(
                    formatMoney(summary.spent, group.currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.negative,
                    maxLines = 1
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Balance", style = MaterialTheme.typography.bodySmall, color = c.secondaryText)
                Text(
                    formatMoney(summary.saved, group.currency, signed = true),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (summary.saved < 0) c.negative else c.primaryText,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth()) {
            // Cuánto de lo ingresado sigue ahí: la barra dice de un vistazo si
            // el grupo va sobrado o justo, que es lo que tres cifras sueltas no
            // llegan a decir.
            ProportionBar(
                if (summary.income > 0) (summary.saved / summary.income).toFloat() else 0f
            )
        }
    }
}
