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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silab.smartcount.data.api.Category
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.SettlementLeg
import com.silab.smartcount.data.api.Transaction
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.api.TxType
import com.silab.smartcount.data.repo.SavingsSummary
import com.silab.smartcount.data.repo.Stats
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SectionTitle
import com.silab.smartcount.ui.theme.SmartTheme
import java.text.Normalizer

// ===========================================================================
// Pestaña 1 · Grupos
// ===========================================================================

/** Sin tildes y en minúsculas: "Salamanca" y "salamanca" buscan lo mismo. */
internal fun foldForSearch(value: String): String =
    Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

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
    var adding by remember { mutableStateOf(false) }
    var showAddLink by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    // Sobrevive a abrir y cerrar un grupo: al volver, la búsqueda sigue puesta.
    var query by rememberSaveable { mutableStateOf("") }

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

    val needle = foldForSearch(query)
    val visible = if (needle.isBlank()) {
        state.tricounts
    } else {
        state.tricounts.filter { foldForSearch(it.title).contains(needle) }
    }

    Box(modifier.fillMaxSize().background(c.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
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
                    SecondaryButton("Añadir") { adding = true }
                }
            }

            // El buscador solo aparece cuando hay grupos que buscar: con dos,
            // ocupa sitio y no ahorra nada.
            if (state.tricounts.size > 3) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SmartField(query, { query = it }, "Buscar grupo")
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            if (state.tricounts.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(state.loading, onPaste = { showAddLink = true }, onCreate = { creating = true })
                }
            } else if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Ningún grupo se llama así.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.secondaryText,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            items(visible, key = { it.id }) { g ->
                GroupCard(
                    group = g,
                    savings = state.isSavings(g.id),
                    summary = if (state.isSavings(g.id)) vm.savingsSummary(g) else null
                ) { vm.openGroup(g.id) }
            }

            if (state.archived.isNotEmpty() && needle.isBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader("Archivados")
                }
                items(state.archived, key = { "arch-" + it.token }) { entry ->
                    ArchivedCard(entry.title, entry.emoji) { vm.restoreGroup(entry) }
                }
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

    if (adding) {
        AddGroupChoiceSheet(
            onDismiss = { adding = false },
            onPaste = { adding = false; showAddLink = true },
            onCreate = { adding = false; creating = true }
        )
    }

    if (showAddLink) {
        AddByLinkSheet(
            onDismiss = { showAddLink = false },
            onConfirm = { link -> vm.addByLink(link); showAddLink = false }
        )
    }

    if (creating) {
        CreateGroupSheet(
            onDismiss = { creating = false },
            onCreate = { title, currency, members ->
                vm.createGroup(title, currency, members)
                creating = false
            }
        )
    }
}

/**
 * La ficha de un grupo en la rejilla. Enseña la cifra que define al grupo —
 * lo que te deben, o lo ahorrado si es un grupo de ahorro — porque una rejilla
 * de nombres a secas obligaría a entrar en cada uno para saber cómo va.
 *
 * Los de ahorro van sobre un fondo teñido de azul: se distinguen de un vistazo
 * sin leer la etiqueta, que es lo que se pide a una rejilla.
 */
