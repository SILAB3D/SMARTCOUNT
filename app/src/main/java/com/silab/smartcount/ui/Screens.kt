package com.silab.smartcount.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.silab.smartcount.data.api.Category
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.Transaction
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.api.TxType
import com.silab.smartcount.data.repo.Savings
import com.silab.smartcount.ui.theme.DisplayNumber
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme
import java.util.Locale

// ===========================================================================
// Piezas comunes a todas las pestañas
// ===========================================================================

@Composable
internal fun ScreenTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
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
internal fun Hero(amount: String, label: String, amountColor: Color? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp)) {
        Text(amount, style = DisplayNumber, color = amountColor ?: SmartTheme.colors.primaryText)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SmartTheme.colors.secondaryText)
    }
}

/** Línea de apoyo bajo el hero: el detalle que matiza la cifra grande. */
@Composable
internal fun HeroCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = SmartTheme.colors.secondaryText,
        modifier = Modifier.padding(horizontal = ScreenPadding)
    )
}

/** Pestañas internas tipo segmento (Movimientos / Balance). */
@Composable
internal fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
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
internal fun SmartField(
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

/** "2026-03" → "mar 2026". */
internal fun monthLabel(key: String): String {
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
// Hoja: crear / editar movimiento
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

internal val TxType.label: String
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
    /** De dónde viene el dinero en un grupo de ahorro. */
    incomeMember: Member? = null,
    onSave: (MovementDraft) -> Unit
) {
    val c = SmartTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeMembers = remember(t) { t.members.filter { it.status == "ACTIVE" } }
    val me = remember(t) { t.linkedMember ?: activeMembers.firstOrNull() }
    val income = incomeMember ?: remember(t) { Savings.incomeMember(t) }

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
        kind == TxType.INCOME -> income ?: owner
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
                            "Entra al grupo desde «${income?.displayName ?: Savings.INCOME_MEMBER}»."
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
internal fun AddByLinkSheet(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
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
internal fun ConfirmSheet(
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

/**
 * Hoja de un solo propósito: elegir a una persona del grupo. La usan la
 * identidad ("quién eres tú aquí") y la fuente de ingresos de un grupo de
 * ahorro, que son la misma pregunta hecha dos veces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemberPickerSheet(
    title: String,
    body: String,
    members: List<Member>,
    selected: Member?,
    onDismiss: () -> Unit,
    onPick: (Member) -> Unit
) {
    val c = SmartTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(ScreenPadding)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = c.primaryText)
                    Spacer(Modifier.height(8.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = c.secondaryText)
                }
            }
            items(members, key = { it.uuid }) { m ->
                SmartRow(
                    title = m.displayName,
                    value = if (m.uuid == selected?.uuid) "✓" else null,
                    valueColor = c.brand,
                    leading = { Initials(m.displayName) },
                    onClick = { onPick(m) }
                )
                SmartDivider()
            }
        }
    }
}
