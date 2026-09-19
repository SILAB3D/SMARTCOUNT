package com.silab.smartcount.notif

import com.silab.smartcount.data.api.Member
import com.silab.smartcount.data.api.Tricount
import com.silab.smartcount.data.db.DetectedKind
import com.silab.smartcount.data.db.InboxEntry

/**
 * Con qué papeles llega un movimiento detectado a la hoja de asignación.
 *
 * Vivía dentro del receptor de la notificación, que creaba el movimiento por
 * su cuenta desde la pantalla de bloqueo. Ahora la notificación solo abre la
 * app con el grupo ya elegido y es la hoja la que enseña la propuesta, así que
 * esto dejó de ser una decisión final para convertirse en lo que siempre tuvo
 * más sentido que fuera: el punto de partida, visible y corregible.
 *
 * La regla es la forma del movimiento, no su nombre: un pago entre personas
 * —Bizum o transferencia— con alguien que está en el grupo es un reembolso de
 * uno a otro, porque eso es literalmente lo que pasó. Un pago con tarjeta, un
 * recibo, o un Bizum con alguien de fuera del grupo, es un gasto que alguien
 * adelantó y se reparte.
 */
object AssignPlan {

    sealed interface Plan {
        data class Reimbursement(val payer: Member, val receiver: Member) : Plan
        data class Expense(val payer: Member, val splitAmong: List<Member>) : Plan
    }

    private val PERSON_TO_PERSON = setOf(
        DetectedKind.BIZUM_SENT, DetectedKind.BIZUM_RECEIVED,
        DetectedKind.TRANSFER_SENT, DetectedKind.TRANSFER_RECEIVED
    )

    /**
     * El emparejamiento de nombres tolera que el banco diga «BEN TORRES» y el
     * grupo solo «Ben»; ignora tildes, mayúsculas y el orden de los apellidos.
     */
    fun memberNamed(t: Tricount, name: String?): Member? {
        if (name.isNullOrBlank()) return null
        val needle = name.trim().lowercase()
        val active = t.members.filter { it.status == "ACTIVE" }
        return active.firstOrNull { it.displayName.trim().lowercase() == needle }
            ?: active.firstOrNull { m ->
                val a = m.displayName.trim().lowercase()
                a.isNotEmpty() &&
                    (needle.startsWith("$a ") || a.startsWith("${needle.substringBefore(' ')} "))
            }
    }

    fun planFor(entry: InboxEntry, t: Tricount): Plan {
        val me = t.linkedMember ?: t.members.firstOrNull()
        val activeMembers = t.members.filter { it.status == "ACTIVE" }
        val other = memberNamed(t, entry.counterparty)

        return if (entry.kind in PERSON_TO_PERSON && me != null && other != null && other.uuid != me.uuid) {
            if (entry.kind.isMoneyIn) {
                Plan.Reimbursement(payer = other, receiver = me)
            } else {
                Plan.Reimbursement(payer = me, receiver = other)
            }
        } else {
            Plan.Expense(
                payer = me ?: activeMembers.firstOrNull() ?: t.members.first(),
                splitAmong = activeMembers
            )
        }
    }
}
