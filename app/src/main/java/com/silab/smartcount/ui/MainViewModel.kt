package com.silab.smartcount.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silab.smartcount.SmartCountApp
import com.silab.smartcount.data.api.Category
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.SettlementLeg
import com.silab.smartcount.data.api.Split
import com.silab.smartcount.data.api.Transaction
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.api.TricountClient
import com.silab.smartcount.data.api.TxType
import com.silab.smartcount.data.db.InboxClass
import com.silab.smartcount.data.db.InboxEntry
import com.silab.smartcount.data.db.InboxStatus
import com.silab.smartcount.data.repo.ArchivedGroups
import com.silab.smartcount.data.repo.Savings
import com.silab.smartcount.data.repo.SavingsSummary
import com.silab.smartcount.notif.DetectionNotifier
import com.silab.smartcount.widget.SmartWidgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date

data class UiState(
    val loading: Boolean = false,
    val tricounts: List<Tricount> = emptyList(),
    val selectedId: Int? = null,
    val error: String? = null,
    val message: String? = null,
    /** Grupos marcados como grupos de ahorro. La marca es local a este móvil. */
    val savingsIds: Set<Int> = emptySet(),
    /**
     * Qué grupo hay abierto en cada pestaña que tiene rejilla. Son dos y no uno
     * porque Grupos y Ahorro se navegan por separado: volver de un grupo de
     * ahorro tiene que devolverte a la rejilla de ahorro, no a la de grupos.
     */
    val openGroupId: Int? = null,
    val openSavingsId: Int? = null,
    /** Los archivados, que la API ya no devuelve y solo viven anotados aquí. */
    val archived: List<ArchivedGroups.Entry> = emptyList()
) {
    val selected: Tricount? get() = tricounts.firstOrNull { it.id == selectedId }

    fun isSavings(id: Int?): Boolean = id != null && id in savingsIds

    /** Los grupos normales y los de ahorro, que en casi nada se parecen. */
    val normalGroups: List<Tricount> get() = tricounts.filterNot { isSavings(it.id) }

    val savingsGroups: List<Tricount> get() = tricounts.filter { isSavings(it.id) }
}

