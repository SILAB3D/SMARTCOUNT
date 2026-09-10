package com.silab.smartcount.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silab.smartcount.SmartCountApp
import com.silab.smartcount.notif.BankNotificationListener
import com.silab.smartcount.notif.MovementPolicy
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme
import com.silab.smartcount.update.UpdatePhase
import com.silab.smartcount.update.UpdateViewModel

// ===========================================================================
// Pestaña 5 · Ajustes
// ===========================================================================

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    state: UiState,
    updateVm: UpdateViewModel,
    modifier: Modifier = Modifier
) {
    val c = SmartTheme.colors
    val context = LocalContext.current
    val update by updateVm.state.collectAsStateWithLifecycle()
    val app = context.applicationContext as SmartCountApp
    val registry = app.bankRegistry

    var learn by remember { mutableStateOf(registry.learnMode) }
    var watched by remember { mutableStateOf(registry.watchedPackages()) }
    val rules = app.notificationRules
    var ownName by remember { mutableStateOf(registry.ownName.orEmpty()) }
    var policyTick by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(rules.mutedSources()) }
    var editingName by remember { mutableStateOf(false) }
    val hasAccess = remember { BankNotificationListener.hasAccess(context) }

    if (editingName) {
        OwnNameSheet(
            initial = ownName,
            onDismiss = { editingName = false },
            onSave = { value ->
                ownName = value.trim()
                registry.ownName = ownName
                editingName = false
            }
        )
    }

    LazyColumn(
        modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { ScreenTitle("Ajustes") }

        item { SectionHeader("Detección") }
        item {
            SmartRow(
                title = "Acceso a notificaciones",
                subtitle = if (hasAccess) "Concedido" else "Sin conceder",
                value = if (hasAccess) "✓" else "→",
                valueColor = if (hasAccess) c.brand else c.secondaryText,
                onClick = {
                    context.startActivity(Intent(BankNotificationListener.settingsIntentAction))
                }
            )
            SmartDivider()
            SmartRow(
                title = "Tu nombre en el banco",
                subtitle = ownName.ifBlank {
                    "Sin definir · los movimientos entre tus cuentas llegarán a la bandeja"
                },
                value = if (ownName.isBlank()) "→" else "Cambiar",
                valueColor = c.secondaryText,
                onClick = { editingName = true }
            )
            SmartDivider()
            SmartRow(
                title = "Modo aprendizaje",
                subtitle = "Registra las notificaciones de todas las apps, no solo las vigiladas",
                value = if (learn) "ON" else "OFF",
                valueColor = if (learn) c.brand else c.secondaryText,
                onClick = { learn = !learn; registry.learnMode = learn }
            )
            SmartDivider()
        }

        item {
            SectionHeader("De qué te avisamos")
            Text(
                "Toca para alternar entre avisar, dejarlo solo en la bandeja o ignorarlo.",
                style = MaterialTheme.typography.bodySmall,
                color = c.secondaryText,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
            )
        }
        items(rules.configurableKinds(), key = { it.name }) { kind ->
            val policy = remember(kind, policyTick) { rules.policyFor(kind) }
            SmartRow(
                title = kind.label,
                value = policy.label,
                valueColor = when (policy) {
                    MovementPolicy.NOTIFY -> c.brand
                    MovementPolicy.INBOX_ONLY -> c.secondaryText
                    MovementPolicy.IGNORE -> c.negative
                },
                onClick = {
                    rules.setPolicy(kind, policy.next())
                    policyTick++
                }
            )
            SmartDivider()
        }

        item {
            SectionHeader("Silenciados")
            if (muted.isEmpty()) {
                Text(
                    "Nada silenciado. Cuando llegue una suscripción, la propia " +
                        "notificación te deja silenciar ese comercio de un toque.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
                )
            }
        }
        items(muted.toList().sorted(), key = { "muted-$it" }) { source ->
            SmartRow(
                title = source.replaceFirstChar { it.uppercase() },
                subtitle = "No avisa ni llega a la bandeja",
                value = "Reactivar",
                valueColor = c.secondaryText,
                onClick = {
                    rules.unmute(source)
                    muted = rules.mutedSources()
                }
            )
            SmartDivider()
        }

        item { SectionHeader("Apps vigiladas") }
        items(watched.toList().sorted(), key = { it }) { pkg ->
            SmartRow(
                title = registry.label(pkg),
                subtitle = pkg,
                value = "Quitar",
                valueColor = c.secondaryText,
                onClick = { registry.remove(pkg); watched = registry.watchedPackages() }
            )
            SmartDivider()
        }

        item {
            SectionHeader("Grupos de ahorro")
            Text(
                "Un grupo de ahorro es un grupo normal leído de otra manera: lo que entra " +
                    "desde la fuente de ingresos son ingresos, lo demás son gastos, y el " +
                    "balance es la resta. También se convierte desde el propio grupo, con " +
                    "el botón «Ahorro».",
                style = MaterialTheme.typography.bodySmall,
                color = c.secondaryText,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
            )
            if (state.tricounts.isEmpty()) {
                Text(
                    "Todavía no hay grupos que convertir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
                )
            }
        }
        items(state.tricounts, key = { "savings-${it.id}" }) { group ->
            val on = state.isSavings(group.id)
            SmartRow(
                title = "${group.emoji ?: ""} ${group.title}".trim(),
                subtitle = if (on) {
                    val summary = vm.savingsSummary(group)
                    "Balance ${formatMoney(summary.saved, group.currency, signed = true)}"
                } else {
                    "Grupo normal"
                },
                value = if (on) "Ahorro" else "Convertir",
                valueColor = if (on) c.brand else c.secondaryText,
                onClick = { vm.setSavings(group, !on) }
            )
            SmartDivider()
        }

        item {
            SectionHeader("Actualizaciones")
            SmartRow(
                title = "Buscar actualizaciones",
                subtitle = update.manualResult
                    ?: "Se comprueba sola al abrir la app",
                value = if (update.phase == UpdatePhase.CHECKING) "…" else "Comprobar",
                valueColor = if (update.manualResult?.startsWith("No se pudo") == true) {
                    c.negative
                } else {
                    c.brand
                },
                onClick = { updateVm.checkManually() }
            )
            SmartDivider()
            SmartRow(
                title = "Instalar apps desconocidas",
                subtitle = if (update.canInstall) {
                    "Concedido · las actualizaciones se instalan con un toque"
                } else {
                    "Sin conceder · hace falta para instalar la actualización"
                },
                value = if (update.canInstall) "✓" else "→",
                valueColor = if (update.canInstall) c.brand else c.secondaryText,
                onClick = { updateVm.openPermissionSettings() }
            )
            SmartDivider()
        }

        item {
            SectionHeader("Acerca de")
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(size = 34)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "SmartCount",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.primaryText
                        )
                        Text(
                            "Versión ${updateVm.installedVersionName} " +
                                "(build ${updateVm.installedVersionCode})",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.secondaryText
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "SmartCount usa la API interna de Tricount, que no es pública ni está " +
                        "documentada. Puede dejar de funcionar tras cualquier actualización y su " +
                        "uso queda fuera de los términos de servicio de Tricount. Uso personal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnNameSheet(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val c = SmartTheme.colors
    var value by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, dragHandle = null) {
        Column(Modifier.padding(ScreenPadding)) {
            Text("Tu nombre en el banco", style = MaterialTheme.typography.titleLarge, color = c.primaryText)
            Spacer(Modifier.height(8.dp))
            Text(
                "Cuando mueves dinero entre tus propias cuentas, el banco te avisa como si " +
                    "alguien te hubiera enviado un Bizum. Con tu nombre aquí, SmartCount " +
                    "reconoce esos movimientos y no te los pone en la bandeja. También sirve " +
                    "para saber cuál de los miembros de un grupo eres tú.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.secondaryText
            )
            Spacer(Modifier.height(20.dp))
            SmartField(value, { value = it }, "Nombre y apellidos")
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Guardar") { onSave(value) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