@Composable
private fun GroupCard(
    group: Tricount,
    savings: Boolean,
    summary: SavingsSummary?,
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
            .background(if (savings) c.savingsTint else c.chipBackground)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.emoji ?: "•", style = MaterialTheme.typography.titleMedium)
            if (savings) {
                Spacer(Modifier.width(6.dp))
                Text("AHORRO", style = SectionTitle, color = c.brand)
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

@Composable
private fun ArchivedCard(title: String, emoji: String?, onRestore: () -> Unit) {
    val c = SmartTheme.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.chipBackground)
            .clickable(onClick = onRestore)
            .padding(16.dp)
    ) {
        Text(emoji ?: "•", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = c.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Text("Recuperar", style = MaterialTheme.typography.bodySmall, color = c.brand)
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
    var pickingSpender by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }

    val isSavings = state.isSavings(t.id)
    val activeMembers = remember(t) { t.members.filter { it.status == "ACTIVE" } }
    val incomeMember = vm.incomeMember(t)
    val spenderMember = vm.spenderMember(t)
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
                    SecondaryButton("Gestionar") { managing = true }
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
                    FigureRow(
                        listOf(
                            Triple("Ingresos", formatMoney(summary.income, t.currency), c.positive),
                            Triple("Gastos", formatMoney(summary.spent, t.currency), c.negative),
                            Triple(
                                "Balance",
                                formatMoney(summary.saved, t.currency, signed = true),
                                if (summary.saved < 0) c.negative else c.primaryText
                            )
                        )
                    )
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
                    Spacer(Modifier.height(12.dp))
                    // Las dos cifras de un grupo normal, en el mismo formato
                    // que las tres de uno de ahorro: lo que llevas gastado tú
                    // y lo que lleva gastado el grupo. Antes esto era una
                    // línea de texto que solo daba el total.
                    FigureRow(
                        listOf(
                            Triple("Mis gastos", formatMoney(Stats.myShare(t), t.currency), c.primaryText),
                            Triple("Gastos del grupo", formatMoney(Stats.totalSpent(t), t.currency), c.primaryText)
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    HeroCaption("${t.activeTransactions.size} movimientos · ${activeMembers.size} personas")
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
                    spenderMember = spenderMember,
                    onPickMe = { pickingMe = true },
                    onPickIncome = { pickingIncome = true },
                    onPickSpender = { pickingSpender = true }
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
                        onClick = { editing = tx }
                    )
                    SmartDivider()
                }
            } else {
                item { BalanceSection(vm, t) }
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
            incomeMember = incomeMember,
            spenderMember = spenderMember
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
            incomeMember = incomeMember,
            spenderMember = spenderMember
        ) { draft ->
            vm.editMovement(t, tx, draft)
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

    if (managing) {
        ManageGroupSheet(
            vm = vm,
            t = t,
            savings = isSavings,
            onDismiss = { managing = false }
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

    if (pickingSpender) {
        MemberPickerSheet(
            title = "¿Quién gasta?",
            body = "Los gastos de este grupo se registran a su nombre, y los ingresos van " +
                "de la fuente de ingresos hacia él.",
            members = activeMembers,
            selected = spenderMember,
            onDismiss = { pickingSpender = false },
            onPick = { vm.setSpenderMember(t, it); pickingSpender = false }
        )
    }
}

/** Dos o tres cifras en fila, el formato de cabecera de todos los grupos. */
@Composable
internal fun FigureRow(figures: List<Triple<String, String, Color>>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        figures.forEach { (label, value, color) ->
            Figure(label, value, color, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun Figure(
    label: String,
    value: String,
    color: Color,
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

/** Quién eres tú, y los dos papeles si el grupo es de ahorro. */
@Composable
private fun IdentityRow(
    t: Tricount,
    savings: Boolean,
    incomeMember: Member?,
    spenderMember: Member?,
    onPickMe: () -> Unit,
    onPickIncome: () -> Unit,
    onPickSpender: () -> Unit
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
            SmartRow(
                title = spenderMember?.displayName ?: "Elegir quién gasta",
                subtitle = "A su nombre se registran los gastos",
                value = "Cambiar",
                valueColor = if (spenderMember == null) c.brand else c.secondaryText,
                leading = { Initials(spenderMember?.displayName ?: "?") },
                onClick = onPickSpender
            )
            SmartDivider()
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, onPaste: () -> Unit, onCreate: () -> Unit) {
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
            "Crea un grupo aquí mismo, o abre Tricount, copia el enlace para compartir de " +
                "uno que ya tengas y pégalo: SmartCount se une y a partir de ahí puedes " +
                "crear, editar y borrar movimientos.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.secondaryText
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Crear grupo", onClick = onCreate)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Pegar un enlace",
                color = c.brand,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onPaste).padding(12.dp)
            )
        }
    }
}

/**
 * Una fila de movimiento.
 *
 * Dice **quién lo hizo y a quién afecta**, que es la mitad de la información
 * de un gasto compartido y antes no estaba: la fila decía «Ana · 3 sep» y
 * había que abrir el movimiento para saber si esos 40 € eran de los cinco o
 * solo de dos.
 *
 * En un grupo de ahorro el color dice de qué lado viene el dinero — verde lo
 * que entra, rojo lo que sale — y en un grupo normal se queda en negro: allí
 * un gasto no es una mala noticia, es el material del que está hecho el grupo,
 * y pintarlo todo de rojo no informaría de nada.
 */
@Composable
private fun TransactionRow(
    tx: Transaction,
    t: Tricount,
    income: Boolean?,
    onClick: () -> Unit
) {
    val c = SmartTheme.colors
    val cat = Category.fromApi(tx.category)
    val amount = tx.amount.abs
    SmartRow(
        title = tx.description.ifBlank { "Sin descripción" },
        subtitle = participantsLine(tx, t),
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

/**
 * «Ana pagó · entre Ana y Beto», «Ana → Beto», «Ana pagó · entre los 5».
 *
 * Con pocos miembros se nombran; a partir de cuatro se cuentan, porque una
 * lista de nombres recortada con puntos suspensivos no dice ni quiénes son ni
 * cuántos, que son las dos cosas que se querían saber.
 */
internal fun participantsLine(tx: Transaction, t: Tricount): String {
    val owner = t.memberByUuid(tx.ownerUuid)?.displayName ?: "?"
    val date = formatTxDate(tx.date)

    if (tx.type == TxType.BALANCE) {
        val to = tx.allocations
            .firstOrNull { it.membershipUuid != tx.ownerUuid }
            ?.let { t.memberByUuid(it.membershipUuid)?.displayName }
            ?: "?"
        return "$owner → $to · $date"
    }

    val verb = if (tx.type == TxType.INCOME) "recibió" else "pagó"
    val names = tx.allocations.mapNotNull { t.memberByUuid(it.membershipUuid)?.displayName }
    val activeCount = t.members.count { it.status == "ACTIVE" }
    val who = when {
        names.isEmpty() -> ""
        names.size == activeCount && activeCount > 2 -> " · entre todos"
        names.size == 1 -> " · para ${names.first()}"
        names.size <= 3 -> " · entre " + names.dropLast(1).joinToString(", ") + " y " + names.last()
        else -> " · entre ${names.size} personas"
    }
    return "$owner $verb$who · $date"
}

/**
 * Saldos y liquidación.
 *
 * El plan no es solo informativo: cada pago se puede registrar de una vez, y al
 * hacerlo se crea la transferencia correspondiente. Como el plan sale del
 * balance, el pago registrado desaparece de la lista él solo.
 */
@Composable
private fun BalanceSection(vm: MainViewModel, t: Tricount) {
    val c = SmartTheme.colors
    val balances = remember(t) { Stats.balances(t) }
    val plan = remember(t) { Stats.settlementPlan(t) }
    val me = t.activeMembershipUuid

    // Lo que hay pendiente de confirmar: un pago suelto o el plan entero.
    var confirming by remember { mutableStateOf<List<SettlementLeg>>(emptyList()) }

    Column {
        SectionHeader("Saldo por persona")
        balances.entries.sortedByDescending { it.value }.forEach { (name, bal) ->
            val isMe = t.memberByUuid(me)?.displayName == name
            SmartRow(
                title = if (isMe) "$name · tú" else name,
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

        if (plan.isEmpty()) {
            SectionHeader("Para saldar cuentas")
            Text(
                "Nadie debe nada a nadie.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.secondaryText,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)
            )
        } else {
            SectionHeader("Para saldar cuentas") {
                if (!t.isArchived && plan.size > 1) {
                    Text(
                        "Saldar todo",
                        color = c.brand,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { confirming = plan }
                    )
                }
            }
            plan.forEach { leg ->
                SmartRow(
                    title = "${leg.fromName} → ${leg.toName}",
                    subtitle = if (t.isArchived) "Un solo pago" else "Toca para registrar el pago",
                    value = formatMoney(leg.amount, t.currency),
                    leading = { Initials(leg.fromName) },
                    onClick = if (t.isArchived) null else { { confirming = listOf(leg) } }
                )
                SmartDivider()
            }
        }
    }

    if (confirming.isNotEmpty()) {
        val legs = confirming
        val total = legs.sumOf { it.amount }
        ConfirmSheet(
            title = if (legs.size == 1) {
                "${legs[0].fromName} paga ${formatMoney(legs[0].amount, t.currency)} a ${legs[0].toName}"
            } else {
                "Saldar las cuentas del grupo"
            },
            body = if (legs.size == 1) {
                "Se añade como transferencia al grupo y los dos saldos se ajustan. " +
                    "Hazlo cuando el pago esté hecho de verdad."
            } else {
                "Se añaden ${legs.size} transferencias por " +
                    "${formatMoney(total, t.currency)} en total y el grupo queda a cero. " +
                    "Hazlo cuando los pagos estén hechos de verdad."
            },
            confirmLabel = if (legs.size == 1) "Registrar el pago" else "Registrar los ${legs.size} pagos",
            onDismiss = { confirming = emptyList() },
            onConfirm = { vm.settle(t, legs); confirming = emptyList() }
        )
    }
}

// ===========================================================================
// Hojas de gestión del grupo
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGroupChoiceSheet(
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    onCreate: () -> Unit
) {
    val c = SmartTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Añadir grupo",
                style = MaterialTheme.typography.titleLarge,
                color = c.primaryText,
                modifier = Modifier.padding(ScreenPadding)
            )
            SmartRow(
                title = "Crear uno nuevo",
                subtitle = "Con su nombre, su moneda y sus miembros",
                value = "›",
                onClick = onCreate
            )
            SmartDivider()
            SmartRow(
                title = "Pegar un enlace de Tricount",
                subtitle = "Para entrar en un grupo que ya existe",
                value = "›",
                onClick = onPaste
            )
            SmartDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupSheet(
    onDismiss: () -> Unit,
    onCreate: (String, String, List<String>) -> Unit
) {
    val c = SmartTheme.colors
    var title by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("EUR") }
    var names by remember { mutableStateOf(listOf("")) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(ScreenPadding)) {
                    Text("Crear grupo", style = MaterialTheme.typography.titleLarge, color = c.primaryText)
                    Spacer(Modifier.height(16.dp))
                    SmartField(title, { title = it }, "Nombre del grupo")
                    Spacer(Modifier.height(12.dp))
                    SmartField(currency, { currency = it.uppercase().take(3) }, "Moneda")
                }
            }
            item { SectionHeader("Miembros") }
            items(names.size) { i ->
                Column(Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)) {
                    SmartField(
                        names[i],
                        { value -> names = names.toMutableList().also { it[i] = value } },
                        "Nombre"
                    )
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                    Text(
                        "+ Añadir otro",
                        color = c.brand,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { names = names + "" }
                            .padding(vertical = 12.dp)
                    )
                }
            }
            item {
                Column(Modifier.padding(ScreenPadding)) {
                    Text(
                        "Tú ya cuentas como miembro; añade a los demás. También se pueden " +
                            "añadir después.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.secondaryText
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Crear", title.isNotBlank() && currency.length == 3) {
                        onCreate(title, currency, names.map { it.trim() }.filter { it.isNotEmpty() })
                    }
                }
            }
        }
    }
}

/**
 * Renombrar, emoji, miembros, ahorro, archivar y quitar. Todo lo que se le
 * puede hacer a un grupo, en un solo sitio, en vez de repartido entre la
 * cabecera y Ajustes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageGroupSheet(
    vm: MainViewModel,
    t: Tricount,
    savings: Boolean,
    onDismiss: () -> Unit
) {
    val c = SmartTheme.colors
    var renaming by remember { mutableStateOf(false) }
    var addingMember by remember { mutableStateOf(false) }
    var renamingMember by remember { mutableStateOf<Member?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val activeMembers = remember(t) { t.members.filter { it.status == "ACTIVE" } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    "Gestionar «${t.title}»",
                    style = MaterialTheme.typography.titleLarge,
                    color = c.primaryText,
                    modifier = Modifier.padding(ScreenPadding)
                )
            }
            item {
                SmartRow(
                    title = "Nombre y emoji",
                    subtitle = "${t.emoji ?: "sin emoji"} · ${t.title}",
                    value = "Cambiar",
                    valueColor = c.secondaryText,
                    onClick = { renaming = true }
                )
                SmartDivider()
                SmartRow(
                    title = if (savings) "Grupo de ahorro" else "Convertir en grupo de ahorro",
                    subtitle = if (savings) {
                        "Se lee como ingresos, gastos y balance"
                    } else {
                        "Lo que entre desde la fuente de ingresos contará como ingreso"
                    },
                    value = if (savings) "Deshacer" else "Convertir",
                    valueColor = if (savings) c.secondaryText else c.brand,
                    onClick = { vm.setSavings(t, !savings); onDismiss() }
                )
                SmartDivider()
            }

            item { SectionHeader("Miembros") }
            items(activeMembers, key = { "m-" + it.uuid }) { m ->
                SmartRow(
                    title = m.displayName,
                    subtitle = if (m.uuid == t.activeMembershipUuid) "Tú" else null,
                    value = "Renombrar",
                    valueColor = c.secondaryText,
                    leading = { Initials(m.displayName) },
                    onClick = { renamingMember = m }
                )
                SmartDivider()
            }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                    Text(
                        "+ Añadir miembro",
                        color = c.brand,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { addingMember = true }
                            .padding(vertical = 14.dp)
                    )
                }
                Text(
                    "Quitar a alguien hay que hacerlo desde la app oficial: la API de " +
                        "Tricount no lo permite por ninguna vía.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
                )
            }

            item { SectionHeader("Quitar de en medio") }
            item {
                SmartRow(
                    title = "Archivar",
                    subtitle = "Desaparece de la rejilla y se recupera desde ahí mismo",
                    value = "Archivar",
                    valueColor = c.secondaryText,
                    onClick = { confirmArchive = true }
                )
                SmartDivider()
                SmartRow(
                    title = "Quitar de SmartCount",
                    subtitle = "El grupo sigue existiendo para los demás",
                    value = "Quitar",
                    valueColor = c.negative,
                    onClick = { confirmRemove = true }
                )
                SmartDivider()
            }
        }
    }

    if (renaming) {
        TextPromptSheet(
            title = "Nombre del grupo",
            body = "El emoji va aparte: escríbelo en su campo si quieres cambiarlo.",
            initial = t.title,
            secondaryLabel = "Emoji",
            secondaryInitial = t.emoji.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { name, emoji ->
                if (name.trim() != t.title) vm.renameGroup(t, name)
                if (!emoji.isNullOrBlank() && emoji != t.emoji) vm.setGroupEmoji(t, emoji)
                renaming = false
            }
        )
    }

    if (addingMember) {
        TextPromptSheet(
            title = "Añadir miembro",
            body = "Se une al grupo para todos, igual que si lo añadieras desde Tricount.",
            initial = "",
            onDismiss = { addingMember = false },
            onConfirm = { name, _ -> vm.addMembers(t, listOf(name)); addingMember = false }
        )
    }

    renamingMember?.let { m ->
        TextPromptSheet(
            title = "Renombrar a «${m.displayName}»",
            body = "El cambio lo ve todo el grupo.",
            initial = m.displayName,
            onDismiss = { renamingMember = null },
            onConfirm = { name, _ -> vm.renameMember(t, m, name); renamingMember = null }
        )
    }

    if (confirmArchive) {
        ConfirmSheet(
            title = "Archivar «${t.title}»",
            body = "Se quita de la rejilla y deja de contar en las estadísticas. " +
                "Aparecerá abajo del todo, en Archivados, para recuperarlo cuando quieras.",
            confirmLabel = "Archivar",
            onDismiss = { confirmArchive = false },
            onConfirm = { vm.archiveGroup(t); confirmArchive = false; onDismiss() }
        )
    }

    if (confirmRemove) {
        ConfirmSheet(
            title = "Quitar «${t.title}» de SmartCount",
            body = "Desaparece de esta app. El grupo sigue existiendo en Tricount para el " +
                "resto, y puedes volver a entrar pegando su enlace.",
            confirmLabel = "Quitar",
            onDismiss = { confirmRemove = false },
            onConfirm = { vm.removeGroup(t); confirmRemove = false; onDismiss() }
        )
    }
}
