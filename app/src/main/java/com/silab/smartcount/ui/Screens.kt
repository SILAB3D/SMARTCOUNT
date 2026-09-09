package com.silab.smartcount.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silab.smartcount.SmartCountApp
import com.silab.smartcount.data.api.Category
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.Transaction
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.api.TxType
import com.silab.smartcount.data.db.Confidence
import com.silab.smartcount.data.db.InboxEntry
import com.silab.smartcount.data.repo.Savings
import com.silab.smartcount.data.repo.Stats
import com.silab.smartcount.notif.BankNotificationListener
import com.silab.smartcount.notif.MovementPolicy
import com.silab.smartcount.update.UpdatePhase
import com.silab.smartcount.update.UpdateViewModel
import com.silab.smartcount.ui.theme.DisplayNumber
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===========================================================================
// Piezas comunes
// ===========================================================================

@Composable
private fun ScreenTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(ScreenPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.headlineLarge, color = SmartTheme.colors.primaryText)
        trailing?.invoke()
    }
}

/** Cifra grande con su etiqueta debajo: el "hero" de cada pestaña en TR. */
@Composable
private fun Hero(amount: String, label: String, amountColor: Color? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp)) {
        Text(amount, style = DisplayNumber, color = amountColor ?: SmartTheme.colors.primaryText)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SmartTheme.colors.secondaryText)
    }
}

/** Pestañas internas tipo segmento (Movimientos / Balance). */
@Composable
private fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options.size) { i ->
            PillChip(options[i], selected == i) { onSelect(i) }
        }
    }
}

@Composable
private fun SmartField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text
) {
    val c = SmartTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = c.secondaryText) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.chipBackground,
            unfocusedContainerColor = c.chipBackground,
            focusedBorderColor = c.brand,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = c.primaryText,
            unfocusedTextColor = c.primaryText,
            cursorColor = c.brand
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

// ===========================================================================
// Pestaña 1 · Grupos
// ===========================================================================