/** Qué movimiento de la bandeja hay que abrir, y con qué grupo ya elegido. */
data class InboxFocus(val entryId: Long, val groupId: Int? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Descripción de los movimientos que crea la liquidación. */
        const val SETTLEMENT_DESCRIPTION = "Liquidación"
    }

    private val appCtx = app as SmartCountApp
    private val client: TricountClient get() = appCtx.client
    private val dao = appCtx.database.inboxDao()
    private val cache = appCtx.groupCache
    private val savings = appCtx.savingsGroups
    private val identity = appCtx.memberIdentity
    private val archive = appCtx.archivedGroups
    private val registry = appCtx.bankRegistry

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Señales que llegan desde los widgets y las notificaciones. */
    private val _newExpenseRequests = MutableStateFlow(0)
    val newExpenseRequests: StateFlow<Int> = _newExpenseRequests.asStateFlow()

    private val _focusedEntry = MutableStateFlow<InboxFocus?>(null)
    val focusedEntry: StateFlow<InboxFocus?> = _focusedEntry.asStateFlow()

    /** Las cifras de un grupo de ahorro, con la fuente de ingresos elegida. */
    fun savingsSummary(t: Tricount): SavingsSummary = savings.summary(t)

    /** El total de todos los grupos de ahorro juntos. */
    fun savingsTotal(groups: List<Tricount>): SavingsSummary =
        groups.fold(SavingsSummary.ZERO) { acc, t -> acc + savings.summary(t) }

    fun incomeMember(t: Tricount): Member? = savings.incomeMember(t)

    /** Quién saca el dinero en un grupo de ahorro. */
    fun spenderMember(t: Tricount): Member? = savings.spenderMember(t)

    /** ¿Este movimiento entra al grupo (verde) o sale de él (rojo)? */
    fun isIncome(t: Tricount, tx: Transaction): Boolean = savings.isIncome(t, tx)

    fun requestNewExpense() { _newExpenseRequests.value += 1 }

    /** La señal es de un solo uso: quien abre la hoja de alta la apaga. */
    fun consumeNewExpense() { _newExpenseRequests.value = 0 }
    fun focusInboxEntry(focus: InboxFocus?) { _focusedEntry.value = focus }

    val inbox: StateFlow<List<InboxEntry>> =
        dao.observeByStatus(InboxStatus.PENDING)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lo ya enviado, para poder mirar atrás sin salir de la bandeja. */
    val inboxHistory: StateFlow<List<InboxEntry>> =
        dao.observeHistory()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        _state.value = _state.value.copy(savingsIds = savings.ids(), archived = archive.all())
        refresh()
    }

    fun refresh() = launchGuarded {
        _state.value = _state.value.copy(loading = true, error = null)
        val list = client.listTricounts()
        val selected = _state.value.selectedId ?: list.firstOrNull()?.id
        _state.value = _state.value.copy(
            loading = false,
            tricounts = list,
            selectedId = selected
        )
        syncCache(list, selected)
    }

    fun select(id: Int) {
        _state.value = _state.value.copy(selectedId = id)
        viewModelScope.launch { syncCache(_state.value.tricounts, id) }
    }

    /** Abre un grupo desde la rejilla de Grupos; null vuelve a la rejilla. */
    fun openGroup(id: Int?) {
        _state.value = _state.value.copy(openGroupId = id, selectedId = id ?: _state.value.selectedId)
        if (id != null) viewModelScope.launch { syncCache(_state.value.tricounts, id) }
    }

    /** Lo mismo desde la pestaña de Ahorro. */
    fun openSavings(id: Int?) {
        _state.value = _state.value.copy(openSavingsId = id, selectedId = id ?: _state.value.selectedId)
        if (id != null) viewModelScope.launch { syncCache(_state.value.tricounts, id) }
    }

    /** Mantiene al día lo que leen el widget y la notificación. */
    private suspend fun syncCache(list: List<Tricount>, selectedId: Int?) {
        runCatching {
            cache.saveGroups(list, selectedId)
            cache.savePendingCount(dao.countPending())
            SmartWidgets.refresh(appCtx)
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, message = null)
    }

    // -----------------------------------------------------------------------
    // Grupos
    // -----------------------------------------------------------------------

    /** Añade un grupo pegando su enlace público de Tricount. */
    fun addByLink(link: String) = launchGuarded {
        val token = TricountClient.extractPublicToken(link)
            ?: throw IllegalArgumentException("No reconozco ese enlace de Tricount")
        _state.value = _state.value.copy(loading = true)
        val joined = client.joinTricount(token)
        archive.forget(token)
        val list = client.listTricounts()
        _state.value = _state.value.copy(
            loading = false,
            tricounts = list,
            selectedId = joined.id,
            archived = archive.all(),
            message = "Grupo «${joined.title}» añadido"
        )
        syncCache(list, joined.id)
    }

    /** Crea un grupo nuevo, con sus miembros si los hay. */
    fun createGroup(title: String, currency: String, memberNames: List<String>) = launchGuarded {
        require(title.isNotBlank()) { "El grupo necesita un nombre" }
        _state.value = _state.value.copy(loading = true)
        val id = client.createTricount(title, currency, memberNames = memberNames)
        val list = client.listTricounts()
        _state.value = _state.value.copy(
            loading = false,
            tricounts = list,
            selectedId = id,
            openGroupId = id,
            message = "Grupo «$title» creado"
        )
        syncCache(list, id)
    }

    fun renameGroup(tricount: Tricount, title: String) = launchGuarded {
        client.updateTricount(tricount, title = title)
        _state.value = _state.value.copy(message = "Ahora se llama «${title.trim()}»")
        refreshQuiet()
    }

    fun setGroupEmoji(tricount: Tricount, emoji: String) = launchGuarded {
        client.updateTricount(tricount, emoji = emoji)
        refreshQuiet()
    }

    fun addMembers(tricount: Tricount, names: List<String>) = launchGuarded {
        client.addMembers(tricount, names)
        _state.value = _state.value.copy(
            message = if (names.size == 1) "«${names.first()}» está dentro" else "${names.size} miembros añadidos"
        )
        refreshQuiet()
    }

    fun renameMember(tricount: Tricount, member: Member, name: String) = launchGuarded {
        client.renameMember(tricount, member, name)
        _state.value = _state.value.copy(message = "Ahora es «${name.trim()}»")
        refreshQuiet()
    }

    /**
     * Archiva el grupo. Se anota antes de archivarlo porque después la API ya
     * no lo devuelve: sin el enlace guardado aquí no habría forma de volver.
     */
    fun archiveGroup(tricount: Tricount) = launchGuarded {
        archive.remember(tricount)
        client.setArchived(tricount, true)
        val list = client.listTricounts()
        _state.value = _state.value.copy(
            tricounts = list,
            openGroupId = null,
            openSavingsId = null,
            archived = archive.all(),
            message = "«${tricount.title}» archivado"
        )
        syncCache(list, _state.value.selectedId)
    }

    fun restoreGroup(entry: ArchivedGroups.Entry) = launchGuarded {
        client.restoreArchived(entry.token)
        archive.forget(entry.token)
        val list = client.listTricounts()
        _state.value = _state.value.copy(
            tricounts = list,
            archived = archive.all(),
            message = "«${entry.title}» vuelve a estar a la vista"
        )
        syncCache(list, _state.value.selectedId)
    }

    /** Lo quita de esta app. El grupo sigue existiendo para los demás. */
    fun removeGroup(tricount: Tricount) = launchGuarded {
        client.unsyncTricount(tricount)
        savings.mark(tricount.id, false)
        val list = client.listTricounts()
        _state.value = _state.value.copy(
            tricounts = list,
            savingsIds = savings.ids(),
            openGroupId = null,
            openSavingsId = null,
            message = "«${tricount.title}» ya no está en SmartCount"
        )
        syncCache(list, _state.value.selectedId)
    }

    // -----------------------------------------------------------------------
    // Movimientos
    // -----------------------------------------------------------------------

    /** Alta de cualquiera de los tres tipos, según lo que traiga la hoja. */
    fun addMovement(tricount: Tricount, draft: MovementDraft) = launchGuarded {
        when (draft.kind) {
            TxType.NORMAL -> client.createExpense(
                tricount, draft.description, draft.amount, draft.owner,
                effectiveSplit(tricount, draft), draft.category, date = draft.date
            )
            TxType.INCOME -> client.createIncome(
                tricount, draft.description, draft.amount, draft.owner,
                effectiveSplit(tricount, draft), draft.category, date = draft.date
            )
            TxType.BALANCE -> client.createReimbursement(
                tricount, draft.owner,
                draft.counterpart ?: throw IllegalArgumentException("Elige a quién va el dinero"),
                draft.amount, draft.description, draft.date
            )
        }
        _state.value = _state.value.copy(message = "${draft.kind.label} añadido")
        refreshQuiet()
    }

    fun editMovement(tricount: Tricount, tx: Transaction, draft: MovementDraft) = launchGuarded {
        client.editTransaction(
            tricount, tx,
            description = draft.description,
            amount = draft.amount,
            owner = draft.owner,
            split = if (tx.type == TxType.BALANCE) null else effectiveSplit(tricount, draft),
            counterpart = draft.counterpart,
            category = draft.category,
            date = draft.date
        )
        _state.value = _state.value.copy(message = "${draft.kind.label} actualizado")
        refreshQuiet()
    }

    /**
     * En un grupo de ahorro los dos papeles mandan sobre lo que traiga la
     * hoja: un gasto es de quien gasta hacia quien gasta, y un ingreso va de
     * la fuente de ingresos hacia quien gasta. Se impone aquí, en el único
     * sitio por el que pasan todas las altas, y no en cada pantalla: así lo
     * cumplen también la bandeja y la notificación, que antes creaban en un
     * grupo de ahorro un gasto repartido como en cualquier otro grupo.
     */
    private fun effectiveSplit(tricount: Tricount, draft: MovementDraft): Split {
        if (!savings.isSavings(tricount.id)) return draft.split
        val spender = savings.spenderMember(tricount)
            ?: throw IllegalStateException("Falta decir quién gasta en «${tricount.title}»")
        return Split(listOf(spender))
    }

    /** Quién es el propietario que corresponde en un grupo de ahorro. */
    fun savingsOwner(tricount: Tricount, income: Boolean): Member? =
        if (income) savings.incomeMember(tricount) else savings.spenderMember(tricount)

    fun deleteExpense(tricount: Tricount, tx: Transaction) = launchGuarded {
        val id = tx.id ?: throw IllegalStateException("El movimiento no tiene id")
        client.deleteTransaction(tricount, id)
        _state.value = _state.value.copy(message = "Movimiento eliminado")
        refreshQuiet()
    }

    /**
     * Liquidación: convierte los pagos que propone el plan en transferencias
     * reales, para que los saldos vuelvan a cero.
     *
     * Se registra como transferencia (tipo BALANCE) y no como un apunte aparte
     * porque el plan es un cálculo, no un dato: en cuanto el pago existe como
     * movimiento, el balance se recalcula solo y el pago desaparece del plan.
     * Así también lo ve el resto del grupo desde la app oficial de Tricount.
     *
     * Los pagos se crean uno a uno y en orden. Si uno falla, los anteriores
     * quedan hechos: son movimientos válidos por sí mismos, y el plan que
     * queda después ya solo propone lo que falte.
     */
    fun settle(tricount: Tricount, legs: List<SettlementLeg>) = launchGuarded {
        require(legs.isNotEmpty()) { "No hay nada que saldar" }
        legs.forEach { leg ->
            val from = tricount.memberByUuid(leg.fromUuid)
                ?: throw IllegalStateException("«${leg.fromName}» ya no está en el grupo")
            val to = tricount.memberByUuid(leg.toUuid)
                ?: throw IllegalStateException("«${leg.toName}» ya no está en el grupo")
            client.createReimbursement(tricount, from, to, leg.amount, SETTLEMENT_DESCRIPTION)
        }
        _state.value = _state.value.copy(
            message = if (legs.size == 1) {
                "Saldado: ${legs.first().fromName} → ${legs.first().toName}"
            } else {
                "${legs.size} pagos registrados · cuentas en paz"
            }
        )
        refreshQuiet()
    }

    /**
     * Fija quién eres tú en un grupo. Hace falta cuando la API no lo dice —
     * pasa en los grupos a los que esta instalación se unió por enlace — y sin
     * ello el balance que se enseña en grande es un 0,00 que no significa nada.
     */
    fun setMyMember(tricount: Tricount, member: Member) = launchGuarded {
        identity.set(tricount.id, member.uuid)
        _state.value = _state.value.copy(
            tricounts = _state.value.tricounts.map {
                if (it.id == tricount.id) it.copy(activeMembershipUuid = member.uuid) else it
            },
            message = "Eres «${member.displayName}» en «${tricount.title}»"
        )
        syncCache(_state.value.tricounts, _state.value.selectedId)
    }

    /** Quién es la fuente de ingresos de un grupo de ahorro. */
    fun setIncomeMember(tricount: Tricount, member: Member) = launchGuarded {
        savings.setIncomeMember(tricount.id, member.uuid)
        _state.value = _state.value.copy(
            savingsIds = savings.ids(),
            message = "Los ingresos de «${tricount.title}» vienen de «${member.displayName}»"
        )
        syncCache(_state.value.tricounts, _state.value.selectedId)
    }

    /** Y quién saca el dinero. */
    fun setSpenderMember(tricount: Tricount, member: Member) = launchGuarded {
        savings.setSpenderMember(tricount.id, member.uuid)
        _state.value = _state.value.copy(
            savingsIds = savings.ids(),
            message = "Los gastos de «${tricount.title}» van a nombre de «${member.displayName}»"
        )
        syncCache(_state.value.tricounts, _state.value.selectedId)
    }

    // -----------------------------------------------------------------------
    // Grupos de ahorro
    // -----------------------------------------------------------------------

    /**
     * Convierte un grupo en grupo de ahorro y al revés. Al activarlo se crea el
     * miembro *Ingresos* si no existe: sin él no hay de dónde venga el dinero.
     */
    fun setSavings(tricount: Tricount, enabled: Boolean) = launchGuarded {
        // Solo se crea el miembro si no hay ninguna fuente de ingresos, ni por
        // nombre ni elegida a mano: un grupo que ya trae su «Ingreso» en
        // singular cumple la convención y añadirle otro lo rompería.
        if (enabled && savings.incomeMember(tricount) == null) {
            client.addMembers(tricount, listOf(Savings.INCOME_MEMBER))
        }
        savings.mark(tricount.id, enabled)
        _state.value = _state.value.copy(
            savingsIds = savings.ids(),
            message = if (enabled) {
                "«${tricount.title}» es ahora un grupo de ahorro"
            } else {
                "«${tricount.title}» vuelve a ser un grupo normal"
            }
        )
        refreshQuiet()
    }

    // -----------------------------------------------------------------------
    // Bandeja
    // -----------------------------------------------------------------------

    /**
     * A qué grupo va el movimiento y con qué papeles. Un mismo cargo puede ir a
     * varios grupos a la vez — el recibo de la luz al piso y al de ahorro — y
     * cada uno tiene sus miembros, así que el reparto se decide grupo a grupo y
     * no una vez para todos.
     */
    data class TargetGroup(
        val tricount: Tricount,
        val asReimbursement: Boolean,
        val payer: Member,
        val receiverOrSplit: List<Member>,
        /**
         * Las partes puestas a mano, por uuid. Vacío = a partes iguales, que
         * es lo que hace el servidor con las asignaciones `RATIO`. Va por
         * grupo y no una vez para todos porque los miembros de uno no son los
         * del otro.
         */
        val fixed: Map<String, Double> = emptyMap()
    )

    fun pushInboxEntry(
        entry: InboxEntry,
        tricount: Tricount,
        asReimbursement: Boolean,
        payer: Member,
        receiverOrSplit: List<Member>,
        description: String,
        amount: Double,
        category: Category?
    ) = pushInboxEntry(
        entry, listOf(TargetGroup(tricount, asReimbursement, payer, receiverOrSplit)),
        description, amount, category
    )

    fun pushInboxEntry(
        entry: InboxEntry,
        targets: List<TargetGroup>,
        description: String,
        amount: Double,
        category: Category?
    ) = launchGuarded {
        require(targets.isNotEmpty()) { "Elige al menos un grupo" }
        var lastId: Int? = null
        var lastGroup: Tricount? = null
        targets.forEach { target ->
            val t = target.tricount
            lastId = when {
                // En un grupo de ahorro mandan los papeles del grupo, no lo
                // que dijera la hoja: un cargo del banco es dinero que sale.
                savings.isSavings(t.id) -> {
                    val spender = savings.spenderMember(t)
                        ?: throw IllegalStateException("Falta decir quién gasta en «${t.title}»")
                    val income = entry.kind.isMoneyIn
                    if (income) {
                        val from = savings.incomeMember(t)
                            ?: throw IllegalStateException("Falta la fuente de ingresos de «${t.title}»")
                        client.createIncome(
                            t, description, amount, from, Split(listOf(spender)),
                            category, date = Date(entry.detectedAt)
                        )
                    } else {
                        client.createExpense(
                            t, description, amount, spender, Split(listOf(spender)),
                            category, date = Date(entry.detectedAt)
                        )
                    }
                }
                target.asReimbursement -> {
                    val receiver = target.receiverOrSplit.firstOrNull()
                        ?: throw IllegalArgumentException("Elige quién recibe el dinero en «${t.title}»")
                    client.createReimbursement(
                        t, target.payer, receiver, amount, description, Date(entry.detectedAt)
                    )
                }
                else -> client.createExpense(
                    t, description, amount, target.payer,
                    Split(target.receiverOrSplit, target.fixed.filterKeys { uuid ->
                        target.receiverOrSplit.any { it.uuid == uuid }
                    }),
                    category, date = Date(entry.detectedAt)
                )
            }
            lastGroup = t
        }
        // La entrada guarda el último destino: es lo que "Deshacer" puede
        // revertir sin ambigüedad. Los demás quedan creados y se corrigen en
        // su grupo, que es donde se ven.
        dao.update(
            entry.copy(
                status = InboxStatus.PUSHED,
                tricountId = lastGroup?.id,
                remoteTxId = lastId,
                amount = amount,
                userClass = InboxClass.BANK
            )
        )
        DetectionNotifier.cancel(appCtx, entry.id)
        _state.value = _state.value.copy(
            message = if (targets.size == 1) {
                "Enviado a «${lastGroup?.title}»"
            } else {
                "Enviado a ${targets.size} grupos"
            }
        )
        refreshQuiet()
    }

    /**
     * Mueve una notificación de cajón.
     *
     * Los dos saltos que tocan la vigilancia lo hacen a propósito: lo que
     * marcas como movimiento bancario trae consigo a su app —si notificó uno,
     * notificará más—, y lo que marcas como no bancario la apaga, que es la
     * única forma de que deje de aparecer. Las dos cosas se deshacen en
     * Ajustes.
     */
    fun classifyInboxEntry(entry: InboxEntry, target: InboxClass) = launchGuarded {
        dao.update(entry.copy(userClass = target))
        when (target) {
            InboxClass.BANK -> registry.add(entry.sourcePackage)
            InboxClass.NON_BANK -> registry.stopWatching(entry.sourcePackage)
            InboxClass.OTHER -> Unit
        }
        cache.savePendingCount(dao.countPending())
        SmartWidgets.refresh(appCtx)
        _state.value = _state.value.copy(
            message = when (target) {
                InboxClass.BANK -> "Se vigilará «${registry.label(entry.sourcePackage)}»"
                InboxClass.NON_BANK -> "Se deja de seguir «${registry.label(entry.sourcePackage)}»"
                InboxClass.OTHER -> "Movido a otros eventos"
            }
        )
    }

    fun ignoreInboxEntry(entry: InboxEntry) = launchGuarded {
        dao.update(entry.copy(status = InboxStatus.IGNORED))
        DetectionNotifier.cancel(appCtx, entry.id)
        syncCache(_state.value.tricounts, _state.value.selectedId)
    }

    fun deleteInboxEntry(entry: InboxEntry) = launchGuarded { dao.delete(entry.id) }

    /** Silencia el comercio o la persona: no volverá a avisar ni a la bandeja. */
    fun muteSource(entry: InboxEntry, source: String) = launchGuarded {
        appCtx.notificationRules.mute(source)
        dao.update(entry.copy(status = InboxStatus.IGNORED))
        DetectionNotifier.cancel(appCtx, entry.id)
        _state.value = _state.value.copy(message = "«$source» silenciado")
        syncCache(_state.value.tricounts, _state.value.selectedId)
    }

    // -----------------------------------------------------------------------

    private suspend fun refreshQuiet() {
        runCatching { client.listTricounts() }.onSuccess { list ->
            _state.value = _state.value.copy(tricounts = list)
            syncCache(list, _state.value.selectedId)
        }
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Error desconocido"
                )
            }
        }
    }
}
