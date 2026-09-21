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
import androidx.compose.foundation.lazy.LazyListScope
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
import com.silab.smartcount.data.api.Split
import com.silab.smartcount.data.api.TricountClient
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.db.Confidence
import com.silab.smartcount.data.db.InboxClass
import com.silab.smartcount.data.db.InboxEntry
import com.silab.smartcount.notif.AssignPlan
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
 * Todo lo que han dicho las apps del móvil, en tres cajones.
 *
 * Eran dos —movimiento y no movimiento— y no daban para lo que hay que
 * decidir. Un aviso de tu banco que no es un cargo y la notificación de un
 * juego no son la misma cosa aunque las dos «no sean movimientos»: la primera
 * viene de una app que quieres seguir mirando y la segunda de una que no.
 * Separarlas permite que cada una tenga la acción que le corresponde —vigilar
 * la app, o dejar de seguirla— en vez de una sola papelera para las dos.
 *
 * Enseñar lo descartado es lo que convierte la bandeja en el sitio donde se
 * calibra el sistema y no solo donde se recogen resultados. El parser se
 * equivoca en las dos direcciones, y las dos equivocaciones no cuestan igual:
 * un aviso comercial colado entre los movimientos se aparta de un toque, pero
 * un movimiento descartado por error — la nómina que BBVA notifica sin
 * importe es el caso de libro — se perdía sin dejar rastro.
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
    var preselected by remember { mutableStateOf<Int?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val history by vm.inboxHistory.collectAsStateWithLifecycle()

    // La notificación abre directamente la hoja de ese movimiento, y con el
    // grupo que se eligió desde ella ya marcado.
    val focused by vm.focusedEntry.collectAsStateWithLifecycle()
    LaunchedEffect(focused, inbox) {
        val focus = focused ?: return@LaunchedEffect
        inbox.firstOrNull { it.id == focus.entryId }?.let {
            assigning = it
            preselected = focus.groupId
            vm.focusInboxEntry(null)
        }
    }
    val fmt = remember { SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()) }

    val byClass = inbox.groupBy { it.classification }
    val movements = byClass[InboxClass.BANK].orEmpty()
    val others = byClass[InboxClass.OTHER].orEmpty()
    val nonBank = byClass[InboxClass.NON_BANK].orEmpty()

    LazyColumn(
        modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(ScreenPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bandeja", style = MaterialTheme.typography.headlineLarge, color = c.primaryText)
                if (history.isNotEmpty()) {
                    SecondaryButton("Enviados") { showHistory = true }
                }
            }
        }

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
                            "leen las apps que elijas y nada sale del móvil.",
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
                listOfNotNull(
                    others.size.takeIf { it > 0 }?.let { "$it en otros eventos" },
                    nonBank.size.takeIf { it > 0 }?.let { "$it no bancarios" }
                ).joinToString(" · ").ifBlank { "Nada descartado" }
            )
            Spacer(Modifier.height(16.dp))
        }

        section(
            entries = movements,
            title = InboxClass.BANK.label,
            explanation = null,
            fmt = fmt
        ) { assigning = it; preselected = null }

        section(
            entries = others,
            title = InboxClass.OTHER.label,
            explanation = "Llegaron de apps que miramos, pero no parecen un cargo ni un abono. " +
                "Si alguno lo era, ábrelo y márcalo.",
            fmt = fmt
        ) { assigning = it; preselected = null }

        section(
            entries = nonBank,
            title = InboxClass.NON_BANK.label,
            explanation = "Sus apps han dejado de vigilarse. Puedes volver a activarlas en Ajustes.",
            fmt = fmt
        ) { assigning = it; preselected = null }
    }

    assigning?.let { entry ->
        // La lista viene del flujo, así que la entrada se relee para que el
        // cambio de cajón se vea sin cerrar nada.
        val live = inbox.firstOrNull { it.id == entry.id } ?: entry
        AssignSheet(vm, live, state, preselected) { assigning = null; preselected = null }
    }

    if (showHistory) {
        HistorySheet(history, state, fmt) { showHistory = false }
    }
}

