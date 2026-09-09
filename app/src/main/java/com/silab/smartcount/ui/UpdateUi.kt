package com.silab.smartcount.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme
import com.silab.smartcount.update.UpdatePhase
import com.silab.smartcount.update.UpdateState

/**
 * Aviso de versión nueva.
 *
 * Enseña lo mínimo: qué versión hay y qué va a pasar. Nada de notas de la
 * versión ni tamaño del descargable, que son datos que nadie lee en un modal.
 *
 * Lo único que sí merece el espacio son **las instrucciones**: al instalar
 * fuera de Play Store, Android enseña una pantalla con tono de advertencia y un
 * botón poco evidente, y ese es el momento exacto en que la gente cancela.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    state: UpdateState,
    installedVersionName: String,
    onPrimary: () -> Unit,
    onGrantPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = SmartTheme.colors
    val release = state.release ?: return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.background,
        dragHandle = null
    ) {
        Column(Modifier.padding(ScreenPadding)) {
            Text(
                "Hay una versión nueva",
                style = MaterialTheme.typography.titleLarge,
                color = c.primaryText
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "SmartCount ${release.versionName} · tienes la $installedVersionName",
                style = MaterialTheme.typography.bodySmall,
                color = c.secondaryText
            )

            Spacer(Modifier.height(20.dp))

            if (state.canInstall) {
                Text(
                    "Al pulsar, la app se descarga y se abre el instalador de Android. " +
                        "Como no viene de Play Store, verás una pantalla de aviso: pulsa " +
                        "«Instalar de todos modos». Va firmada con la misma clave, y tus " +
                        "grupos y tu bandeja quedan intactos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.secondaryText
                )
            } else {
                Text(
                    "Android necesita tu permiso para que SmartCount pueda instalar " +
                        "actualizaciones. Se concede una sola vez, en una pantalla de " +
                        "ajustes del sistema.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.secondaryText
                )
            }

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.negative
                )
            }

            if (state.phase == UpdatePhase.DOWNLOADING) {
                Spacer(Modifier.height(20.dp))
                ProgressTrack(state.progress)
            }

            Spacer(Modifier.height(24.dp))

            if (state.canInstall) {
                PrimaryButton(
                    text = state.buttonLabel,
                    enabled = state.phase != UpdatePhase.DOWNLOADING,
                    onClick = onPrimary
                )
            } else {
                PrimaryButton(text = "Conceder permiso", onClick = onGrantPermission)
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Ahora no",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.secondaryText,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Barra de progreso de la descarga, con el mismo lenguaje visual que el resto. */
@Composable
private fun ProgressTrack(percent: Int) {
    val c = SmartTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(100))
            .background(c.chipBackground)
    ) {
        Box(
            Modifier
                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                .height(4.dp)
                .clip(RoundedCornerShape(100))
                .background(c.brand)
        )
    }
}
