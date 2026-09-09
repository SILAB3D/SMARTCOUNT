package com.silab.smartcount.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.silab.smartcount.SmartCountApp
import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.cache.CachedGroup
import com.silab.smartcount.data.cache.GroupCache
import com.silab.smartcount.data.db.DetectedKind
import com.silab.smartcount.data.db.InboxEntry
import com.silab.smartcount.data.db.InboxStatus
import com.silab.smartcount.widget.SmartWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Ejecuta la asignación rápida desde el botón de la notificación, sin abrir
 * la app. Elige el tipo de movimiento por su forma:
 *
 *  - Bizum con una contraparte que coincide con un miembro → reembolso entre
 *    esas dos personas (que es lo que realmente ocurrió).
 *  - Cualquier otro caso → gasto repartido entre todos, pagado por ti.
 *
 * Siempre queda registrado en la bandeja como PUSHED, así que se puede
 * corregir después en la app, y la notificación ofrece "Deshacer" 30 segundos.
 */
class QuickAssignReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SmartCountApp ?: return
        val entryId = intent.getLongExtra(DetectionNotifier.EXTRA_ENTRY_ID, -1)
        val groupId = intent.getIntExtra(DetectionNotifier.EXTRA_GROUP_ID, -1)
        if (entryId < 0) return
        if (intent.action != ACTION_MUTE && groupId < 0) return

        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_MUTE -> mute(context, app, entryId)
                    ACTION_UNMUTE -> unmute(context, app, entryId)
                    ACTION_ASSIGN -> assign(context, app, entryId, groupId)
                    ACTION_UNDO -> undo(
                        context, app, entryId, groupId,
                        intent.getIntExtra(DetectionNotifier.EXTRA_REMOTE_TX, -1)
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Silencia el comercio o la persona de este movimiento: no volverá a
     * avisar ni a aparecer en la bandeja. Es la salida rápida para las
     * suscripciones, que llegan como un pago con tarjeta cualquiera.
     */
    private suspend fun mute(context: Context, app: SmartCountApp, entryId: Long) {
        val dao = app.database.inboxDao()
        val entry = dao.byId(entryId) ?: return
        val source = entry.merchant ?: entry.counterparty ?: return
        app.notificationRules.mute(source)
        dao.update(entry.copy(status = InboxStatus.IGNORED))
        DetectionNotifier.notifyMuted(context, entry, source)
        SmartWidgets.refresh(context)
    }

    private suspend fun unmute(context: Context, app: SmartCountApp, entryId: Long) {
        val dao = app.database.inboxDao()
        val entry = dao.byId(entryId) ?: return
        val source = entry.merchant ?: entry.counterparty ?: return
        app.notificationRules.unmute(source)
        dao.update(entry.copy(status = InboxStatus.PENDING))
        DetectionNotifier.notify(context, entry)
        SmartWidgets.refresh(context)
    }

    private suspend fun assign(context: Context, app: SmartCountApp, entryId: Long, groupId: Int) {
        val dao = app.database.inboxDao()
        val entry = dao.byId(entryId) ?: return
        val cached = GroupCache(context).read().groups.firstOrNull { it.id == groupId } ?: return
        val amount = entry.amount ?: run {
            DetectionNotifier.notifyError(context, entry, "El movimiento no tiene importe; ábrelo en la app")
            return
        }

        try {
            val tricount = app.client.getTricount(groupId)
            val plan = planFor(entry, cached, tricount)
            val remoteId = when (plan) {
                is Plan.Reimbursement -> app.client.createReimbursement(
                    tricount, plan.payer, plan.receiver, amount,
                    entry.concept ?: entry.counterparty ?: entry.kind.label,
                    Date(entry.detectedAt)
                )
                is Plan.Expense -> app.client.createExpense(
                    tricount,
                    entry.concept ?: entry.merchant ?: entry.counterparty ?: entry.kind.label,
                    amount, plan.payer, plan.splitAmong,
                    date = Date(entry.detectedAt)
                )
            }
            dao.update(
                entry.copy(
                    status = InboxStatus.PUSHED,
                    tricountId = groupId,
                    remoteTxId = remoteId
                )
            )
            DetectionNotifier.notifyAssigned(context, entry, cached, remoteId)
            SmartWidgets.refresh(context)
        } catch (e: Exception) {
            DetectionNotifier.notifyError(context, entry, e.message ?: "Error desconocido")
        }
    }

    private suspend fun undo(
        context: Context,
        app: SmartCountApp,
        entryId: Long,
        groupId: Int,
        remoteTxId: Int
    ) {
        val dao = app.database.inboxDao()
        val entry = dao.byId(entryId) ?: return
        try {
            if (remoteTxId >= 0) {
                app.client.deleteTransaction(app.client.getTricount(groupId), remoteTxId)
            }
            dao.update(entry.copy(status = InboxStatus.PENDING, tricountId = null, remoteTxId = null))
            DetectionNotifier.notify(context, entry)
            SmartWidgets.refresh(context)
        } catch (e: Exception) {
            DetectionNotifier.notifyError(context, entry, e.message ?: "No se pudo deshacer")
        }
    }

    /** Decide qué movimiento crear. Aislado para poder probarlo. */
    sealed interface Plan {
        data class Reimbursement(val payer: Member, val receiver: Member) : Plan
        data class Expense(val payer: Member, val splitAmong: List<Member>) : Plan
    }

    companion object {
        const val ACTION_ASSIGN = "com.silab.smartcount.ASSIGN"
        const val ACTION_UNDO = "com.silab.smartcount.UNDO"
        const val ACTION_MUTE = "com.silab.smartcount.MUTE"
        const val ACTION_UNMUTE = "com.silab.smartcount.UNMUTE"

        fun planFor(entry: InboxEntry, cached: CachedGroup, t: Tricount): Plan {
            val me = t.linkedMember ?: t.members.firstOrNull()
            val activeMembers = t.members.filter { it.status == "ACTIVE" }
            val other = cached.memberByName(entry.counterparty)
                ?.let { m -> t.memberByUuid(m.uuid) }

            // Un pago entre personas (Bizum o transferencia) con alguien del
            // grupo es un reembolso; un pago con tarjeta o un recibo es un gasto.
            val isPersonToPerson = entry.kind in setOf(
                DetectedKind.BIZUM_SENT, DetectedKind.BIZUM_RECEIVED,
                DetectedKind.TRANSFER_SENT, DetectedKind.TRANSFER_RECEIVED
            )

            return if (isPersonToPerson && me != null && other != null && other.uuid != me.uuid) {
                if (entry.kind.isMoneyIn) Plan.Reimbursement(payer = other, receiver = me)
                else Plan.Reimbursement(payer = me, receiver = other)
            } else {
                Plan.Expense(
                    payer = me ?: activeMembers.first(),
                    splitAmong = activeMembers
                )
            }
        }
    }
}
