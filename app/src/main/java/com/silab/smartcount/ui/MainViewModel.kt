package com.silab.smartcount.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silab.smartcount.SmartCountApp
import com.silab.smartcount.data.api.Category
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.SettlementLeg
import com.silab.smartcount.data.api.Transaction
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.api.TricountClient
import com.silab.smartcount.data.api.TxType
import com.silab.smartcount.data.db.DetectedKind
import com.silab.smartcount.data.db.InboxEntry
import com.silab.smartcount.data.db.InboxStatus
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
    val openSavingsId: Int? = null
) {
    val selected: Tricount? get() = tricounts.firstOrNull { it.id == selectedId }

    fun isSavings(id: Int?): Boolean = id != null && id in savingsIds

    /** Los grupos normales y los de ahorro, que en casi nada se parecen. */
    val normalGroups: List<Tricount> get() = tricounts.filterNot { isSavings(it.id) }

    val savingsGroups: List<Tricount> get() = tricounts.filter { isSavings(it.id) }
}

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

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Señales que llegan desde los widgets y las notificaciones. */
    private val _newExpenseRequests = MutableStateFlow(0)
    val newExpenseRequests: StateFlow<Int> = _newExpenseRequests.asStateFlow()

    private val _focusedEntry = MutableStateFlow<Long?>(null)
    val focusedEntry: StateFlow<Long?> = _focusedEntry.asStateFlow()

    /** Las cifras de un grupo de ahorro, con la fuente de ingresos elegida. */
    fun savingsSummary(t: Tricount): SavingsSummary = savings.summary(t)

    /** El total de todos los grupos de ahorro juntos. */
    fun savingsTotal(groups: List<Tricount>): SavingsSummary =
        groups.fold(SavingsSummary.ZERO) { acc, t -> acc + savings.summary(t) }

    fun incomeMember(t: Tricount): Member? = savings.incomeMember(t)

    /** ¿Este movimiento entra al grupo (verde) o sale de él (rojo)? */
    fun isIncome(t: Tricount, tx: Transaction): Boolean = savings.isIncome(t, tx)

    fun requestNewExpense() { _newExpenseRequests.value += 1 }

    /** La señal es de un solo uso: quien abre la hoja de alta la apaga. */
    fun consumeNewExpense() { _newExpenseRequests.value = 0 }
    fun focusInboxEntry(id: Long?) { _focusedEntry.value = id }

    val inbox: StateFlow<List<InboxEntry>> =
        dao.observeByStatus(InboxStatus.PENDING)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        _state.value = _state.value.copy(savingsIds = savings.ids())
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

    /** Añade un grupo pegando su enlace público de Tricount. */
    fun addByLink(link: String) = launchGuarded {
        val token = TricountClient.extractPublicToken(link)
            ?: throw IllegalArgumentException("No reconozco ese enlace de Tricount")
        _state.value = _state.value.copy(loading = true)
        val joined = client.joinTricount(token)
        val list = client.listTricounts()
        _state.value = _state.value.copy(
            loading = false,
            tricounts = list,
            selectedId = joined.id,
            message = "Grupo «${joined.title}» añadido"
        )
    }

    fun addExpense(
        tricount: Tricount,
        description: String,
        amount: Double,
        payer: Member,
        splitAmong: List<Member>,
        category: Category?,
        date: Date = Date()
    ) = launchGuarded {
        client.createExpense(tricount, description, amount, payer, splitAmong, category, date = date)
        _state.value = _state.value.copy(message = "Gasto añadido")
        refreshQuiet()
    }

    /** Alta de cualquiera de los tres tipos, según lo que traiga la hoja. */
    fun addMovement(tricount: Tricount, draft: MovementDraft) = when (draft.kind) {
        TxType.NORMAL -> addExpense(
            tricount, draft.description, draft.amount, draft.owner, draft.splitAmong, draft.category
        )
        TxType.INCOME -> addIncome(
            tricount, draft.description, draft.amount, draft.owner, draft.splitAmong, draft.category
        )
        TxType.BALANCE -> addTransfer(
            tricount, draft.description, draft.amount, draft.owner,
            draft.counterpart ?: draft.owner
        )
    }

    /** Ingreso: dinero que entra al grupo (tipo INCOME). */
    fun addIncome(
        tricount: Tricount,
        description: String,
        amount: Double,
        receiver: Member,
        splitAmong: List<Member>,
        category: Category?,
        date: Date = Date()
    ) = launchGuarded {
        client.createIncome(tricount, description, amount, receiver, splitAmong, category, date = date)
        _state.value = _state.value.copy(message = "Ingreso añadido")
        refreshQuiet()
    }

    /** Transferencia entre dos miembros: el tipo BALANCE de Tricount. */
    fun addTransfer(
        tricount: Tricount,
        description: String,
        amount: Double,
        from: Member,
        to: Member,
        date: Date = Date()
    ) = launchGuarded {
        require(from.uuid != to.uuid) { "Una transferencia necesita dos personas distintas" }
        client.createReimbursement(tricount, from, to, amount, description, date = date)
        _state.value = _state.value.copy(message = "Transferencia añadida")
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

    fun editExpense(
        tricount: Tricount,
        tx: Transaction,
        description: String,
        amount: Double,
        payer: Member,
        splitAmong: List<Member>,
        category: Category?
    ) = launchGuarded {
        client.editTransaction(
            tricount, tx,
            description = description,
            amount = amount,
            payer = payer,
            splitAmong = splitAmong,
            category = category
        )
        _state.value = _state.value.copy(message = "Gasto actualizado")
        refreshQuiet()
    }

    fun deleteExpense(tricount: Tricount, tx: Transaction) = launchGuarded {
        val id = tx.id ?: throw IllegalStateException("Gasto sin id")
        client.deleteTransaction(tricount, id)
        _state.value = _state.value.copy(message = "Gasto eliminado")
        refreshQuiet()
    }

    // -----------------------------------------------------------------------
    // Bandeja de Bizum
    // -----------------------------------------------------------------------

    /**
     * Envía un movimiento detectado a un grupo.
     * - Bizum a otra persona del grupo → reembolso (tipo BALANCE).
     * - Bizum/pago a un tercero → gasto repartido.
     */
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
        val receiverOrSplit: List<Member>
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
            lastId = if (target.asReimbursement) {
                val receiver = target.receiverOrSplit.firstOrNull()
                    ?: throw IllegalArgumentException("Elige quién recibe el dinero en «${t.title}»")
                client.createReimbursement(
                    t, target.payer, receiver, amount, description, Date(entry.detectedAt)
                )
            } else {
                client.createExpense(
                    t, description, amount, target.payer, target.receiverOrSplit,
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
                userMovement = true
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
     * Calibración: marcar una notificación como movimiento bancario o como que
     * no lo es. No borra nada — solo cambia de grupo en la bandeja, para poder
     * volver atrás si la decisión fue equivocada.
     */
    fun setBankMovement(entry: InboxEntry, isMovement: Boolean) = launchGuarded {
        dao.update(entry.copy(userMovement = isMovement))
        cache.savePendingCount(dao.countPending())
        SmartWidgets.refresh(appCtx)
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

    /** Sugerencia de si un movimiento parece un reembolso entre miembros. */
    fun looksLikeReimbursement(entry: InboxEntry): Boolean = entry.kind in setOf(
        DetectedKind.BIZUM_SENT, DetectedKind.BIZUM_RECEIVED,
        DetectedKind.TRANSFER_SENT, DetectedKind.TRANSFER_RECEIVED
    )

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
