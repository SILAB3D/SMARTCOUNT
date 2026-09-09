package com.silab.smartcount

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silab.smartcount.ui.GroupsScreen
import com.silab.smartcount.ui.InboxScreen
import com.silab.smartcount.ui.MainViewModel
import com.silab.smartcount.ui.SettingsScreen
import com.silab.smartcount.ui.StatsScreen
import com.silab.smartcount.ui.theme.SmartCountTheme
import com.silab.smartcount.notif.DetectionNotifier
import com.silab.smartcount.ui.theme.SmartTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val startTab = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // El splash se instala antes de super.onCreate: si no, no llega a verse.
        val splash = installSplashScreen()
        val start = SystemClock.uptimeMillis()
        // Se mantiene exactamente 1 s, lo que dura la animación del icono.
        splash.setKeepOnScreenCondition {
            SystemClock.uptimeMillis() - start < SPLASH_MILLIS
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent { SmartCountTheme { AppRoot(vm, startTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /**
     * Rutas de entrada: compartir un enlace de Tricount, el widget "+ Gasto"
     * y el toque en la notificación de movimiento detectado.
     */
    private fun handleShare(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.addByLink(it) }
            ACTION_NEW_EXPENSE -> {
                startTab.value = "groups"
                vm.requestNewExpense()
            }
            ACTION_OPEN_INBOX -> {
                startTab.value = "inbox"
                val entryId = intent.getLongExtra(DetectionNotifier.EXTRA_ENTRY_ID, -1)
                vm.focusInboxEntry(if (entryId >= 0) entryId else null)
            }
        }
    }

    companion object {
        private const val SPLASH_MILLIS = 1000L
        const val ACTION_NEW_EXPENSE = "com.silab.smartcount.NEW_EXPENSE"
        const val ACTION_OPEN_INBOX = "com.silab.smartcount.OPEN_INBOX"
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    GROUPS("Grupos", Icons.Outlined.People),
    STATS("Estadísticas", Icons.Outlined.BarChart),
    INBOX("Bandeja", Icons.Outlined.Inbox),
    SETTINGS("Ajustes", Icons.Outlined.Tune)
}

@Composable
fun AppRoot(vm: MainViewModel, startTab: MutableState<String?> = mutableStateOf(null)) {
    val c = SmartTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.GROUPS) }

    // El widget y la notificación pueden abrir la app en una pestaña concreta.
    LaunchedEffect(startTab.value) {
        when (startTab.value) {
            "inbox" -> tab = Tab.INBOX
            "groups" -> tab = Tab.GROUPS
        }
        startTab.value = null
    }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.error, state.message) {
        val msg = state.error ?: state.message
        if (msg != null) {
            toast = msg
            vm.clearMessages()
            delay(2600)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {

            // Cambio de pestaña con fundido: sin saltos ni recomposición visible.
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                },
                modifier = Modifier.weight(1f),
                label = "tab"
            ) { current ->
                when (current) {
                    Tab.GROUPS -> GroupsScreen(vm, state)
                    Tab.STATS -> StatsScreen(state)
                    Tab.INBOX -> InboxScreen(vm, state, inbox)
                    Tab.SETTINGS -> SettingsScreen()
                }
            }

            BottomTabs(
                selected = tab,
                inboxCount = inbox.size,
                onSelect = { tab = it }
            )
        }

        toast?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp, start = 20.dp, end = 20.dp)
            ) {
                Snackbar(
                    containerColor = c.chipSelected,
                    contentColor = c.chipSelectedText
                ) { Text(msg) }
            }
        }
    }
}

@Composable
private fun BottomTabs(selected: Tab, inboxCount: Int, onSelect: (Tab) -> Unit) {
    val c = SmartTheme.colors
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.background)
                .navigationBarsPadding()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Tab.entries.forEach { t ->
                val active = t == selected
                val tint = if (active) c.primaryText else c.secondaryText
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(t) }
                        .padding(vertical = 4.dp)
                ) {
                    Box {
                        Icon(t.icon, contentDescription = t.label, tint = tint)
                        if (t == Tab.INBOX && inboxCount > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 1.dp, end = 1.dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(c.brand)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.label,
                        color = tint,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
