package com.silab.smartcount.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.repo.Stats
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme
import kotlin.math.abs

// ===========================================================================
// Pestaña 3 · Estadísticas
// ===========================================================================

/**
 * Dos estadísticas distintas bajo el mismo nombre, porque las preguntas no se
 * parecen: de un grupo normal se quiere saber **en qué se va el dinero**, y de
 * los de ahorro **cuánto queda y desde cuándo**. Mezclarlas producía medias
 * sin sentido — el "total gastado" de un grupo de ahorro incluía la nómina.
 *
 * En las dos se puede mirar el gasto **del grupo entero o solo la parte
 * tuya**, y acotarlo a un mes o a un año. Antes solo existía la cifra del
 * grupo y una sola línea con tu total, sin desglosar: no había forma de ver
 * en qué se te iba a ti el dinero.
 */
@Composable
fun StatsScreen(vm: MainViewModel, state: UiState, modifier: Modifier = Modifier) {
    val c = SmartTheme.colors
    val normal = state.normalGroups
    val savings = state.savingsGroups
    var scope by rememberSaveable { mutableStateOf(0) }
    var mine by rememberSaveable { mutableStateOf(false) }
    var period by rememberSaveable { mutableStateOf(Stats.ALL_TIME) }

    // Sin grupos de ahorro no hay nada que separar: se enseña la de siempre.
    val showScopes = savings.isNotEmpty()
    val effectiveScope = if (showScopes) scope else 0
    val groups = if (effectiveScope == 0) normal else savings

    LazyColumn(
        modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { ScreenTitle("Estadísticas") }

        if (showScopes) {
            item {
                SegmentedTabs(listOf("Grupos", "Ahorro"), effectiveScope) { scope = it }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (groups.isEmpty()) {
            item {
                Column(Modifier.padding(ScreenPadding)) {
                    Text(
                        if (effectiveScope == 0) "Sin grupos normales" else "Sin grupos de ahorro",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.primaryText
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Añade un grupo en la pestaña Grupos para ver en qué se va el dinero.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.secondaryText
                    )
                }
            }
            return@LazyColumn
        }

        // Los filtros, en una tira antes de las cifras: qué grupo, qué tramo
        // de tiempo y si se mira el grupo entero o solo tu parte.
        item {
            val periods = remember(groups) { Stats.periodsOf(groups) }
            GroupFilter(vm, groups, state.selectedId)
            Spacer(Modifier.height(8.dp))
            PeriodFilter(periods, period) { period = it }
            Spacer(Modifier.height(8.dp))
            SegmentedTabs(listOf("Todo el grupo", "Mi parte"), if (mine) 1 else 0) { mine = it == 1 }
            Spacer(Modifier.height(16.dp))
        }

        if (effectiveScope == 0) {
            normalStats(this, vm, state, normal, mine, period)
        } else {
            savingsStats(this, vm, savings, mine, period)
        }
    }
}

@Composable
private fun GroupFilter(vm: MainViewModel, groups: List<Tricount>, selectedId: Int?) {
    if (groups.size <= 1) return
    val current = groups.firstOrNull { it.id == selectedId } ?: groups.first()
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(groups, key = { it.id }) { g ->
            PillChip("${g.emoji ?: ""} ${g.title}".trim(), g.id == current.id) { vm.select(g.id) }
        }
    }
}

@Composable
private fun PeriodFilter(periods: List<String>, selected: String, onSelect: (String) -> Unit) {
    if (periods.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { PillChip("Todo", selected == Stats.ALL_TIME) { onSelect(Stats.ALL_TIME) } }
        items(periods, key = { it }) { p ->
            PillChip(
                if (p.length == 4) p else monthLabel(p),
                selected == p
            ) { onSelect(p) }
        }
    }
}

// ---------------------------------------------------------------------------
// Grupos normales: en qué se va el dinero
// ---------------------------------------------------------------------------

private fun normalStats(
    scope: LazyListScope,
    vm: MainViewModel,
    state: UiState,
    groups: List<Tricount>,
    mine: Boolean,
    period: String
) = with(scope) {
    val t = groups.firstOrNull { it.id == state.selectedId } ?: groups.first()
    val mineUuid = if (mine) t.activeMembershipUuid else null

    item {
        val total = Stats.totalSpent(t, mineUuid = mineUuid, period = period)
        Hero(
            formatMoney(total, t.currency),
            if (mine) "Tu parte en ${t.title}" else "Total gastado en ${t.title}"
        )
        Spacer(Modifier.height(4.dp))
        HeroCaption(
            if (mine && t.activeMembershipUuid == null) {
                "Falta saber quién eres en este grupo"
            } else {
                "media ${formatMoney(Stats.averagePerTransaction(t, period = period), t.currency)} por gasto"
            }
        )
        Spacer(Modifier.height(20.dp))
        BreakdownSection(t, incomeUuid = null, mineUuid = mineUuid, period = period)
    }
}

// ---------------------------------------------------------------------------
// Grupos de ahorro: cuánto queda y en qué se fue
// ---------------------------------------------------------------------------

private fun savingsStats(
    scope: LazyListScope,
    vm: MainViewModel,
    groups: List<Tricount>,
    mine: Boolean,
    period: String
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
            FigureRow(
                listOf(
                    Triple("Ingresos", formatMoney(total.income, currency), SmartTheme.colors.positive),
                    Triple("Gastos", formatMoney(total.spent, currency), SmartTheme.colors.negative),
                    Triple(
                        "Balance",
                        formatMoney(total.saved, currency, signed = true),
                        if (total.saved < 0) SmartTheme.colors.negative else SmartTheme.colors.primaryText
                    )
                )
            )
        }
        Spacer(Modifier.height(20.dp))
        SectionHeader("Balance por grupo")
    }

    val maxSaved = groups.maxOfOrNull { abs(vm.savingsSummary(it).saved) } ?: 1.0
    items(groups, key = { "saved-${it.id}" }) { g ->
        val s = vm.savingsSummary(g)
        AmountBarRow(
            label = "${g.emoji ?: ""} ${g.title}".trim(),
            value = formatMoney(s.saved, g.currency, signed = true),
            fraction = if (maxSaved > 0) (abs(s.saved) / maxSaved).toFloat() else 0f,
            valueColor = if (s.saved < 0) SmartTheme.colors.negative else SmartTheme.colors.primaryText
        )
    }

    // En qué se va lo que sale del grupo de ahorro. Los ingresos quedan fuera:
    // aquí la pregunta es en qué se gasta, no cuánto entró.
    item {
        val g = groups.first()
        Spacer(Modifier.height(12.dp))
        BreakdownSection(
            t = g,
            incomeUuid = vm.incomeMember(g)?.uuid,
            mineUuid = if (mine) g.activeMembershipUuid else null,
            period = period
        )
    }
}

