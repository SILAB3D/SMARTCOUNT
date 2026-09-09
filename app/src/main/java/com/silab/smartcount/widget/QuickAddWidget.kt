package com.silab.smartcount.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.silab.smartcount.MainActivity

/**
 * Widget pequeño de una sola acción: abre directamente la hoja de nuevo gasto
 * del grupo activo. Pensado para meter un gasto en dos toques desde el inicio.
 */
class QuickAddWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Content() } }
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        val context = LocalContext.current
        val fg = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF000000))
        val bg = ColorProvider(day = Color(0xFF07070A), night = Color(0xFFFFFFFF))
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_NEW_EXPENSE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bg)
                .cornerRadius(24.dp)
                .clickable(actionStartActivity(intent)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("+", style = TextStyle(color = fg, fontSize = 34.sp, fontWeight = FontWeight.Bold))
            Text("Gasto", style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}
