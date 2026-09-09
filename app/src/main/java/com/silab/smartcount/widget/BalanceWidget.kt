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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.silab.smartcount.MainActivity
import com.silab.smartcount.data.cache.CacheSnapshot
import com.silab.smartcount.data.cache.GroupCache
import java.util.Locale

/**
 * Widget de saldo: el grupo activo, cuánto te deben o debes, y accesos
 * directos a añadir gasto y a la bandeja. Mismo lenguaje visual que la app.
 */
class BalanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = GroupCache(context).read()
        provideContent { GlanceTheme { Content(snapshot) } }
    }

    private fun money(v: Double, currency: String, signed: Boolean = false): String {
        val symbol = if (currency.equals("EUR", true)) "€" else currency
        val sign = if (signed && v > 0) "+" else ""
        return String.format(Locale.getDefault(), "%s%.2f %s", sign, v, symbol)
    }

    @androidx.compose.runtime.Composable
    private fun Content(snapshot: CacheSnapshot) {
        val context = LocalContext.current
        val g = snapshot.selected
        val bg = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF000000))
        val text = ColorProvider(day = Color(0xFF07070A), night = Color(0xFFFFFFFF))
        val text2 = ColorProvider(day = Color(0xFF74747A), night = Color(0xFF8A8A8E))
        val chip = ColorProvider(day = Color(0xFFF2F2F4), night = Color(0xFF161618))
        val pos = ColorProvider(day = Color(0xFF00B862), night = Color(0xFF00C46A))
        // Misma regla que en la app: el azul de marca para lo que no es dinero.
        val brand = ColorProvider(day = Color(0xFF3355E6), night = Color(0xFF4A6CFF))
        val neg = ColorProvider(day = Color(0xFFFF3B3B), night = Color(0xFFFF4D4D))

        Column(
            GlanceModifier
                .fillMaxSize()
                .background(bg)
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(
                    actionStartActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                )
        ) {
            if (g == null) {
                Text("SmartCount", style = TextStyle(color = text, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(6.dp))
                Text("Añade un grupo para ver tu saldo", style = TextStyle(color = text2, fontSize = 13.sp))
                return@Column
            }

            Text(
                "${g.emoji ?: ""} ${g.title}".trim(),
                style = TextStyle(color = text2, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                money(g.myBalance, g.currency, signed = true),
                style = TextStyle(
                    color = if (g.myBalance < 0) neg else pos,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Text(
                if (g.myBalance >= 0) "te deben" else "debes",
                style = TextStyle(color = text2, fontSize = 12.sp)
            )

            Spacer(GlanceModifier.height(12.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Chip("+ Gasto", chip, text, MainActivity.ACTION_NEW_EXPENSE)
                Spacer(GlanceModifier.width(8.dp))
                Chip(
                    if (snapshot.pendingInbox > 0) "Bandeja ${snapshot.pendingInbox}" else "Bandeja",
                    chip,
                    if (snapshot.pendingInbox > 0) brand else text2,
                    MainActivity.ACTION_OPEN_INBOX
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Chip(
        label: String,
        background: ColorProvider,
        color: ColorProvider,
        action: String
    ) {
        val context = LocalContext.current
        val intent = Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        Text(
            label,
            style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier
                .background(background)
                .cornerRadius(100.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(actionStartActivity(intent))
        )
    }
}

class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalanceWidget()
}