/** Un cajón de la bandeja, con su explicación si hace falta. */
private fun LazyListScope.section(
    entries: List<InboxEntry>,
    title: String,
    explanation: String?,
    fmt: SimpleDateFormat,
    onClick: (InboxEntry) -> Unit
) {
    if (entries.isEmpty()) return
    item(key = "head-$title") {
        SectionHeader(title)
        if (explanation != null) {
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = SmartTheme.colors.secondaryText,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
            )
        }
    }
    items(entries, key = { it.id }) { e ->
        InboxRow(e, fmt) { onClick(e) }
        SmartDivider()
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
            if (e.userClass != null) append(" · a mano")
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
 * Asignar un movimiento a uno o varios grupos.
 *
 * Va a varios a la vez porque el recibo de la luz va al piso y al grupo de
 * ahorro, y hacerlo dos veces obligaba a repetir importe y descripción a mano.
 * Cada grupo elegido guarda su propio reparto: los miembros de uno no son los
 * del otro y "quién paga" no se puede decidir una vez para todos.
 *
 * En un grupo de ahorro no se pregunta nada de eso: los papeles del grupo
 * mandan, y lo dice en su sitio en vez de enseñar unos selectores que no se
 * van a respetar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignSheet(
    vm: MainViewModel,
    entry: InboxEntry,
    state: UiState,
    preselectedGroupId: Int?,
    onDismiss: () -> Unit
) {
    val c = SmartTheme.colors
    val tricounts = state.tricounts
    // La propuesta de partida sale de AssignPlan, que es la misma decisión que
    // tomaba el botón de la notificación cuando creaba el movimiento por su
    // cuenta. Ahora no decide: rellena la hoja y se puede cambiar todo.
    fun planOf(t: Tricount) = AssignPlan.planFor(entry, t)

    var asReimbursement by remember {
        mutableStateOf(
            tricounts.firstOrNull { it.id == preselectedGroupId }
                ?.let { planOf(it) is AssignPlan.Plan.Reimbursement }
                ?: tricounts.firstOrNull()?.let { planOf(it) is AssignPlan.Plan.Reimbursement }
                ?: false
        )
    }

    var chosen by remember {
        mutableStateOf(
            listOfNotNull(
                preselectedGroupId?.takeIf { id -> tricounts.any { it.id == id } }
                    ?: tricounts.firstOrNull()?.id
            )
        )
    }
    var payers by remember { mutableStateOf(mapOf<Int, String>()) }
    var splits by remember { mutableStateOf(mapOf<Int, Set<String>>()) }

    // El reparto desigual, grupo a grupo: en qué grupos se pone la cantidad a
    // mano, y cuánto lleva cada uuid. Va por grupo y no una sola vez porque los
    // miembros de uno no son los del otro y el mismo cargo puede ir a varios.
    var byAmounts by remember { mutableStateOf(setOf<Int>()) }
    var splitAmounts by remember { mutableStateOf(mapOf<Int, Map<String, String>>()) }

    var description by remember {
        mutableStateOf(entry.concept ?: entry.merchant ?: entry.counterparty ?: entry.kind.label)
    }
    var amountText by remember {
        mutableStateOf(entry.amount?.let { String.format(Locale.US, "%.2f", it) } ?: "")
    }
    val amount = amountText.replace(',', '.').toDoubleOrNull()

    fun membersOf(t: Tricount) = t.members.filter { it.status == "ACTIVE" }
    fun payerOf(t: Tricount): Member? {
        payers[t.id]?.let { uuid -> t.memberByUuid(uuid)?.let { return it } }
        return when (val plan = planOf(t)) {
            is AssignPlan.Plan.Reimbursement -> plan.payer
            is AssignPlan.Plan.Expense -> plan.payer
        }
    }

    fun splitOf(t: Tricount): List<Member> {
        splits[t.id]?.let { stored -> return membersOf(t).filter { it.uuid in stored } }
        return when (val plan = planOf(t)) {
            // El Bizum dice con quién fue: quien lo recibe viene propuesto.
            is AssignPlan.Plan.Reimbursement ->
                if (asReimbursement) listOf(plan.receiver) else membersOf(t)
            is AssignPlan.Plan.Expense ->
                if (asReimbursement) emptyList() else plan.splitAmong
        }
    }

    /** Las partes fijadas a mano de un grupo, ya en número y solo de quien entra. */
    fun fixedOf(t: Tricount): Map<String, Double> =
        if (t.id !in byAmounts) {
            emptyMap()
        } else {
            splitOf(t).mapNotNull { m ->
                splitAmounts[t.id]?.get(m.uuid)
                    ?.replace(',', '.')?.toDoubleOrNull()
                    ?.let { m.uuid to it }
            }.toMap()
        }

    /**
     * Qué le falta al reparto de este grupo para cuadrar. La API rechaza las
     * asignaciones que no suman el total, así que se dice aquí en vez de
     * enseñar luego su error.
     */
    fun splitErrorOf(t: Tricount): String? {
        if (amount == null || asReimbursement || state.isSavings(t.id) || t.id !in byAmounts) return null
        val split = Split(splitOf(t), fixedOf(t))
        val pending = split.remainder(amount)
        return when {
            split.free.isEmpty() && pending > 0.005 ->
                "Faltan ${formatMoney(pending, t.currency)} por asignar"
            pending < -0.005 ->
                "Las partes se pasan en ${formatMoney(-pending, t.currency)}"
            else -> null
        }
    }

    val targets = chosen.mapNotNull { id -> tricounts.firstOrNull { it.id == id } }
    val valid = amount != null && amount > 0 && targets.isNotEmpty() &&
        targets.all { t ->
            state.isSavings(t.id) ||
                (payerOf(t) != null && splitOf(t).isNotEmpty() && splitErrorOf(t) == null)
        }

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

            // Calibración, arriba del todo cuando no está en el cajón de los
            // movimientos: es la decisión que hay que tomar antes que ninguna.
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
                            "Si lo era, márcalo y podrás asignarlo: su app pasa a estar " +
                                "vigilada. Rellena el importe si el banco no lo puso.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.secondaryText
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Sí es un movimiento bancario") {
                            vm.classifyInboxEntry(entry, InboxClass.BANK)
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

            val normalTargets = targets.filterNot { state.isSavings(it.id) }

            if (normalTargets.isNotEmpty()) {
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
                                byAmounts = emptySet()
                                splitAmounts = emptyMap()
                            }
                        }
                        item {
                            PillChip("Gasto repartido", !asReimbursement) {
                                asReimbursement = false
                                splits = emptyMap()
                                byAmounts = emptySet()
                                splitAmounts = emptyMap()
                            }
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
            normalTargets.forEach { t ->
                item(key = "payer-${t.id}") {
                    val members = membersOf(t)
                    Column {
                        SectionHeader(
                            if (normalTargets.size > 1) {
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
                        if (asReimbursement) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = ScreenPadding),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(members, key = { it.uuid }) { m ->
                                    PillChip(m.displayName, splitOf(t).any { it.uuid == m.uuid }) {
                                        splits = splits + (t.id to setOf(m.uuid))
                                    }
                                }
                            }
                        } else {
                            // Un gasto repartido se puede dividir a partes
                            // iguales o con la cantidad de cada uno puesta a
                            // mano, igual que en la hoja del grupo: la cena en
                            // la que uno no bebió llega desde la notificación
                            // sin tener que corregirla luego en Tricount.
                            val amounts = splitAmounts[t.id].orEmpty()
                            val custom = t.id in byAmounts
                            val chosenMembers = splitOf(t)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = ScreenPadding),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(SplitMode.entries.toList(), key = { it.name }) { option ->
                                    val selected = (option == SplitMode.AMOUNTS) == custom
                                    PillChip(option.label, selected) {
                                        if (option == SplitMode.AMOUNTS) {
                                            byAmounts = byAmounts + t.id
                                        } else {
                                            byAmounts = byAmounts - t.id
                                            splitAmounts = splitAmounts - t.id
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            members.forEach { m ->
                                val included = chosenMembers.any { it.uuid == m.uuid }
                                val shareText = when {
                                    !included -> "—"
                                    custom -> null   // lo pone el campo
                                    amount == null -> "—"
                                    else -> {
                                        val i = chosenMembers.indexOfFirst { it.uuid == m.uuid }
                                        val parts = TricountClient.previewSplit(amount, chosenMembers.size)
                                        formatMoney(parts.getOrElse(i) { 0.0 }, t.currency)
                                    }
                                }
                                MemberSplitRow(
                                    member = m,
                                    included = included,
                                    currency = t.currency,
                                    shareText = shareText,
                                    amountText = amounts[m.uuid].orEmpty(),
                                    editable = custom,
                                    onToggle = {
                                        val current = chosenMembers.map { it.uuid }.toSet()
                                        splits = splits + (t.id to
                                            if (included) current - m.uuid else current + m.uuid)
                                        if (included) {
                                            splitAmounts = splitAmounts + (t.id to (amounts - m.uuid))
                                        }
                                    },
                                    onAmountChange = { raw ->
                                        splitAmounts = splitAmounts + (t.id to
                                            if (raw.isBlank()) amounts - m.uuid else amounts + (m.uuid to raw))
                                        if (raw.isNotBlank() && !included) {
                                            splits = splits + (t.id to
                                                (chosenMembers.map { it.uuid }.toSet() + m.uuid))
                                        }
                                    }
                                )
                                SmartDivider()
                            }
                            if (custom) {
                                val error = splitErrorOf(t)
                                val free = Split(chosenMembers, fixedOf(t)).free.size
                                val pending = amount?.let { Split(chosenMembers, fixedOf(t)).remainder(it) } ?: 0.0
                                Text(
                                    when {
                                        error != null -> error
                                        free > 0 && pending > 0.005 ->
                                            "${formatMoney(pending, t.currency)} para " +
                                                (if (free == 1) "el que queda" else "los $free que quedan")
                                        else -> "Deja en blanco a quien deba repartirse lo que sobre."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (error != null) c.negative else c.secondaryText,
                                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            targets.filter { state.isSavings(it.id) }.forEach { t ->
                item(key = "savings-${t.id}") {
                    Text(
                        "En «${t.title}» se registra con sus papeles: " +
                            if (entry.kind.isMoneyIn) {
                                "ingreso desde «${vm.incomeMember(t)?.displayName ?: "la fuente de ingresos"}» " +
                                    "hacia «${vm.spenderMember(t)?.displayName ?: "quien gasta"}»."
                            } else {
                                "gasto a nombre de «${vm.spenderMember(t)?.displayName ?: "quien gasta"}»."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.secondaryText,
                        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)
                    )
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
                                    asReimbursement = asReimbursement && !state.isSavings(t.id),
                                    payer = payerOf(t)!!,
                                    receiverOrSplit = splitOf(t),
                                    fixed = if (asReimbursement) emptyMap() else fixedOf(t)
                                )
                            },
                            description.trim(), amount!!, null
                        )
                        onDismiss()
                    }
                    Spacer(Modifier.height(12.dp))

                    // Los tres cajones, menos el que ya ocupa.
                    InboxClass.entries.filter { it != entry.classification }.forEach { target ->
                        FooterAction(
                            when (target) {
                                InboxClass.BANK -> "Es un movimiento bancario"
                                InboxClass.OTHER -> "Moverlo a otros eventos"
                                InboxClass.NON_BANK ->
                                    "No es bancario · dejar de seguir «${entry.bankLabel}»"
                            }
                        ) {
                            vm.classifyInboxEntry(entry, target)
                            if (target != InboxClass.BANK) onDismiss()
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

/** Lo ya enviado a Tricount: mirar atrás sin salir de la bandeja. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    history: List<InboxEntry>,
    state: UiState,
    fmt: SimpleDateFormat,
    onDismiss: () -> Unit
) {
    val c = SmartTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(ScreenPadding)) {
                    Text("Enviados", style = MaterialTheme.typography.titleLarge, color = c.primaryText)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Los últimos movimientos que salieron de la bandeja. Para cambiarlos, " +
                            "búscalos en su grupo: allí es donde viven.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.secondaryText
                    )
                }
            }
            items(history, key = { it.id }) { e ->
                val group = state.tricounts.firstOrNull { it.id == e.tricountId }
                SmartRow(
                    title = e.merchant ?: e.counterparty ?: e.concept ?: e.kind.label,
                    subtitle = listOfNotNull(
                        group?.title ?: "grupo desconocido",
                        fmt.format(Date(e.detectedAt))
                    ).joinToString(" · "),
                    value = e.amount?.let { formatMoney(it, e.currency) } ?: "—",
                    valueColor = c.secondaryText,
                    leading = { Initials(e.merchant ?: e.counterparty ?: e.bankLabel) }
                )
                SmartDivider()
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
