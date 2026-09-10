package com.silab.smartcount.notif

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.silab.smartcount.SmartCountApp
import com.silab.smartcount.data.cache.GroupCache
import com.silab.smartcount.widget.SmartWidgets
import com.silab.smartcount.data.db.Confidence
import com.silab.smartcount.data.db.DetectedKind
import com.silab.smartcount.data.db.InboxEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Escucha las notificaciones de las apps bancarias y guarda los movimientos
 * detectados en la bandeja de entrada local.
 *
 * Requiere que el usuario conceda "Acceso a notificaciones" manualmente
 * (Ajustes del sistema → Notificaciones → Acceso a notificaciones).
 * Nada sale del dispositivo: el envío a Tricount lo decide el usuario.
 */
class BankNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val registry by lazy { BankRegistry(applicationContext) }
    private val rules by lazy { NotificationRules(applicationContext) }
    private val dao by lazy { (applicationContext as SmartCountApp).database.inboxDao() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val watched = registry.isWatched(pkg)
        if (!watched && !registry.learnMode) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val parsed = MovementParser.parse(title, text, registry.ownName)

        // Lo que no se supo parsear también se guarda, y no solo en modo
        // aprendizaje: es la mitad que le falta a la bandeja para poder
        // calibrarse. Sin las notificaciones descartadas a la vista no hay
        // forma de rescatar la que el parser dejó fuera por error — y los
        // avisos de nómina de BBVA, que llegan sin importe, son exactamente
        // ese caso. Al venir solo de las apps vigiladas, el volumen es acotado.
        val policy = parsed?.let {
            rules.decide(it.kind, it.merchant, it.counterparty)
        } ?: MovementPolicy.INBOX_ONLY
        // "Ignorar" sigue significando ignorar: ni notificación ni bandeja. Es
        // lo que mantiene fuera los movimientos entre tus propias cuentas.
        if (policy == MovementPolicy.IGNORE && !registry.learnMode) return

        val key = dedupeKey(pkg, title, text)
        val now = System.currentTimeMillis()

        scope.launch {
            // Los bancos re-publican la misma notificación al actualizarla.
            if (dao.countRecentWithKey(key, now - DEDUPE_WINDOW_MS) > 0) return@launch

            val newId = dao.insert(
                InboxEntry(
                    detectedAt = now,
                    sourcePackage = pkg,
                    bankLabel = registry.label(pkg),
                    rawTitle = title.orEmpty(),
                    rawText = text.orEmpty(),
                    amount = parsed?.amount,
                    currency = parsed?.currency ?: "EUR",
                    counterparty = parsed?.counterparty,
                    merchant = parsed?.merchant,
                    concept = parsed?.concept,
                    kind = parsed?.kind ?: DetectedKind.UNKNOWN,
                    confidence = parsed?.confidence ?: Confidence.LOW,
                    dedupeKey = key
                )
            )
            if (newId <= 0) return@launch

            // Avisar con una notificación accionable: los grupos como botones.
            // Los tipos en "solo bandeja" y los orígenes silenciados no avisan.
            dao.byId(newId)?.let { saved ->
                if (policy == MovementPolicy.NOTIFY && saved.amount != null) {
                    DetectionNotifier.notify(applicationContext, saved)
                }
            }
            GroupCache(applicationContext)
                .savePendingCount(dao.countPending())
            SmartWidgets.refresh(applicationContext)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

    private fun dedupeKey(pkg: String, title: String?, text: String?): String {
        val raw = "$pkg|${title.orEmpty()}|${text.orEmpty()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        private const val DEDUPE_WINDOW_MS = 5 * 60 * 1000L

        /** ¿Tenemos concedido el acceso a notificaciones? */
        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            val component = ComponentName(context, BankNotificationListener::class.java)
            return enabled.split(":").any {
                ComponentName.unflattenFromString(it) == component
            }
        }

        val settingsIntentAction: String = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    }
}
