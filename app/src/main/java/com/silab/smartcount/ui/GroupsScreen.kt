package com.silab.smartcount.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silab.smartcount.data.api.Category
import com.silab.smartcount.data.api.Transaction
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.repo.Stats
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme

// ===========================================================================
// Pestaña 1 · Grupos
// ===========================================================================

/**
 * Dos pantallas en una: la rejilla con todos los grupos y, al tocar uno, el
 * grupo abierto. Antes la lista de grupos era una fila de chips sobre el grupo
 * activo, así que con más de tres o cuatro grupos había que desplazar a ciegas
 * una tira horizontal para encontrar el que se busca. La rejilla los enseña
 * todos a la vez y con su cifra, que es lo que se venía a mirar.
 */
@Composable
fun GroupsScreen(vm: MainViewModel, state: UiState, modifier: Modifier = Modifier) {
    val c = SmartTheme.colors
    var showAddLink by remember { mutableStateOf(false) }

    // "+ Gasto" desde el widget abre el grupo activo directamente.
    val newExpenseSignal by vm.newExpenseRequests.collectAsStateWithLifecycle()
    LaunchedEffect(newExpenseSignal) {
        if (newExpenseSignal > 0 && state.selectedId != null) vm.openGroup(state.selectedId)
    }

    val open = state.tricounts.firstOrNull { it.id == state.openGroupId }

    if (open != null) {
        GroupDetail(vm, state, open, modifier) { vm.openGroup(null) }
        return
    }

    Box(modifier.fillMaxSize().background(c.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = ScreenPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Grupos",
                        style = MaterialTheme.typography.headlineLarge,
                        color = c.primaryText
                    )
                    SecondaryButton("Añadir") { showAddLink = true }
                }
            }

            if (state.tricounts.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    EmptyState(state.loading) { showAddLink = true }
                }
            }

            items(state.tricounts, key = { it.id }) { g ->
                GroupCard(
                    group = g,
                    savings = state.isSavings(g.id),
                    summary = if (state.isSavings(g.id)) vm.savingsSummary(g) else null
                ) { vm.openGroup(g.id) }
            }
        }

        AnimatedVisibility(
            visible = state.loading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(c.primaryText))
        }
    }

    if (showAddLink) {
        AddByLinkSheet(
            onDismiss = { showAddLink = false },
            onConfirm = { link -> vm.addByLink(link); showAddLink = false }
        )
    }
}

/**
 * La ficha de un grupo en la rejilla. Enseña la cifra que define al grupo —
 * lo que te deben, o lo ahorrado si es un grupo de ahorro — porque una rejilla
 * de nombres a secas obligaría a entrar en cada uno para saber cómo va.
 */
@Composable
private fun GroupCard(
    group: Tricount,
    savings: Boolean,
    summary: com.silab.smartcount.data.repo.SavingsSummary?,
    onClick: () -> Unit
) {
    val c = SmartTheme.colors
    val headline = if (savings && summary != null) {
        summary.saved
    } else {
        Stats.balanceOf(group, group.activeMembershipUuid)
    }
    val unknown = !savings && group.linkedMember == null
    val label = when {
        savings -> "ahorrado"
        unknown -> "falta saber quién eres"
        headline >= 0 -> "te deben"
        else -> "debes"
    }

    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.chipBackground)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.emoji ?: "•", style = MaterialTheme.typography.titleMedium)
            if (savings) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "AHORRO",
                    style = com.silab.smartcount.ui.theme.SectionTitle,
                    color = c.brand
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            group.title,
            style = MaterialTheme.typography.titleMedium,
            color = c.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (unknown) "—" else formatMoney(headline, group.currency, signed = true),
            style = MaterialTheme.typography.titleLarge,
            color = when {
                unknown -> c.secondaryText
                headline < 0 -> c.negative
                else -> c.positive
            },
            maxLines = 1
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.secondaryText, maxLines = 1)
    }
}

// ===========================================================================
// Un grupo abierto
// ===========================================================================