// ---------------------------------------------------------------------------
// El desglose: categoría (con su gráfico), persona y mes
// ---------------------------------------------------------------------------

@Composable
private fun BreakdownSection(
    t: Tricount,
    incomeUuid: String?,
    mineUuid: String?,
    period: String
) {
    val c = SmartTheme.colors
    var tab by rememberSaveable(t.id) { mutableStateOf(0) }

    val rows = remember(t, tab, incomeUuid, mineUuid, period) {
        when (tab) {
            0 -> Stats.byCategory(t, incomeUuid, mineUuid, period)
            1 -> Stats.byPayer(t, incomeUuid, period)
            else -> Stats.byMonth(t, incomeUuid, mineUuid, period).map { monthLabel(it.first) to it.second }
        }
    }
    val max = rows.maxOfOrNull { it.second } ?: 1.0

    Column {
        SegmentedTabs(listOf("Categoría", "Persona", "Mes"), tab) { tab = it }
        Spacer(Modifier.height(12.dp))

        if (rows.isEmpty()) {
            Text(
                "Sin datos en este tramo",
                color = c.secondaryText,
                modifier = Modifier.padding(ScreenPadding)
            )
            return@Column
        }

        if (tab == 0) {
            CategoryDonut(rows, t.currency)
        } else {
            rows.forEach { (label, value) ->
                AmountBarRow(label, formatMoney(value, t.currency), (value / max).toFloat())
            }
        }
    }
}

/** Cuántas porciones se dibujan antes de juntar el resto en «Otros». */
private const val DONUT_SLICES = 5

/**
 * La distribución por categoría, en anillo.
 *
 * El anillo contesta de un vistazo "¿en qué se me va?"; comparar dos
 * categorías parecidas por el ángulo es difícil, así que debajo va la lista
 * con su cifra y su barra, que es donde se compara de verdad. El color
 * identifica, y nunca va solo: cada porción tiene su nombre y su importe en la
 * leyenda.
 *
 * A partir de la sexta categoría se funden en «Otros». Un anillo de doce
 * porciones no se lee, y repetir colores para las de más abajo haría que dos
 * categorías distintas se pintaran igual.
 */
@Composable
private fun CategoryDonut(rows: List<Pair<String, Double>>, currency: String) {
    val c = SmartTheme.colors
    val palette = c.chartSeries

    val slices = remember(rows) {
        if (rows.size <= DONUT_SLICES + 1) {
            rows
        } else {
            val head = rows.take(DONUT_SLICES)
            head + ("Otros" to rows.drop(DONUT_SLICES).sumOf { it.second })
        }
    }
    val total = slices.sumOf { it.second }
    if (total <= 0.0) return

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(180.dp)) {
                val thickness = 26.dp.toPx()
                val inset = thickness / 2
                val diameter = size.minDimension - thickness
                // Un hueco del grosor de dos píxeles entre porciones: separa
                // sin que parezca que falta un trozo del anillo.
                val gapDegrees = 1.6f
                var start = -90f
                slices.forEachIndexed { i, (_, value) ->
                    val sweep = (value / total * 360.0).toFloat()
                    val drawn = (sweep - gapDegrees).coerceAtLeast(0.6f)
                    drawArc(
                        color = palette[i % palette.size],
                        startAngle = start + gapDegrees / 2,
                        sweepAngle = drawn,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(diameter, diameter),
                        style = Stroke(width = thickness)
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatMoney(total, currency),
                    style = MaterialTheme.typography.titleLarge,
                    color = c.primaryText
                )
                Text(
                    if (slices.size == 1) "1 categoría" else "${slices.size} categorías",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        slices.forEachIndexed { i, (label, value) ->
            LegendRow(
                color = palette[i % palette.size],
                label = label,
                value = formatMoney(value, currency),
                share = value / total
            )
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: String, share: Double) {
    val c = SmartTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = c.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${(share * 100).toInt()} %",
            style = MaterialTheme.typography.bodySmall,
            color = c.secondaryText
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = c.primaryText)
    }
}

/** Etiqueta, importe y barra de proporción: la fila de persona y mes. */
@Composable
private fun AmountBarRow(
    label: String,
    value: String,
    fraction: Float,
    valueColor: Color? = null
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
