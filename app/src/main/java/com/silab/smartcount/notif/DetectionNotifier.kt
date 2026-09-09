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
import com.silab.smartcount.data.db.DetectedKind
import com.silab.smartcount.data.db.InboxEntry
import java.util.Locale

/**
 * Avisa de cada movimiento detectado con una notificación accionable:
 * los grupos aparecen como botones, así que categorizar un Bizum es un solo
 * toque desde la pantalla de bloqueo, sin abrir la app.
 */
object DetectionNotifier {

    const val CHANNEL_ID = "movimientos"
    const val EXTRA_ENTRY_ID = "entry_id"
    const val EXTRA_GROUP_ID = "group_id"
    const val EXTRA_REMOTE_TX = "remote_tx"
    const val ACTION_OPEN_INBOX = "com.silab.smartcount.OPEN_INBOX"

    /** Cuántos grupos caben como botones sin saturar la notificación. */
    private const val MAX_GROUP_ACTIONS = 2

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

    /** Los grupos que se ofrecen como botón: el activo primero. */
    fun candidateGroups(cache: GroupCache): List<CachedGroup> {
        val snap = cache.read()
        val selected = snap.selected
        return (listOfNotNull(selected) + snap.groups.filter { it.id != selected?.id })
            .take(MAX_GROUP_ACTIONS)
    }

    fun notify(context: Context, entry: InboxEntry) {
        ensureChannel(context)
        val groups = candidateGroups(GroupCache(context))

        val open = PendingIntent.getActivity(
            context,
            entry.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_INBOX
                putExtra(EXTRA_ENTRY_ID, entry.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleFor(entry))
            .setContentText(bodyFor(entry, groups))
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.rawText))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)

        groups.forEach { g ->
            builder.addAction(
                0,
                g.title.take(18),
                quickAssignIntent(context, entry.id, g.id)
            )
        }
        builder.addAction(0, "Elegir…", open)
        // Silenciar el origen: la vía rápida para las suscripciones, que llegan
        // como un pago con tarjeta normal y solo se distinguen por el comercio.
        (entry.merchant ?: entry.counterparty)?.let { source ->
            builder.addAction(0, "No avisar de ${source.take(14)}", muteIntent(context, entry.id))
        }

        NotificationManagerCompat.from(context)
            .notifySafely(context, entry.id.toInt(), builder.build())
    }

    private fun muteIntent(context: Context, entryId: Long) =
        PendingIntent.getBroadcast(
            context,
            (entryId + 500_000).toInt(),
            Intent(context, QuickAssignReceiver::class.java).apply {
                action = QuickAssignReceiver.ACTION_MUTE
                putExtra(EXTRA_ENTRY_ID, entryId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun quickAssignIntent(context: Context, entryId: Long, groupId: Int) =
        PendingIntent.getBroadcast(
            context,
            (entryId * 100 + groupId).toInt(),
            Intent(context, QuickAssignReceiver::class.java).apply {
                action = QuickAssignReceiver.ACTION_ASSIGN
                putExtra(EXTRA_ENTRY_ID, entryId)
                putExtra(EXTRA_GROUP_ID, groupId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Sustituye la notificación por la confirmación, con opción de deshacer. */
    fun notifyAssigned(context: Context, entry: InboxEntry, group: CachedGroup, remoteTxId: Int) {
        val undo = PendingIntent.getBroadcast(
            context,
            -entry.id.toInt(),
            Intent(context, QuickAssignReceiver::class.java).apply {
                action = QuickAssignReceiver.ACTION_UNDO
                putExtra(EXTRA_ENTRY_ID, entry.id)
                putExtra(EXTRA_GROUP_ID, group.id)
                putExtra(EXTRA_REMOTE_TX, remoteTxId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Añadido a «${group.title}»")
            .setContentText(titleFor(entry))
            .addAction(0, "Deshacer", undo)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .build()
        NotificationManagerCompat.from(context).notifySafely(context, entry.id.toInt(), n)
    }

    /** Confirma el silenciado y deja deshacerlo, por si fue un toque sin querer. */
    fun notifyMuted(context: Context, entry: InboxEntry, source: String) {
        val undo = PendingIntent.getBroadcast(
            context,
            (entry.id + 600_000).toInt(),
            Intent(context, QuickAssignReceiver::class.java).apply {
                action = QuickAssignReceiver.ACTION_UNMUTE
                putExtra(EXTRA_ENTRY_ID, entry.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("«$source» silenciado")
            .setContentText("No volverá a avisar. Puedes reactivarlo en Ajustes.")
            .addAction(0, "Deshacer", undo)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .build()
        NotificationManagerCompat.from(context).notifySafely(context, entry.id.toInt(), n)
    }

    fun notifyError(context: Context, entry: InboxEntry, message: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("No se pudo enviar a Tricount")
            .setContentText(message.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notifySafely(context, entry.id.toInt(), n)
    }

    fun cancel(context: Context, entryId: Long) {
        NotificationManagerCompat.from(context).cancel(entryId.toInt())
    }
}

/** En Android 13+ publicar sin permiso lanza SecurityException; no queremos morir por eso. */
private fun NotificationManagerCompat.notifySafely(
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
