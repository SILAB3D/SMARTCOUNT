package com.silab.smartcount.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silab.smartcount.SmartCountApp
import com.silab.smartcount.data.api.Category
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.Transaction
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.api.TricountClient
import com.silab.smartcount.data.db.DetectedKind
import com.silab.smartcount.data.db.InboxEntry
import com.silab.smartcount.data.db.InboxStatus
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
    val message: String? = null
) {
    val selected: Tricount? get() = tricounts.firstOrNull { it.id == selectedId }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app as SmartCountApp
    private val client: TricountClient get() = appCtx.client
    private val dao = appCtx.database.inboxDao()
    private val cache = appCtx.groupCache

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Señales que llegan desde los widgets y las notificaciones. */
    private val _newExpenseRequests = MutableStateFlow(0)
    val newExpenseRequests: StateFlow<Int> = _newExpenseRequests.asStateFlow()

    private val _focusedEntry = MutableStateFlow<Long?>(null)
    val focusedEntry: StateFlow<Long?> = _focusedEntry.asStateFlow()

    fun requestNewExpense() { _newExpenseRequests.value += 1 }
    fun focusInboxEntry(id: Long?) { _focusedEntry.value = id }

    val inbox: StateFlow<List<InboxEntry>> =
        dao.observeByStatus(InboxStatus.PENDING)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { refresh() }

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
    ) = launchGuarded {
        val remoteId = if (asReimbursement) {
            val receiver = receiverOrSplit.firstOrNull()
                ?: throw IllegalArgumentException("Elige quién recibe el dinero")
            client.createReimbursement(tricount, payer, receiver, amount, description, Date(entry.detectedAt))
        } else {
            client.createExpense(
                tricount, description, amount, payer, receiverOrSplit,
                category, date = Date(entry.detectedAt)
            )
        }
        dao.update(
            entry.copy(
                status = InboxStatus.PUSHED,
                tricountId = tricount.id,
                remoteTxId = remoteId,
                amount = amount
            )
        )
        DetectionNotifier.cancel(appCtx, entry.id)
        _state.value = _state.value.copy(message = "Enviado a «${tricount.title}»")
        refreshQuiet()
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
