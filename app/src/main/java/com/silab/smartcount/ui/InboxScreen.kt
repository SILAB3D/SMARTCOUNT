package com.silab.smartcount.ui

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.db.Confidence
import com.silab.smartcount.data.db.InboxEntry
import com.silab.smartcount.notif.BankNotificationListener
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===========================================================================
// Pestaña 4 · Bandeja
// ===========================================================================

/**
 * Todo lo que han dicho las apps de banco, en dos grupos: lo que el parser ha
 * reconocido como un movimiento y lo que no.
 *
 * Enseñar también lo descartado es lo que convierte la bandeja en un sitio
 * donde se calibra el sistema y no solo donde se recogen resultados. El parser
 * se equivoca en las dos direcciones, y las dos equivocaciones no cuestan
 * igual: un aviso comercial colado entre los movimientos se aparta de un
 * toque, pero un movimiento que el parser descartó — la nómina que BBVA
 * notifica sin importe es el caso de libro — se perdía sin dejar rastro. Ahora
 * cualquiera de los dos se mueve al otro grupo, y esa decisión manda sobre la
 * del parser.
 */
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

    val movements = inbox.filter { it.isBankMovement }
    val others = inbox.filterNot { it.isBankMovement }

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
                        "Los Bizum, transferencias y pagos que detectemos aparecerán aquí " +
                            "para que los asignes a un grupo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.secondaryText
                    )
                }
            }
            return@LazyColumn
        }

        item {
            Hero(
                movements.size.toString(),
                if (movements.size == 1) "movimiento por asignar" else "movimientos por asignar"
            )
            Spacer(Modifier.height(4.dp))
            HeroCaption(
                if (others.isEmpty()) {
                    "Nada descartado"
                } else {
                    "${others.size} notificaciones descartadas, abajo"
                }
            )
            Spacer(Modifier.height(16.dp))
        }

        if (movements.isNotEmpty()) {
            item { SectionHeader("Movimientos bancarios") }
            items(movements, key = { it.id }) { e ->
                InboxRow(e, fmt) { assigning = e }
                SmartDivider()
            }
        }

        if (others.isNotEmpty()) {
            item {
                SectionHeader("No parecen movimientos")
                Text(
                    "Si alguno sí lo era, ábrelo y márcalo: a partir de ahí se comporta " +
                        "como cualquier otro movimiento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
                )
            }
            items(others, key = { it.id }) { e ->
                InboxRow(e, fmt) { assigning = e }
                SmartDivider()
            }
        }
    }

    assigning?.let { entry ->
        // La lista viene del flujo, así que la entrada se relee para que el
        // cambio de "no es un movimiento" a "sí lo es" se vea sin cerrar nada.
        val live = inbox.firstOrNull { it.id == entry.id } ?: entry
        AssignSheet(vm, live, state) { assigning = null }
    }
}

@Composable
private fun InboxRow(e: InboxEntry, fmt: SimpleDateFormat, onClick: () -> Unit) {
    val c = SmartTheme.colors
    val received = e.kind.isMoneyIn
    SmartRow(
        title = e.merchant ?: e.counterparty ?: e.concept ?: e.rawTitle.ifBlank { e.kind.label },
        subtitle = buildString {
            append(if (e.isBankMovement) e.kind.label else e.bankLabel)
            if (e.confidence == Confidence.LOW && e.isBankMovement) append(" · revisar")
            if (e.userMovement != null) append(" · a mano")
            append(" · ")
            append(fmt.format(Date(e.detectedAt)))
        },
        value = e.amount?.let {
            formatMoney(if (received) it else -it, e.currency, signed = true)
        } ?: "—",
        valueColor = when {
            !e.isBankMovement -> c.secondaryText
            received -> c.positive
            else -> c.negative
        },
        leading = { Initials(e.merchant ?: e.counterparty ?: e.bankLabel) },
        onClick = onClick
    )
}

// ===========================================================================
// Hoja de asignación
// ===========================================================================