/**
 * El grupo abierto. Se usa desde la pestaña Grupos y desde la de Ahorro: es la
 * misma pantalla, y lo único que cambia es cómo se leen los movimientos.
 */
@Composable
fun GroupDetail(
    vm: MainViewModel,
    state: UiState,
    t: Tricount,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val c = SmartTheme.colors
    var innerTab by remember(t.id) { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Transaction?>(null) }
    var pickingMe by remember { mutableStateOf(false) }
    var pickingIncome by remember { mutableStateOf(false) }

    val isSavings = state.isSavings(t.id)
    val activeMembers = remember(t) { t.members.filter { it.status == "ACTIVE" } }
    val incomeMember = vm.incomeMember(t)
    val summary = remember(t, isSavings, incomeMember) { vm.savingsSummary(t) }

    val newExpenseSignal by vm.newExpenseRequests.collectAsStateWithLifecycle()
    LaunchedEffect(newExpenseSignal) {
        if (newExpenseSignal > 0) {
            creating = true
            vm.consumeNewExpense()
        }
    }

    Box(modifier.fillMaxSize().background(c.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {

            item {
                Row(
                    Modifier.fillMaxWidth().padding(ScreenPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "‹",
                            style = MaterialTheme.typography.headlineLarge,
                            color = c.secondaryText,
                            modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp)
                        )
                        Text(
                            "${t.emoji ?: ""} ${t.title}".trim(),
                            style = MaterialTheme.typography.titleLarge,
                            color = c.primaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    SecondaryButton(if (isSavings) "Ahorro ✓" else "Ahorro") {
                        vm.setSavings(t, !isSavings)
                    }
                }
            }

            item {
                if (isSavings) {
                    // En un grupo de ahorro no hay deudas que saldar: la cifra
                    // que importa es lo que queda después de gastar.
                    Hero(
                        amount = formatMoney(summary.saved, t.currency, signed = true),
                        label = "Balance de ${t.title}",
                        amountColor = if (summary.saved < 0) c.negative else c.positive
                    )
                    Spacer(Modifier.height(12.dp))
                    SavingsFigures(summary, t.currency)
                } else {
                    val myBalance = Stats.balanceOf(t, t.activeMembershipUuid)
                    Hero(
                        amount = if (t.linkedMember == null) "—" else {
                            formatMoney(myBalance, t.currency, signed = true)
                        },
                        label = when {
                            t.linkedMember == null -> "Aún no se sabe quién eres aquí"
                            myBalance >= 0 -> "Te deben en ${t.title}"
                            else -> "Debes en ${t.title}"
                        },
                        amountColor = when {
                            t.linkedMember == null -> c.secondaryText
                            myBalance < 0 -> c.negative
                            else -> c.positive
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    HeroCaption(
                        "Total del grupo ${formatMoney(Stats.totalSpent(t), t.currency)} · " +
                            "${t.activeTransactions.size} movimientos"
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // Quién eres tú aquí. Se pregunta en vez de enseñar un 0,00 que no
            // significa nada: la API no lo dice en los grupos a los que esta
            // instalación se unió por enlace, que son casi todos.
            item {
                IdentityRow(
                    t = t,
                    savings = isSavings,
                    incomeMember = incomeMember,
                    onPickMe = { pickingMe = true },
                    onPickIncome = { pickingIncome = true }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                if (!isSavings) {
                    SegmentedTabs(listOf("Movimientos", "Balance"), innerTab) { innerTab = it }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (isSavings || innerTab == 0) {
                val txs = t.activeTransactions.sortedByDescending { it.date }
                if (txs.isEmpty()) {
                    item {
                        Text(
                            "Aún no hay movimientos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.secondaryText,
                            modifier = Modifier.padding(ScreenPadding)
                        )
                    }
                }
                items(txs, key = { it.uuid.ifBlank { it.id.toString() } }) { tx ->
                    TransactionRow(
                        tx = tx,
                        t = t,
                        income = if (isSavings) vm.isIncome(t, tx) else null,
                        onClick = { editing = tx },
                        onLongClick = { confirmDelete = tx }
                    )
                    SmartDivider()
                }
            } else {
                item { BalanceSection(t) }
            }
        }

        if (!t.isArchived) {
            // Degradado bajo el botón: las filas no chocan con él al hacer scroll.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(c.background.copy(alpha = 0f), c.background, c.background)
                        )
                    )
                    .padding(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 16.dp)
            ) {
                PrimaryButton(if (isSavings) "Añadir movimiento" else "Añadir gasto") { creating = true }
            }
        }

        AnimatedVisibility(
            visible = state.loading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(c.primaryText))
        }
    }

    if (creating) {
        ExpenseSheet(
            t, null,
            onDismiss = { creating = false },
            savings = isSavings,
            incomeMember = incomeMember
        ) { draft ->
            vm.addMovement(t, draft); creating = false
        }
    }

    editing?.let { tx ->
        ExpenseSheet(
            t, tx,
            onDismiss = { editing = null },
            onDelete = { confirmDelete = tx; editing = null },
            savings = isSavings,
            incomeMember = incomeMember
        ) { draft ->
            vm.editExpense(
                t, tx, draft.description, draft.amount, draft.owner,
                draft.splitAmong, draft.category
            )
            editing = null
        }
    }

    confirmDelete?.let { tx ->
        ConfirmSheet(
            title = "Eliminar «${tx.description}»",
            body = "Se borra también en Tricount para todo el grupo. No se puede deshacer.",
            confirmLabel = "Eliminar",
            onDismiss = { confirmDelete = null },
            onConfirm = { vm.deleteExpense(t, tx); confirmDelete = null }
        )
    }

    if (pickingMe) {
        MemberPickerSheet(
            title = "¿Quién eres tú en este grupo?",
            body = "Tricount no siempre dice a qué miembro corresponde esta instalación, " +
                "y sin saberlo no se puede calcular tu balance. Se guarda solo en este móvil.",
            members = activeMembers,
            selected = t.linkedMember,
            onDismiss = { pickingMe = false },
            onPick = { vm.setMyMember(t, it); pickingMe = false }
        )
    }

    if (pickingIncome) {
        MemberPickerSheet(
            title = "¿De dónde vienen los ingresos?",
            body = "Lo que cree este miembro cuenta como dinero que entra; todo lo demás, " +
                "como dinero que sale.",
            members = activeMembers,
            selected = incomeMember,
            onDismiss = { pickingIncome = false },
            onPick = { vm.setIncomeMember(t, it); pickingIncome = false }
        )
    }
}

/** Ingresos, gastos y balance del grupo de ahorro, en la cabecera. */
@Composable
private fun SavingsFigures(
    summary: com.silab.smartcount.data.repo.SavingsSummary,
    currency: String,
    modifier: Modifier = Modifier
) {
    val c = SmartTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Figure("Ingresos", formatMoney(summary.income, currency), c.positive, Modifier.weight(1f))
        Figure("Gastos", formatMoney(summary.spent, currency), c.negative, Modifier.weight(1f))
        Figure(
            "Balance",
            formatMoney(summary.saved, currency, signed = true),
            if (summary.saved < 0) c.negative else c.primaryText,
            Modifier.weight(1f)
        )
    }
}

@Composable
internal fun Figure(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val c = SmartTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.chipBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.secondaryText, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Quién eres tú, y de dónde viene el dinero si el grupo es de ahorro. */
@Composable
private fun IdentityRow(
    t: Tricount,
    savings: Boolean,
    incomeMember: com.silab.smartcount.data.api.Member?,
    onPickMe: () -> Unit,
    onPickIncome: () -> Unit
) {
    val c = SmartTheme.colors
    val me = t.linkedMember
    Column {
        SmartRow(
            title = me?.displayName ?: "Elegir quién eres",
            subtitle = if (me == null) {
                "Sin esto no hay balance que enseñar"
            } else {
                "Tú, en este grupo"
            },
            value = "Cambiar",
            valueColor = if (me == null) c.brand else c.secondaryText,
            leading = { Initials(me?.displayName ?: "?") },
            onClick = onPickMe
        )
        SmartDivider()
        if (savings) {
            SmartRow(
                title = incomeMember?.displayName ?: "Elegir la fuente de ingresos",
                subtitle = "De aquí vienen los ingresos del grupo",
                value = "Cambiar",
                valueColor = if (incomeMember == null) c.brand else c.secondaryText,
                leading = { Initials(incomeMember?.displayName ?: "?") },
                onClick = onPickIncome
            )
            SmartDivider()
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, onAdd: () -> Unit) {
    val c = SmartTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(48.dp))
        BrandMark(size = 56)
        Spacer(Modifier.height(24.dp))
        Text(
            if (loading) "Cargando…" else "Sin grupos todavía",
            style = MaterialTheme.typography.titleLarge,
            color = c.primaryText
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Abre Tricount, copia el enlace para compartir de un grupo y pégalo aquí. " +
                "SmartCount se une al grupo y a partir de ahí puedes crear, editar y borrar gastos.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.secondaryText
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Pegar enlace", onClick = onAdd)
    }
}

/**
 * Una fila de movimiento. En un grupo de ahorro el color dice de qué lado
 * viene el dinero — verde lo que entra, rojo lo que sale — y en un grupo
 * normal se queda en negro: allí un gasto no es una mala noticia, es el
 * material del que está hecho el grupo, y pintarlo todo de rojo no informaría
 * de nada.
 */
@Composable
private fun TransactionRow(
    tx: Transaction,
    t: Tricount,
    income: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val c = SmartTheme.colors
    val cat = Category.fromApi(tx.category)
    val payer = t.memberByUuid(tx.ownerUuid)?.displayName ?: "?"
    val amount = tx.amount.abs
    SmartRow(
        title = tx.description.ifBlank { "Sin descripción" },
        subtitle = "$payer · ${formatTxDate(tx.date)}",
        value = when (income) {
            null -> formatMoney(amount, t.currency)
            true -> formatMoney(amount, t.currency, signed = true)
            false -> formatMoney(-amount, t.currency, signed = true)
        },
        valueColor = when (income) {
            null -> null
            true -> c.positive
            false -> c.negative
        },
        leading = {
            Box(
                Modifier
                    .width(38.dp).height(38.dp)
                    .clip(RoundedCornerShape(100))
                    .background(c.chipBackground),
                contentAlignment = Alignment.Center
            ) { Text(tx.categoryCustom?.takeLast(2) ?: cat?.emoji ?: "•") }
        },
        onClick = onClick
    )
}

@Composable
private fun BalanceSection(t: Tricount) {
    val c = SmartTheme.colors
    val balances = remember(t) { Stats.balances(t) }
    val plan = remember(t) { Stats.settlementPlan(t) }

    Column {
        SectionHeader("Saldo por persona")
        balances.entries.sortedByDescending { it.value }.forEach { (name, bal) ->
            SmartRow(
                title = name,
                subtitle = when {
                    bal > 0.005 -> "Le deben"
                    bal < -0.005 -> "Debe"
                    else -> "En paz"
                },
                value = formatMoney(bal, t.currency, signed = true),
                valueColor = when {
                    bal > 0.005 -> c.positive
                    bal < -0.005 -> c.negative
                    else -> c.secondaryText
                },
                leading = { Initials(name) }
            )
            SmartDivider()
        }

        if (plan.isNotEmpty()) {
            SectionHeader("Para saldar cuentas")
            plan.forEach { leg ->
                SmartRow(
                    title = "${leg.fromName} → ${leg.toName}",
                    subtitle = "Un solo pago",
                    value = formatMoney(leg.amount, t.currency),
                    leading = { Initials(leg.fromName) }
                )
                SmartDivider()
            }
        }
    }
}
