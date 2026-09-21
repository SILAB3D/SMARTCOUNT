package com.silab.smartcount.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.silab.smartcount.MainActivity
import com.silab.smartcount.R
import com.silab.smartcount.data.cache.CachedGroup
import com.silab.smartcount.data.cache.GroupCache
import com.silab.smartcount.data.db.InboxEntry
import java.util.Locale

/**
 * Avisa de cada movimiento detectado con una notificación accionable.
 *
 * Tres botones, que son las tres cosas que se hacen con un cargo recién
 * detectado: llevarlo al grupo normal en el que andas metido ahora mismo,
 * llevarlo al de ahorro, o decidirlo tú.
 *
 * **Los tres abren la app con el movimiento ya preparado; ninguno lo envía a
 * Tricount por su cuenta.** Antes el botón de un grupo creaba el movimiento
 * desde la pantalla de bloqueo y ofrecía deshacerlo treinta segundos. Era más
 * rápido, pero un cargo casi nunca llega listo para guardar —hay que mirar
 * entre quién se reparte, si la descripción del banco vale, si el importe es
 * el bueno— y lo que se ganaba en un toque se perdía luego corrigiendo desde
 * dentro. Ahora el botón elige el grupo y la app abre la hoja con esa
 * elección puesta.
 *
 * Son tres y no cuatro porque Android enseña **como mucho tres acciones**: la
 * cuarta se añade y no se ve. Silenciar un comercio se hace desde la hoja del
 * movimiento, que es donde se lee su nombre entero.
 */
object DetectionNotifier {

    const val CHANNEL_ID = "movimientos"
    const val EXTRA_ENTRY_ID = "entry_id"
    const val EXTRA_GROUP_ID = "group_id"
    const val ACTION_OPEN_INBOX = "com.silab.smartcount.OPEN_INBOX"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Movimientos detectados",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Bizum, transferencias y pagos que puedes llevar a un grupo"
            setShowBadge(true)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun titleFor(entry: InboxEntry): String {
        val amount = entry.amount?.let {
            String.format(Locale.getDefault(), "%.2f €", it)
        } ?: ""
        val who = (entry.merchant ?: entry.counterparty)?.let { " · $it" } ?: ""
        return "${entry.kind.label} $amount$who".trim()
    }

    fun bodyFor(entry: InboxEntry, groups: List<CachedGroup>): String = when {
        groups.isEmpty() -> "Añade un grupo en SmartCount para poder asignarlo"
        entry.concept != null -> "«${entry.concept}» · ¿a qué grupo lo llevas?"
        else -> "¿A qué grupo lo llevas?"
    }

    /**
     * Los dos grupos que se ofrecen: el normal y el de ahorro **más
     * recientes**.
     *
     * Reciente es el último en el que pasó algo, contando tanto su último
     * movimiento como el día en que entró en la app: un grupo recién añadido
     * todavía no tiene movimientos y es justo donde vas a querer llevar lo
     * siguiente. Antes se ofrecía el grupo activo y el que viniera después en
     * la lista, que no quiere decir nada.
     */
    fun candidateGroups(cache: GroupCache): List<CachedGroup> {
        val snap = cache.read()
        return listOfNotNull(snap.mostRecentNormal, snap.mostRecentSavings)
    }

    fun notify(context: Context, entry: InboxEntry) {
        ensureChannel(context)
        val groups = candidateGroups(GroupCache(context))

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleFor(entry))
            .setContentText(bodyFor(entry, groups))
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.rawText))
            .setContentIntent(openIntent(context, entry.id, null))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)

        // Solo el nombre del grupo: el prefijo «Ahorro: » se comía la mitad
        // del botón y dejaba el nombre cortado, que es justo lo único que
        // hace falta leer para elegir.
        groups.forEach { g ->
            builder.addAction(0, g.title.take(24), openIntent(context, entry.id, g.id))
        }
        builder.addAction(0, "Elegir…", openIntent(context, entry.id, null))

        NotificationManagerCompat.from(context)
            .notifySafely(context, entry.id.toInt(), builder.build())
    }

    /**
     * Abre la app en la bandeja, con este movimiento y —si el botón traía
     * uno— con ese grupo ya marcado.
     */
    private fun openIntent(context: Context, entryId: Long, groupId: Int?): PendingIntent {
        // Un requestCode distinto por botón: con el mismo, el segundo
        // PendingIntent reutilizaría los extras del primero y los tres
        // botones acabarían haciendo lo mismo.
        val requestCode = (entryId * 10 + (groupId?.rem(7)?.plus(1) ?: 0)).toInt()
        return PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_INBOX
                putExtra(EXTRA_ENTRY_ID, entryId)
                groupId?.let { putExtra(EXTRA_GROUP_ID, it) }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel(context: Context, entryId: Long) {
        NotificationManagerCompat.from(context).cancel(entryId.toInt())
    }
}

/** En Android 13+ publicar sin permiso lanza SecurityException; no queremos morir por eso. */
internal fun NotificationManagerCompat.notifySafely(
    context: Context,
    id: Int,
    notification: android.app.Notification
) {
    try {
        notify(id, notification)
    } catch (e: SecurityException) {
        // Falta el permiso POST_NOTIFICATIONS: el movimiento sigue en la bandeja.
    }
}
