package com.silab.smartcount.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Repinta todos los widgets tras cambiar la caché. */
object SmartWidgets {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh(context: Context) {
        scope.launch {
            runCatching { BalanceWidget().updateAll(context) }
            runCatching { QuickAddWidget().updateAll(context) }
        }
    }

    suspend fun hasAny(context: Context): Boolean =
        GlanceAppWidgetManager(context).getGlanceIds(BalanceWidget::class.java).isNotEmpty()
}