@Composable
fun GroupsScreen(vm: MainViewModel, state: UiState, modifier: Modifier = Modifier) {
    val c = SmartTheme.colors
    var innerTab by remember { mutableStateOf(0) }
    var showAddLink by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Transaction?>(null) }

    val t = state.selected

    // "+ Gasto" desde el widget abre directamente la hoja de nuevo gasto.
    val newExpenseSignal by vm.newExpenseRequests.collectAsStateWithLifecycle()
    LaunchedEffect(newExpenseSignal) {
        if (newExpenseSignal > 0 && t != null) creating = true
    }

    Box(modifier.fillMaxSize().background(c.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {

            item {
                ScreenTitle("Grupos") {
                    SecondaryButton("Añadir") { showAddLink = true }
                }
            }

            if (state.tricounts.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScreenPadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.tricounts, key = { it.id }) { g ->
                            PillChip("${g.emoji ?: ""} ${g.title}".trim(), g.id == state.selectedId) {
                                vm.select(g.id)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (t == null) {
                item { EmptyState(state.loading) { showAddLink = true } }
            } else {
                val isSavings = state.isSavings(t.id)
                val myBalance = t.linkedMember?.let { Stats.balances(t)[it.displayName] } ?: 0.0
                item {
                    if (isSavings) {
                        // En un grupo de ahorro no hay deudas que saldar: la cifra
                        // que importa es lo que queda después de gastar.
                        val summary = Savings.summary(t)
                        Hero(
                            amount = formatMoney(summary.saved, t.currency, signed = true),
                            label = "Ahorrado en ${t.title}",
                            amountColor = if (summary.saved < 0) c.negative else c.positive
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatMoney(summary.income, t.currency)} ingresado · " +
                                "${formatMoney(summary.spent, t.currency)} gastado",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.secondaryText,
                            modifier = Modifier.padding(horizontal = ScreenPadding)
                        )
                    } else {
                        Hero(
                            amount = formatMoney(myBalance, t.currency, signed = true),
                            label = if (myBalance >= 0) "Te deben en ${t.title}" else "Debes en ${t.title}",
                            amountColor = if (myBalance < 0) c.negative else c.positive
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Total del grupo ${formatMoney(Stats.totalSpent(t), t.currency)} · " +
                                "${t.activeTransactions.size} movimientos",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.secondaryText,
                            modifier = Modifier.padding(horizontal = ScreenPadding)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
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
                        TransactionRow(tx, t, onClick = { editing = tx }, onLongClick = { confirmDelete = tx })
                        SmartDivider()
                    }
                } else {
                    item { BalanceSection(t) }
                }
            }
        }

        if (t != null && !t.isArchived) {
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
                PrimaryButton(
                    if (state.isSavings(t.id)) "Añadir movimiento" else "Añadir gasto"
                ) { creating = true }
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

    if (creating && t != null) {
        ExpenseSheet(
            t, null,
            onDismiss = { creating = false },
            savings = state.isSavings(t.id)
        ) { draft ->
            vm.addMovement(t, draft); creating = false
        }
    }

    editing?.let { tx ->
        if (t != null) {
            ExpenseSheet(
                t, tx,
                onDismiss = { editing = null },
                onDelete = { confirmDelete = tx; editing = null },
                savings = state.isSavings(t.id)
            ) { draft ->
                vm.editExpense(
                    t, tx, draft.description, draft.amount, draft.owner,
                    draft.splitAmong, draft.category
                )
                editing = null
            }
        }
    }

    confirmDelete?.let { tx ->
        ConfirmSheet(
            title = "Eliminar «${tx.description}»",
            body = "Se borra también en Tricount para todo el grupo. No se puede deshacer.",
            confirmLabel = "Eliminar",
            onDismiss = { confirmDelete = null },
            onConfirm = { t?.let { vm.deleteExpense(it, tx) }; confirmDelete = null }
        )
    }
}

@Composable
private fun EmptyState(loading: Boolean, onAdd: () -> Unit) {
    val c = SmartTheme.colors
    Column(Modifier.fillMaxWidth().padding(ScreenPadding)) {
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

@Composable
private fun TransactionRow(
    tx: Transaction,
    t: Tricount,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val c = SmartTheme.colors
    val cat = Category.fromApi(tx.category)
    val payer = t.memberByUuid(tx.ownerUuid)?.displayName ?: "?"
    SmartRow(
        title = tx.description.ifBlank { "Sin descripción" },
        subtitle = "$payer · ${formatTxDate(tx.date)}",
        value = formatMoney(tx.amount.abs, t.currency),
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

// ===========================================================================
// Hoja: crear / editar gasto
// ===========================================================================

/**
 * Lo que la hoja devuelve al guardar. Un solo objeto en vez de seis parámetros
 * sueltos, porque cada tipo de movimiento usa unos campos y no otros.
 */
data class MovementDraft(
    val kind: TxType,
    val description: String,
    val amount: Double,
    /** Gasto: quien paga. Ingreso: quien recibe. Transferencia: quien envía. */
    val owner: Member,
    /** Solo en las transferencias: la otra punta. */
    val counterpart: Member? = null,
    val splitAmong: List<Member> = emptyList(),
    val category: Category? = null
)

private val TxType.label: String
    get() = when (this) {
        TxType.NORMAL -> "Gasto"
        TxType.INCOME -> "Ingreso"
        TxType.BALANCE -> "Transferencia"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseSheet(
    t: Tricount,
    existing: Transaction?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    prefillAmount: Double? = null,
    prefillDescription: String? = null,
    /** En un grupo de ahorro los papeles están fijados y sobran los selectores. */
    savings: Boolean = false,
    onSave: (MovementDraft) -> Unit
) {
    val c = SmartTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeMembers = remember(t) { t.members.filter { it.status == "ACTIVE" } }
    val me = remember(t) { t.linkedMember ?: activeMembers.firstOrNull() }
    val incomeMember = remember(t) { Savings.incomeMember(t) }

    // Al editar, el tipo no se toca: la API edita cada uno por su camino.
    var kind by remember { mutableStateOf(existing?.type ?: TxType.NORMAL) }
    val kindLocked = existing != null

    var description by remember { mutableStateOf(existing?.description ?: prefillDescription.orEmpty()) }
    var amountText by remember {
        mutableStateOf(
            (existing?.amount?.abs ?: prefillAmount)?.let { String.format(Locale.US, "%.2f", it) } ?: ""
        )
    }
    var owner by remember {
        mutableStateOf(t.memberByUuid(existing?.ownerUuid) ?: me)
    }
    var counterpart by remember {
        mutableStateOf(
            existing?.allocations
                ?.firstOrNull { it.membershipUuid != existing.ownerUuid }
                ?.let { t.memberByUuid(it.membershipUuid) }
                ?: activeMembers.firstOrNull { it.uuid != me?.uuid }
        )
    }
    var split by remember {
        mutableStateOf(
            existing?.allocations?.mapNotNull { t.memberByUuid(it.membershipUuid) }?.toSet()
                ?: activeMembers.toSet()
        )
    }
    var category by remember { mutableStateOf(Category.fromApi(existing?.category)) }

    // En un grupo de ahorro los papeles son fijos: tú gastas, «Ingresos» ingresa.
    val effectiveOwner = when {
        !savings -> owner
        kind == TxType.INCOME -> incomeMember ?: owner
        else -> me ?: owner
    }
    val effectiveSplit = when {
        savings -> listOfNotNull(me)
        kind == TxType.BALANCE -> listOfNotNull(counterpart)
        else -> split.toList()
    }

    val amount = amountText.replace(',', '.').toDoubleOrNull()
    val valid = description.isNotBlank() && amount != null && amount > 0 &&
        effectiveOwner != null &&
        when (kind) {
            TxType.BALANCE -> counterpart != null && counterpart?.uuid != effectiveOwner.uuid
            else -> effectiveSplit.isNotEmpty()
        }

    val title = when {
        existing != null -> "Editar " + kind.label.lowercase()
        kind == TxType.BALANCE -> "Nueva transferencia"
        else -> "Nuevo " + kind.label.lowercase()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.background,
        dragHandle = null
    ) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(ScreenPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = c.primaryText
                    )
                    Text(
                        "Cancelar",
                        color = c.secondaryText,
                        modifier = Modifier.clickable(onClick = onDismiss)
                    )
                }
            }

            if (!kindLocked) {
                item {
                    val kinds = if (savings) {
                        listOf(TxType.NORMAL, TxType.INCOME)
                    } else {
                        listOf(TxType.NORMAL, TxType.INCOME, TxType.BALANCE)
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScreenPadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(kinds, key = { it.name }) { option ->
                            PillChip(option.label, option == kind) { kind = option }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            item {
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    SmartField(amountText, { amountText = it }, "Importe (" + t.currency + ")", KeyboardType.Decimal)
                    Spacer(Modifier.height(12.dp))
                    SmartField(description, { description = it }, "Descripción")
                    Spacer(Modifier.height(4.dp))
                    if (amount != null && kind != TxType.BALANCE && effectiveSplit.size > 1) {
                        Text(
                            formatMoney(amount / effectiveSplit.size, t.currency) +
                                " por persona · " + effectiveSplit.size + " personas",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.secondaryText,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            if (savings) {
                item {
                    Text(
                        if (kind == TxType.INCOME) {
                            "Entra al grupo desde «" + Savings.INCOME_MEMBER + "»."
                        } else {
                            "Sale del grupo. Se descuenta de lo ahorrado."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.secondaryText,
                        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)
                    )
                }
            } else {
                item {
                    SectionHeader(
                        when (kind) {
                            TxType.NORMAL -> "Pagado por"
                            TxType.INCOME -> "Recibido por"
                            TxType.BALANCE -> "De"
                        }
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScreenPadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(activeMembers, key = { it.uuid }) { m ->
                            PillChip(m.displayName, owner?.uuid == m.uuid) { owner = m }
                        }
                    }
                }

                if (kind == TxType.BALANCE) {
                    item {
                        SectionHeader("A")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = ScreenPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(activeMembers, key = { it.uuid }) { m ->
                                PillChip(m.displayName, counterpart?.uuid == m.uuid) { counterpart = m }
                            }
                        }
                        if (counterpart != null && counterpart?.uuid == owner?.uuid) {
                            Text(
                                "Elige dos personas distintas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.negative,
                                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)
                            )
                        }
                    }
                } else {
                    item {
                        SectionHeader("Repartido entre") {
                            Text(
                                if (split.size == activeMembers.size) "Quitar todos" else "Seleccionar todos",
                                color = c.secondaryText,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable {
                                    split = if (split.size == activeMembers.size) {
                                        emptySet()
                                    } else {
                                        activeMembers.toSet()
                                    }
                                }
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = ScreenPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(activeMembers, key = { it.uuid }) { m ->
                                PillChip(m.displayName, split.any { it.uuid == m.uuid }) {
                                    split = if (split.any { it.uuid == m.uuid }) {
                                        split.filterNot { it.uuid == m.uuid }.toSet()
                                    } else split + m
                                }
                            }
                        }
                    }
                }
            }

            // Una transferencia no es un gasto de nada: no lleva categoría.
            if (kind != TxType.BALANCE) {
                item {
                    SectionHeader("Categoría")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScreenPadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(Category.entries.toList(), key = { it.name }) { cat ->
                            PillChip(cat.emoji + " " + cat.label, category == cat) {
                                category = if (category == cat) null else cat
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(ScreenPadding)) {
                    Spacer(Modifier.height(8.dp))
                    val action = if (existing == null) {
                        "Añadir " + kind.label.lowercase()
                    } else {
                        "Guardar cambios"
                    }
                    PrimaryButton(action, valid) {
                        onSave(
                            MovementDraft(
                                kind = kind,
                                description = description.trim(),
                                amount = amount!!,
                                owner = effectiveOwner!!,
                                counterpart = counterpart,
                                splitAmong = effectiveSplit,
                                category = category
                            )
                        )
                    }
                    if (onDelete != null) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "Eliminar " + kind.label.lowercase(),
                                color = c.negative,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable(onClick = onDelete).padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddByLinkSheet(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val c = SmartTheme.colors
    var link by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.background,
        dragHandle = null
    ) {
        Column(Modifier.padding(ScreenPadding)) {
            Text("Añadir grupo", style = MaterialTheme.typography.titleLarge, color = c.primaryText)
            Spacer(Modifier.height(8.dp))
            Text(
                "En Tricount: abre el grupo → Compartir → Copiar enlace. También puedes " +
                    "compartir el enlace directamente hacia SmartCount.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.secondaryText
            )
            Spacer(Modifier.height(20.dp))
            SmartField(link, { link = it }, "Enlace de Tricount")
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Añadir", link.isNotBlank()) { onConfirm(link) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val c = SmartTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        Column(Modifier.padding(ScreenPadding)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.primaryText)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.secondaryText)
            Spacer(Modifier.height(24.dp))
            PrimaryButton(confirmLabel, onClick = onConfirm)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Cancelar",
                    color = c.secondaryText,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(14.dp)
                )
            }
        }
    }
}

// ===========================================================================
// Pestaña 2 · Estadísticas
// ===========================================================================

@Composable
fun StatsScreen(state: UiState, modifier: Modifier = Modifier) {
    val c = SmartTheme.colors
    val t = state.selected
    var tab by remember { mutableStateOf(0) }

    if (t == null) {
        Box(modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.Center) {
            Text("Elige un grupo en la pestaña Grupos", color = c.secondaryText)
        }
        return
    }

    val rows = remember(t, tab) {
        when (tab) {
            0 -> Stats.byCategory(t)
            1 -> Stats.byPayer(t)
            else -> Stats.byMonth(t).map { monthLabel(it.first) to it.second }
        }
    }
    val max = rows.maxOfOrNull { it.second } ?: 1.0

    LazyColumn(
        modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { ScreenTitle("Estadísticas") }
        item {
            Hero(formatMoney(Stats.totalSpent(t), t.currency), "Total gastado en ${t.title}")
            Spacer(Modifier.height(4.dp))
            Text(
                "Tu parte ${formatMoney(Stats.myShare(t), t.currency)} · " +
                    "media ${formatMoney(Stats.averagePerTransaction(t), t.currency)} por gasto",
                style = MaterialTheme.typography.bodySmall,
                color = c.secondaryText,
                modifier = Modifier.padding(horizontal = ScreenPadding)
            )
            Spacer(Modifier.height(20.dp))
            SegmentedTabs(listOf("Categoría", "Persona", "Mes"), tab) { tab = it }
            Spacer(Modifier.height(12.dp))
        }

        if (rows.isEmpty()) {
            item {
                Text(
                    "Sin datos todavía",
                    color = c.secondaryText,
                    modifier = Modifier.padding(ScreenPadding)
                )
            }
        }

        items(rows, key = { it.first }) { (label, value) ->
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = c.primaryText)
                    Text(
                        formatMoney(value, t.currency),
                        style = MaterialTheme.typography.titleMedium,
                        color = c.primaryText
                    )
                }
                Spacer(Modifier.height(8.dp))
                ProportionBar((value / max).toFloat())
            }
        }
    }
}

private fun monthLabel(key: String): String {
    val parts = key.split("-")
    if (parts.size < 2) return key
    val months = listOf(
        "ene", "feb", "mar", "abr", "may", "jun",
        "jul", "ago", "sep", "oct", "nov", "dic"
    )
    val m = parts[1].toIntOrNull()?.minus(1)?.coerceIn(0, 11) ?: return key
    return "${months[m]} ${parts[0]}"
}

// ===========================================================================
// Pestaña 3 · Bandeja (Bizum)
// ===========================================================================

@Composable
fun InboxScreen(
    vm: MainViewModel,
    state: UiState,
    inbox: List<InboxEntry>,
    modifier: Modifier = Modifier
) {
    val c = SmartTheme.colors
    val context = LocalContext.current
    val hasAccess = remember { BankNotificationListener.hasAccess(context) }
    var assigning by remember { mutableStateOf<InboxEntry?>(null) }

    // Tocar la notificación abre directamente la hoja de ese movimiento.
    val focused by vm.focusedEntry.collectAsStateWithLifecycle()
    LaunchedEffect(focused, inbox) {
        val id = focused ?: return@LaunchedEffect
        inbox.firstOrNull { it.id == id }?.let {
            assigning = it
            vm.focusInboxEntry(null)
        }
    }
    val fmt = remember { SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { ScreenTitle("Bandeja") }

        if (!hasAccess) {
            item {
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    Text(
                        "Falta el acceso a notificaciones",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.primaryText
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sin ese permiso SmartCount no puede ver tus movimientos. Solo se " +
                            "leen las apps de banco que elijas y nada sale del móvil.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.secondaryText
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Conceder permiso") {
                        context.startActivity(Intent(BankNotificationListener.settingsIntentAction))
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }

        if (inbox.isEmpty()) {
            item {
                Column(Modifier.padding(ScreenPadding)) {
                    Text("Nada pendiente", style = MaterialTheme.typography.titleLarge, color = c.primaryText)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Los Bizum y transferencias que detectemos aparecerán aquí para que " +
                            "los asignes a un grupo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.secondaryText
                    )
                }
            }
        } else {
            item {
                Hero(
                    inbox.size.toString(),
                    if (inbox.size == 1) "movimiento por asignar" else "movimientos por asignar"
                )
                Spacer(Modifier.height(12.dp))
            }
            items(inbox, key = { it.id }) { e ->
                val received = e.kind.isMoneyIn
                SmartRow(
                    title = e.merchant ?: e.counterparty ?: e.concept
                        ?: e.rawTitle.ifBlank { e.kind.label },
                    subtitle = buildString {
                        append(e.kind.label)
                        if (e.confidence == Confidence.LOW) append(" · revisar")
                        append(" · ")
                        append(fmt.format(Date(e.detectedAt)))
                    },
                    value = e.amount?.let {
                        formatMoney(if (received) it else -it, e.currency, signed = true)
                    } ?: "—",
                    valueColor = if (received) c.positive else c.negative,
                    leading = { Initials(e.merchant ?: e.counterparty ?: e.bankLabel) },
                    onClick = { assigning = e }
                )
                SmartDivider()
            }
        }
    }

    assigning?.let { entry ->
        AssignSheet(vm, entry, state.tricounts) { assigning = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignSheet(
    vm: MainViewModel,
    entry: InboxEntry,
    tricounts: List<Tricount>,
    onDismiss: () -> Unit
) {
    val c = SmartTheme.colors
    var target by remember { mutableStateOf(tricounts.firstOrNull()) }
    var asReimbursement by remember { mutableStateOf(vm.looksLikeReimbursement(entry)) }
    val t = target

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        if (t == null) {
            Column(Modifier.padding(ScreenPadding)) {
                Text("Sin grupos", style = MaterialTheme.typography.titleLarge, color = c.primaryText)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Añade primero un grupo desde la pestaña Grupos.",
                    color = c.secondaryText,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Entendido", onClick = onDismiss)
                Spacer(Modifier.height(16.dp))
            }
            return@ModalBottomSheet
        }

        val activeMembers = t.members.filter { it.status == "ACTIVE" }
        var payer by remember(t) { mutableStateOf(t.linkedMember ?: activeMembers.firstOrNull()) }
        var selection by remember(t, asReimbursement) { mutableStateOf(setOf<Member>()) }
        var description by remember {
        mutableStateOf(entry.concept ?: entry.merchant ?: entry.counterparty ?: entry.kind.label)
    }
        var amountText by remember {
            mutableStateOf(entry.amount?.let { String.format(Locale.US, "%.2f", it) } ?: "")
        }
        val amount = amountText.replace(',', '.').toDoubleOrNull()
        val valid = amount != null && amount > 0 && payer != null && selection.isNotEmpty()

        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(ScreenPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Asignar movimiento", style = MaterialTheme.typography.titleLarge, color = c.primaryText)
                    Text("Cancelar", color = c.secondaryText, modifier = Modifier.clickable(onClick = onDismiss))
                }
                Text(
                    entry.rawText.ifBlank { entry.rawTitle }.take(120),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding)
                )
                Spacer(Modifier.height(20.dp))
            }

            item {
                SectionHeader("Grupo")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tricounts, key = { it.id }) { g ->
                        PillChip(g.title, g.id == t.id) { target = g }
                    }
                }
            }

            item {
                SectionHeader("Tipo")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { PillChip("Reembolso", asReimbursement) { asReimbursement = true } }
                    item { PillChip("Gasto repartido", !asReimbursement) { asReimbursement = false } }
                }
            }

            item {
                Column(Modifier.padding(horizontal = ScreenPadding, vertical = 16.dp)) {
                    SmartField(amountText, { amountText = it }, "Importe (${t.currency})", KeyboardType.Decimal)
                    Spacer(Modifier.height(12.dp))
                    SmartField(description, { description = it }, "Descripción")
                }
            }

            item {
                SectionHeader(if (asReimbursement) "Quién paga" else "Quién pagó")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activeMembers, key = { it.uuid }) { m ->
                        PillChip(m.displayName, payer?.uuid == m.uuid) { payer = m }
                    }
                }
            }

            item {
                SectionHeader(if (asReimbursement) "Quién lo recibe" else "Repartido entre")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activeMembers, key = { it.uuid }) { m ->
                        PillChip(m.displayName, selection.any { it.uuid == m.uuid }) {
                            selection = when {
                                asReimbursement -> setOf(m)
                                selection.any { it.uuid == m.uuid } ->
                                    selection.filterNot { it.uuid == m.uuid }.toSet()
                                else -> selection + m
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(ScreenPadding)) {
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Enviar a Tricount", valid) {
                        vm.pushInboxEntry(
                            entry, t, asReimbursement, payer!!, selection.toList(),
                            description.trim(), amount!!, null
                        )
                        onDismiss()
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Ignorar este movimiento",
                            color = c.secondaryText,
                            modifier = Modifier
                                .clickable { vm.ignoreInboxEntry(entry); onDismiss() }
                                .padding(12.dp)
                        )
                    }
                    (entry.merchant ?: entry.counterparty)?.let { source ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "No volver a avisar de «$source»",
                                color = c.secondaryText,
                                modifier = Modifier
                                    .clickable { vm.muteSource(entry, source); onDismiss() }
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===========================================================================
// Pestaña 4 · Ajustes
// ===========================================================================

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    state: UiState,
    updateVm: UpdateViewModel,
    modifier: Modifier = Modifier
) {
    val c = SmartTheme.colors
    val context = LocalContext.current
    val update by updateVm.state.collectAsStateWithLifecycle()
    val app = context.applicationContext as SmartCountApp
    val registry = app.bankRegistry

    var learn by remember { mutableStateOf(registry.learnMode) }
    var watched by remember { mutableStateOf(registry.watchedPackages()) }
    val rules = app.notificationRules
    var ownName by remember { mutableStateOf(registry.ownName.orEmpty()) }
    var policyTick by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(rules.mutedSources()) }
    var editingName by remember { mutableStateOf(false) }
    val hasAccess = remember { BankNotificationListener.hasAccess(context) }

    if (editingName) {
        OwnNameSheet(
            initial = ownName,
            onDismiss = { editingName = false },
            onSave = { value ->
                ownName = value.trim()
                registry.ownName = ownName
                editingName = false
            }
        )
    }

    LazyColumn(
        modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { ScreenTitle("Ajustes") }

        item { SectionHeader("Detección") }
        item {
            SmartRow(
                title = "Acceso a notificaciones",
                subtitle = if (hasAccess) "Concedido" else "Sin conceder",
                value = if (hasAccess) "✓" else "→",
                valueColor = if (hasAccess) c.brand else c.secondaryText,
                onClick = {
                    context.startActivity(Intent(BankNotificationListener.settingsIntentAction))
                }
            )
            SmartDivider()
            SmartRow(
                title = "Tu nombre en el banco",
                subtitle = ownName.ifBlank {
                    "Sin definir · los movimientos entre tus cuentas llegarán a la bandeja"
                },
                value = if (ownName.isBlank()) "→" else "Cambiar",
                valueColor = c.secondaryText,
                onClick = { editingName = true }
            )
            SmartDivider()
            SmartRow(
                title = "Modo aprendizaje",
                subtitle = "Registra todas las notificaciones para localizar tu banco",
                value = if (learn) "ON" else "OFF",
                valueColor = if (learn) c.brand else c.secondaryText,
                onClick = { learn = !learn; registry.learnMode = learn }
            )
            SmartDivider()
        }

        item {
            SectionHeader("De qué te avisamos")
            Text(
                "Toca para alternar entre avisar, dejarlo solo en la bandeja o ignorarlo.",
                style = MaterialTheme.typography.bodySmall,
                color = c.secondaryText,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
            )
        }
        items(rules.configurableKinds(), key = { it.name }) { kind ->
            val policy = remember(kind, policyTick) { rules.policyFor(kind) }
            SmartRow(
                title = kind.label,
                value = policy.label,
                valueColor = when (policy) {
                    MovementPolicy.NOTIFY -> c.brand
                    MovementPolicy.INBOX_ONLY -> c.secondaryText
                    MovementPolicy.IGNORE -> c.negative
                },
                onClick = {
                    rules.setPolicy(kind, policy.next())
                    policyTick++
                }
            )
            SmartDivider()
        }

        item {
            SectionHeader("Silenciados")
            if (muted.isEmpty()) {
                Text(
                    "Nada silenciado. Cuando llegue una suscripción, la propia " +
                        "notificación te deja silenciar ese comercio de un toque.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
                )
            }
        }
        items(muted.toList().sorted(), key = { "muted-$it" }) { source ->
            SmartRow(
                title = source.replaceFirstChar { it.uppercase() },
                subtitle = "No avisa ni llega a la bandeja",
                value = "Reactivar",
                valueColor = c.secondaryText,
                onClick = {
                    rules.unmute(source)
                    muted = rules.mutedSources()
                }
            )
            SmartDivider()
        }

        item { SectionHeader("Apps vigiladas") }
        items(watched.toList().sorted(), key = { it }) { pkg ->
            SmartRow(
                title = registry.label(pkg),
                subtitle = pkg,
                value = "Quitar",
                valueColor = c.secondaryText,
                onClick = { registry.remove(pkg); watched = registry.watchedPackages() }
            )
            SmartDivider()
        }

        item {
            SectionHeader("Grupos de ahorro")
            Text(
                "Un grupo de ahorro es un grupo normal leído de otra manera: lo que " +
                    "creas tú son gastos, lo que crea «${Savings.INCOME_MEMBER}» son " +
                    "ingresos, y el ahorro es la resta. Al activarlo se añade ese " +
                    "miembro al grupo si no existe.",
                style = MaterialTheme.typography.bodySmall,
                color = c.secondaryText,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
            )
            if (state.tricounts.isEmpty()) {
                Text(
                    "Todavía no hay grupos que convertir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
                )
            }
        }
        items(state.tricounts, key = { "savings-${it.id}" }) { group ->
            val on = state.isSavings(group.id)
            SmartRow(
                title = "${group.emoji ?: ""} ${group.title}".trim(),
                subtitle = if (on) {
                    val summary = Savings.summary(group)
                    "Ahorrado ${formatMoney(summary.saved, group.currency, signed = true)}"
                } else {
                    "Grupo normal"
                },
                value = if (on) "Ahorro" else "Convertir",
                valueColor = if (on) c.brand else c.secondaryText,
                onClick = { vm.setSavings(group, !on) }
            )
            SmartDivider()
        }

        item {
            SectionHeader("Actualizaciones")
            SmartRow(
                title = "Buscar actualizaciones",
                subtitle = update.manualResult
                    ?: "Se comprueba sola al abrir la app",
                value = if (update.phase == UpdatePhase.CHECKING) "…" else "Comprobar",
                valueColor = if (update.manualResult?.startsWith("No se pudo") == true) {
                    c.negative
                } else {
                    c.brand
                },
                onClick = { updateVm.checkManually() }
            )
            SmartDivider()
            SmartRow(
                title = "Instalar apps desconocidas",
                subtitle = if (update.canInstall) {
                    "Concedido · las actualizaciones se instalan con un toque"
                } else {
                    "Sin conceder · hace falta para instalar la actualización"
                },
                value = if (update.canInstall) "✓" else "→",
                valueColor = if (update.canInstall) c.brand else c.secondaryText,
                onClick = { updateVm.openPermissionSettings() }
            )
            SmartDivider()
        }

        item {
            SectionHeader("Acerca de")
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(size = 34)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "SmartCount",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.primaryText
                        )
                        Text(
                            "Versión ${updateVm.installedVersionName} " +
                                "(build ${updateVm.installedVersionCode})",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.secondaryText
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "SmartCount usa la API interna de Tricount, que no es pública ni está " +
                        "documentada. Puede dejar de funcionar tras cualquier actualización y su " +
                        "uso queda fuera de los términos de servicio de Tricount. Uso personal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnNameSheet(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val c = SmartTheme.colors
    var value by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        Column(Modifier.padding(ScreenPadding)) {
            Text("Tu nombre en el banco", style = MaterialTheme.typography.titleLarge, color = c.primaryText)
            Spacer(Modifier.height(8.dp))
            Text(
                "Cuando mueves dinero entre tus propias cuentas, el banco te avisa como si " +
                    "alguien te hubiera enviado un Bizum. Con tu nombre aquí, SmartCount " +
                    "reconoce esos movimientos y no te los pone en la bandeja.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.secondaryText
            )
            Spacer(Modifier.height(20.dp))
            SmartField(value, { value = it }, "Nombre y apellidos")
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Guardar") { onSave(value) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