/**
 * Asignar un movimiento. Dos cosas que antes no se podían hacer:
 *
 *  - **llevarlo a varios grupos a la vez**: el recibo de la luz va al piso y
 *    al grupo de ahorro, y hacerlo dos veces obligaba a repetir el importe y
 *    la descripción a mano, con lo que eso tiene de erratas;
 *  - **decir que no es un movimiento**, que es la mitad que le faltaba a la
 *    calibración.
 *
 * Cada grupo elegido guarda su propio reparto, porque los miembros de uno no
 * son los del otro y "quién paga" no se puede decidir una vez para todos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignSheet(
    vm: MainViewModel,
    entry: InboxEntry,
    state: UiState,
    onDismiss: () -> Unit
) {
    val c = SmartTheme.colors
    val tricounts = state.tricounts
    var asReimbursement by remember { mutableStateOf(vm.looksLikeReimbursement(entry)) }

    // Selección por grupo: qué grupos, y dentro de cada uno quién paga y entre
    // quiénes se reparte.
    var chosen by remember { mutableStateOf(listOfNotNull(tricounts.firstOrNull()?.id)) }
    var payers by remember { mutableStateOf(mapOf<Int, String>()) }
    var splits by remember { mutableStateOf(mapOf<Int, Set<String>>()) }

    var description by remember {
        mutableStateOf(entry.concept ?: entry.merchant ?: entry.counterparty ?: entry.kind.label)
    }
    var amountText by remember {
        mutableStateOf(entry.amount?.let { String.format(Locale.US, "%.2f", it) } ?: "")
    }
    val amount = amountText.replace(',', '.').toDoubleOrNull()

    fun membersOf(t: Tricount) = t.members.filter { it.status == "ACTIVE" }
    fun payerOf(t: Tricount): Member? =
        payers[t.id]?.let { t.memberByUuid(it) } ?: t.linkedMember ?: membersOf(t).firstOrNull()
    fun splitOf(t: Tricount): List<Member> {
        val stored = splits[t.id]
        return when {
            stored != null -> membersOf(t).filter { it.uuid in stored }
            asReimbursement -> emptyList()
            else -> membersOf(t)
        }
    }

    val targets = chosen.mapNotNull { id -> tricounts.firstOrNull { it.id == id } }
    val valid = amount != null && amount > 0 && targets.isNotEmpty() &&
        targets.all { payerOf(it) != null && splitOf(it).isNotEmpty() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        if (tricounts.isEmpty()) {
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

        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(ScreenPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Asignar movimiento",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.primaryText
                    )
                    Text("Cancelar", color = c.secondaryText, modifier = Modifier.clickable(onClick = onDismiss))
                }
                Text(
                    entry.rawText.ifBlank { entry.rawTitle }.take(160),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding)
                )
                Spacer(Modifier.height(16.dp))
            }

            // Calibración, arriba del todo cuando el parser lo había descartado:
            // es la decisión que hay que tomar antes que ninguna otra.
            if (!entry.isBankMovement) {
                item {
                    Column(Modifier.padding(horizontal = ScreenPadding)) {
                        Text(
                            "Esto no parecía un movimiento bancario",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.primaryText
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Si lo era, márcalo y podrás asignarlo. Rellena el importe si el " +
                                "banco no lo puso en la notificación.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.secondaryText
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Sí es un movimiento bancario") {
                            vm.setBankMovement(entry, true)
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }

            item {
                SectionHeader("Grupos") {
                    Text(
                        if (chosen.size == tricounts.size) "Quitar todos" else "Todos",
                        color = c.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable {
                            chosen = if (chosen.size == tricounts.size) emptyList() else tricounts.map { it.id }
                        }
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tricounts, key = { it.id }) { g ->
                        PillChip("${g.emoji ?: ""} ${g.title}".trim(), g.id in chosen) {
                            chosen = if (g.id in chosen) chosen - g.id else chosen + g.id
                        }
                    }
                }
                if (chosen.size > 1) {
                    Text(
                        "Se creará el mismo movimiento en ${chosen.size} grupos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.secondaryText,
                        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)
                    )
                }
            }

            item {
                SectionHeader("Tipo")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        PillChip("Reembolso", asReimbursement) {
                            asReimbursement = true
                            splits = emptyMap()
                        }
                    }
                    item {
                        PillChip("Gasto repartido", !asReimbursement) {
                            asReimbursement = false
                            splits = emptyMap()
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = ScreenPadding, vertical = 16.dp)) {
                    SmartField(amountText, { amountText = it }, "Importe", KeyboardType.Decimal)
                    Spacer(Modifier.height(12.dp))
                    SmartField(description, { description = it }, "Descripción")
                }
            }

            // Las personas, grupo a grupo: los miembros de uno no son los del
            // otro, así que "quién paga" no se puede decidir una vez para todos.
            targets.forEach { t ->
                item(key = "payer-${t.id}") {
                    val members = membersOf(t)
                    Column {
                        SectionHeader(
                            if (targets.size > 1) {
                                "${t.title} · ${if (asReimbursement) "quién paga" else "quién pagó"}"
                            } else if (asReimbursement) "Quién paga" else "Quién pagó"
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = ScreenPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(members, key = { it.uuid }) { m ->
                                PillChip(m.displayName, payerOf(t)?.uuid == m.uuid) {
                                    payers = payers + (t.id to m.uuid)
                                }
                            }
                        }
                        SectionHeader(
                            if (asReimbursement) "Quién lo recibe" else "Repartido entre"
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = ScreenPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(members, key = { it.uuid }) { m ->
                                val current = splitOf(t).map { it.uuid }.toSet()
                                PillChip(m.displayName, m.uuid in current) {
                                    val next = when {
                                        asReimbursement -> setOf(m.uuid)
                                        m.uuid in current -> current - m.uuid
                                        else -> current + m.uuid
                                    }
                                    splits = splits + (t.id to next)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(ScreenPadding)) {
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(
                        if (targets.size > 1) "Enviar a ${targets.size} grupos" else "Enviar a Tricount",
                        valid
                    ) {
                        vm.pushInboxEntry(
                            entry,
                            targets.map { t ->
                                MainViewModel.TargetGroup(
                                    tricount = t,
                                    asReimbursement = asReimbursement,
                                    payer = payerOf(t)!!,
                                    receiverOrSplit = splitOf(t)
                                )
                            },
                            description.trim(), amount!!, null
                        )
                        onDismiss()
                    }
                    Spacer(Modifier.height(12.dp))
                    if (entry.isBankMovement) {
                        FooterAction("No es un movimiento bancario") {
                            vm.setBankMovement(entry, false)
                            onDismiss()
                        }
                    }
                    FooterAction("Ignorar este movimiento") {
                        vm.ignoreInboxEntry(entry); onDismiss()
                    }
                    (entry.merchant ?: entry.counterparty)?.let { source ->
                        FooterAction("No volver a avisar de «$source»") {
                            vm.muteSource(entry, source); onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterAction(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = SmartTheme.colors.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable(onClick = onClick).padding(12.dp)
        )
    }
}
