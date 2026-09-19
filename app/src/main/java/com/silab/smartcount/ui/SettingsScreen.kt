package com.silab.smartcount.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silab.smartcount.SmartCountApp
import com.silab.smartcount.notif.BankNotificationListener
import com.silab.smartcount.notif.LearnMode
import com.silab.smartcount.notif.MovementPolicy
import com.silab.smartcount.ui.theme.ScreenPadding
import com.silab.smartcount.ui.theme.SmartTheme
import com.silab.smartcount.update.UpdatePhase
import com.silab.smartcount.update.UpdateViewModel

// ===========================================================================
// Pestaña 5 · Ajustes
// ===========================================================================

/**
 * Ajustes, por apartados plegables.
 *
 * Era una lista larguísima donde los permisos estaban repartidos entre el
 * principio y el final y había que desplazarse entera para encontrar
 * cualquier cosa. Ahora cada apartado se abre si se necesita y, plegados
 * todos, la pantalla cabe de un vistazo.
 *
 * Arriba del todo, y solo cuando la hay, la actualización: es lo único que
 * pide atención en vez de esperar a que la busques.
 */
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
    val rules = app.notificationRules

    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggle(key: String) {
        expanded = if (key in expanded) expanded - key else expanded + key
    }

    var learn by remember { mutableStateOf(registry.learnMode) }
    var appsTick by remember { mutableIntStateOf(0) }
    var ownName by remember { mutableStateOf(registry.ownName.orEmpty()) }
    var policyTick by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(rules.mutedSources()) }
    var editingName by remember { mutableStateOf(false) }
    val hasAccess = remember { BankNotificationListener.hasAccess(context) }

    val watchedApps = remember(appsTick) { registry.knownPackages().sortedBy { registry.label(it) } }
    val candidates = remember(appsTick) { registry.seen().sortedBy { registry.label(it) } }

    if (editingName) {
        TextPromptSheet(
            title = "Tu nombre en el banco",
            body = "Cuando mueves dinero entre tus propias cuentas, el banco te avisa como " +
                "si alguien te hubiera enviado un Bizum. Con tu nombre aquí, SmartCount " +
                "reconoce esos movimientos y no te los pone en la bandeja. También sirve " +
                "para saber cuál de los miembros de un grupo eres tú.",
            initial = ownName,
            label = "Nombre y apellidos",
            onDismiss = { editingName = false },
            onConfirm = { value, _ ->
                ownName = value
                registry.ownName = value
                editingName = false
            }
        )
    }

    LazyColumn(
        modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { ScreenTitle("Ajustes") }

        // Lo primero, cuando lo hay: una versión nueva esperando.
        if (update.phase == UpdatePhase.AVAILABLE || update.phase == UpdatePhase.READY) {
            item {
                UpdateCard(
                    versionName = update.release?.versionName.orEmpty(),
                    installed = updateVm.installedVersionName,
                    label = update.buttonLabel,
                    onClick = updateVm::primaryAction
                )
            }
        }

        settingsSection("permisos", "Permisos", "Lo que hay que conceder fuera de la app", expanded, ::toggle) {
            item {
                SmartRow(
                    title = "Acceso a notificaciones",
                    subtitle = if (hasAccess) "Concedido" else "Sin conceder · sin esto no se detecta nada",
                    value = if (hasAccess) "✓" else "→",
                    valueColor = if (hasAccess) c.brand else c.secondaryText,
                    onClick = {
                        context.startActivity(Intent(BankNotificationListener.settingsIntentAction))
                    }
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
                SmartRow(
                    title = "Avisos de SmartCount",
                    subtitle = "Sus notificaciones, en los ajustes del sistema",
                    value = "→",
                    valueColor = c.secondaryText,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                )
                SmartDivider()
            }
        }

        settingsSection("deteccion", "Detección", "Qué apps se miran y cómo", expanded, ::toggle) {
            item {
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
                    subtitle = learn.description,
                    value = learn.label,
                    valueColor = when (learn) {
                        LearnMode.OFF -> c.secondaryText
                        LearnMode.SELECTIVE -> c.brand
                        LearnMode.FULL -> c.brand
                    },
                    onClick = {
                        learn = learn.next()
                        registry.learnMode = learn
                    }
                )
                SmartDivider()
                Text(
                    "Toca para cambiar de modo. En Completo se miran todas las apps del " +
                        "móvil y las que notifiquen aparecerán aquí abajo para activarlas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)
                )
            }

            item { SectionHeader("Apps vigiladas") }
            items(watchedApps, key = { "app-$it" }) { pkg ->
                val active = registry.isActive(pkg)
                val isDefault = pkg in registry.defaults()
                SmartRow(
                    title = registry.label(pkg),
                    subtitle = if (isDefault) pkg else "$pkg · añadida por ti",
                    value = if (active) "ON" else "OFF",
                    valueColor = if (active) c.brand else c.secondaryText,
                    onClick = { registry.setActive(pkg, !active); appsTick++ },
                    trailing = if (isDefault) null else {
                        {
                            Text(
                                "  Quitar",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.negative,
                                modifier = Modifier.clickable { registry.forget(pkg); appsTick++ }
                            )
                        }
                    }
                )
                SmartDivider()
            }

            if (candidates.isNotEmpty()) {
                item {
                    SectionHeader("Han notificado y no se vigilan")
                }
                items(candidates, key = { "cand-$it" }) { pkg ->
                    SmartRow(
                        title = registry.label(pkg),
                        subtitle = pkg,
                        value = "Vigilar",
                        valueColor = c.brand,
                        onClick = { registry.add(pkg); appsTick++ }
                    )
                    SmartDivider()
                }
            }
        }

        settingsSection("avisos", "Avisos", "De qué te avisamos y qué se calla", expanded, ::toggle) {
            item {
                Text(
                    "Toca cada tipo para alternar entre avisar, dejarlo solo en la bandeja " +
                        "o ignorarlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryText,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)
                )
            }
            items(rules.configurableKinds(), key = { "kind-" + it.name }) { kind ->
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

            item { SectionHeader("Silenciados") }
            if (muted.isEmpty()) {
                item {
                    Text(
                        "Nada silenciado. Cuando llegue una suscripción, la propia hoja del " +
                            "movimiento te deja callar ese comercio.",
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
        }

        settingsSection("updates", "Actualizaciones", "De dónde salen y cuándo", expanded, ::toggle) {
            item {
                SmartRow(
                    title = "Buscar actualizaciones",
                    subtitle = update.manualResult
                        ?: "Se comprueba sola al abrir la app y una vez al día",
                    value = if (update.phase == UpdatePhase.CHECKING) "…" else "Comprobar",
                    valueColor = if (update.manualResult?.startsWith("No se pudo") == true) {
                        c.negative
                    } else {
                        c.brand
                    },
                    onClick = { updateVm.checkManually() }
                )
                SmartDivider()
            }
        }

        settingsSection("acerca", "Acerca de", "Versión y letra pequeña", expanded, ::toggle) {
            item {
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
}

/**
 * Un apartado plegable. Plegado de serie: la pantalla entera cabe de un
 * vistazo y cada cosa se abre cuando hace falta.
 */
private fun LazyListScope.settingsSection(
    key: String,
    title: String,
    subtitle: String,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    body: LazyListScope.() -> Unit
) {
    val open = key in expanded
    item(key = "sec-$key") {
        val c = SmartTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle(key) }
                .padding(horizontal = ScreenPadding, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.primaryText)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.secondaryText)
            }
            Text(
                if (open) "▾" else "›",
                style = MaterialTheme.typography.titleMedium,
                color = c.secondaryText
            )
        }
        SmartDivider(inset = false)
    }
    if (open) {
        body()
        item(key = "sec-end-$key") { Spacer(Modifier.height(12.dp)) }
    }
}

/** La actualización disponible, arriba del todo y sin tener que buscarla. */
@Composable
private fun UpdateCard(
    versionName: String,
    installed: String,
    label: String,
    onClick: () -> Unit
) {
    val c = SmartTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .clip(RoundedCornerShape(18.dp))
            .background(c.savingsTint)
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Text(
            "Actualización disponible",
            style = MaterialTheme.typography.titleMedium,
            color = c.primaryText
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Versión $versionName · tienes la $installed",
            style = MaterialTheme.typography.bodySmall,
            color = c.secondaryText
        )
        Spacer(Modifier.height(14.dp))
        Text(label, color = c.brand, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(20.dp))
}
