package com.silab.smartcount.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silab.smartcount.ui.theme.RowVerticalPadding
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme
import java.util.Locale

/** "2026-09-03 19:40:00" → "3 sep" (con año solo si no es el actual). */
fun formatTxDate(raw: String): String {
    val date = raw.take(10).split("-")
    if (date.size < 3) return raw
    val months = listOf(
        "ene", "feb", "mar", "abr", "may", "jun",
        "jul", "ago", "sep", "oct", "nov", "dic"
    )
    val year = date[0].toIntOrNull() ?: return raw
    val month = date[1].toIntOrNull()?.minus(1)?.coerceIn(0, 11) ?: return raw
    val day = date[2].toIntOrNull() ?: return raw
    val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    return if (year == currentYear) "$day ${months[month]}" else "$day ${months[month]} $year"
}

fun formatMoney(v: Double, currency: String, signed: Boolean = false): String {
    val symbol = when (currency.uppercase()) {
        "EUR" -> "€"; "GBP" -> "£"; "USD" -> "$"; else -> currency
    }
    val sign = if (signed && v > 0) "+" else ""
    return String.format(Locale.getDefault(), "%s%.2f %s", sign, v, symbol)
}

/** Línea de separación finísima, a sangre del contenido como en TR. */
@Composable
fun SmartDivider(modifier: Modifier = Modifier, inset: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = if (inset) ScreenPadding else 0.dp)
            .height(1.dp)
            .background(SmartTheme.colors.divider)
    )
}

@Composable
fun SectionHeader(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text.uppercase(),
            style = com.silab.smartcount.ui.theme.SectionTitle,
            color = SmartTheme.colors.secondaryText
        )
        action?.invoke()
    }
}

/** Avatar circular monocromo con iniciales. */
@Composable
fun Initials(name: String, size: Int = 38) {
    val c = SmartTheme.colors
    val initials = name.trim().split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(c.chipBackground),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = c.secondaryText, fontWeight = FontWeight.Medium)
    }
}

/**
 * Fila estándar: avatar/emoji, título + subtítulo, y valor a la derecha.
 * Es el ladrillo con el que TR construye casi todas sus pantallas.
 */
@Composable
fun SmartRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val c = SmartTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ScreenPadding, vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = c.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = valueColor ?: c.primaryText
            )
        }
        trailing?.invoke()
    }
}

/** Chip de selección: relleno invertido cuando está activo, sin bordes. */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val c = SmartTheme.colors
    val bg by animateColorAsState(
        if (selected) c.chipSelected else c.chipBackground,
        tween(180), label = "chipBg"
    )
    val fg by animateColorAsState(
        if (selected) c.chipSelectedText else c.secondaryText,
        tween(180), label = "chipFg"
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(100))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text, color = fg, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

/** Botón principal: ancho completo, color invertido, esquinas de cápsula. */
@Composable
fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = SmartTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(100))
            .background(if (enabled) c.chipSelected else c.chipBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) c.chipSelectedText else c.secondaryText,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = SmartTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(100))
            .background(c.chipBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = c.primaryText, fontWeight = FontWeight.Medium)
    }
}

/**
 * La marca de SmartCount dibujada con los colores del tema: la barra común del
 * ÷ y el +, el asta, y los dos puntos en el azul de marca. Misma geometría que
 * el icono (lienzo 108), para que app e icono sean literalmente lo mismo.
 */
@Composable
fun BrandMark(
    size: Int = 40,
    inkColor: Color? = null,
    accentColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val c = SmartTheme.colors
    val ink = inkColor ?: c.primaryText
    val accent = accentColor ?: c.brand
    Canvas(modifier.size(size.dp)) {
        val u = this.size.minDimension / 108f          // el lienzo del icono
        fun p(v: Float) = v * u
        drawRoundRect(
            color = ink,
            topLeft = Offset(p(22f), p(49.5f)),
            size = Size(p(64f), p(9f)),
            cornerRadius = CornerRadius(p(4.5f))
        )
        drawRoundRect(
            color = ink,
            topLeft = Offset(p(34f), p(31.5f)),
            size = Size(p(9f), p(45f)),
            cornerRadius = CornerRadius(p(4.5f))
        )
        drawCircle(accent, radius = p(6.3f), center = Offset(p(71f), p(38.5f)))
        drawCircle(accent, radius = p(6.3f), center = Offset(p(71f), p(69.5f)))
    }
}

/** Barra de proporción para las estadísticas: fina, sin fondo redondeado llamativo. */
@Composable
fun ProportionBar(fraction: Float) {
    val c = SmartTheme.colors
    val animated by animateFloatAsState(
        fraction.coerceIn(0f, 1f), tween(450), label = "bar"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(100))
            .background(c.divider)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(3.dp)
                .clip(RoundedCornerShape(100))
                .background(c.brand)
        )
    }
}
