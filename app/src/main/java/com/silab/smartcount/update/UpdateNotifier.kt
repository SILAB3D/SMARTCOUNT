package com.silab.smartcount.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silab.smartcount.MainActivity
import com.silab.smartcount.R
import com.silab.smartcount.notif.notifySafely
import java.util.concurrent.TimeUnit

/**
 * Avisar de que hay versión nueva.
 *
 * Hasta ahora la comprobación solo ocurría al abrir la app, así que una
 * versión publicada el lunes podía descubrirse el viernes: justo al revés de
 * lo que se quiere de un canal cuyo objetivo es que un `git push` acabe
 * instalado. Ahora se comprueba también una vez al día en segundo plano y,
 * cuando hay algo, lo dice.
 *
 * **Una sola vez por versión.** Un aviso diario de la misma actualización
 * enseñaría a ignorarlo, que es la única forma segura de que el aviso
 * importante pase desapercibido.
 */
object UpdateNotifier {

    const val CHANNEL_ID = "actualizaciones"
    private const val NOTIFICATION_ID = 424242
    private const val PREFS = "updates"
    private const val KEY_NOTIFIED = "notified_version_code"
    private const val WORK_NAME = "comprobar-actualizacion"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Actualizaciones",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Avisa cuando hay una versión nueva de SmartCount"
            setShowBadge(false)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** ¿Ya se avisó de esta versión? */
    fun alreadyNotified(context: Context, versionCode: Long): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_NOTIFIED, 0L) >= versionCode

    private fun markNotified(context: Context, versionCode: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_NOTIFIED, versionCode).apply()
    }

    fun notify(context: Context, release: Updater.Release) {
        if (alreadyNotified(context, release.versionCode)) return
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            release.versionCode.toInt(),
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_UPDATE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SmartCount ${release.versionName} disponible")
            .setContentText("Tienes la ${Updater.installedVersionName(context)}")
            .setContentIntent(open)
            .addAction(0, "Descargar e instalar", open)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notifySafely(context, NOTIFICATION_ID, n)
        markNotified(context, release.versionCode)
    }

    /**
     * Una comprobación al día, y solo con red. No hay prisa: lo que se quiere
     * evitar es enterarse una semana tarde, no en el mismo minuto.
     */
    fun schedule(context: Context) {
        val work = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            // KEEP y no UPDATE: reprogramar en cada arranque reiniciaría la
            // cuenta atrás y, abriendo la app a diario, no llegaría a correr.
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }
}

/**
 * La comprobación de fondo. Se calla sus errores igual que la del arranque:
 * sin cobertura o con GitHub a 403 no hay nada que decirle a nadie, y el
 * trabajo se reintenta mañana.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = runCatching { Updater.check(applicationContext) }.getOrNull()
        if (result is Updater.Check.Available) {
            UpdateNotifier.notify(applicationContext, result.release)
        }
        return Result.success()
    }
}
