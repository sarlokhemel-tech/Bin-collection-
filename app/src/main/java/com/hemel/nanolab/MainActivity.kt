package com.hemel.nanolab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NanoApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshTrees()
    }
}

@Composable
fun NanoApp(vm: AppViewModel) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NanoRoot(vm)
        }
    }
}

@Composable
fun NanoRoot(vm: AppViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    LaunchedEffect(vm.info) {
        val m = vm.info
        if (m != null) {
            snack.showSnackbar(m)
            vm.info = null
        }
    }
    BackHandler(enabled = vm.screen != 0 && drawerState.currentValue == DrawerValue.Closed) { vm.screen = 0 }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { AppDrawer(vm, closeDrawer) }
    ) {
        Box(Modifier.fillMaxSize()) {
            when (vm.screen) {
                0 -> ChatScreen(vm, "cloud", openDrawer)
                1 -> ChatScreen(vm, "local", openDrawer)
                2 -> TestScreen(vm, openDrawer)
                3 -> ModelsScreen(vm, openDrawer)
                4 -> A11yScreen(vm, openDrawer)
                else -> SettingsScreen(vm, openDrawer)
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
        }
    }
}

@Composable
fun AppDrawer(vm: AppViewModel, close: () -> Unit) {
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    val items = listOf(
        Triple(0, "চ্যাট", R.drawable.ic_sparkle),
        Triple(1, "লোকাল LLM", R.drawable.ic_chip),
        Triple(2, "মডেল টেস্ট", R.drawable.ic_flask),
        Triple(3, "মডেল লাইব্রেরি", R.drawable.ic_folder),
        Triple(4, "Accessibility Tree", R.drawable.ic_access),
        Triple(5, "সেটিংস", R.drawable.ic_settings)
    )
    ModalDrawerSheet {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Ico(R.drawable.ic_sparkle, tint = MaterialTheme.colorScheme.primary, size = 26.dp)
                Text("  NanoLab", style = MaterialTheme.typography.titleLarge)
            }
            for (it3 in items) {
                NavigationDrawerItem(
                    label = { Text(it3.second) },
                    selected = vm.screen == it3.first,
                    onClick = { vm.screen = it3.first; close() },
                    icon = { Ico(it3.third, size = 22.dp) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            DrawerHistory("চ্যাট ইতিহাস", "cloud", vm, close) { renameTarget = it }
            DrawerHistory("লোকাল LLM ইতিহাস", "local", vm, close) { renameTarget = it }
        }
    }
    val rt = renameTarget
    if (rt != null) {
        RenameDialog(rt.title, { vm.renameSession(rt, it); renameTarget = null }) { renameTarget = null }
    }
}

@Composable
fun DrawerHistory(
    title: String, kind: String, vm: AppViewModel, close: () -> Unit, onRename: (ChatSession) -> Unit
) {
    val list = vm.sessions.filter { it.kind == kind && it.messages.isNotEmpty() }.sortedByDescending { it.updatedAt }
    if (list.isEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp)
    )
    for (s in list.take(40)) key(s.id) {
        var menu by remember { mutableStateOf(false) }
        val activeId = if (kind == "cloud") vm.activeCloudId else vm.activeLocalId
        val isActive = activeId == s.id && ((kind == "cloud" && vm.screen == 0) || (kind == "local" && vm.screen == 1))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp))
                .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { vm.openSession(s); close() }
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                s.title,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (s.sending) Text("⏳", modifier = Modifier.padding(end = 4.dp))
            Box {
                IcoBtn(R.drawable.ic_more, "আরও", { menu = true }, size = 20.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("নাম বদলান") },
                        leadingIcon = { Ico(R.drawable.ic_edit, size = 20.dp) },
                        onClick = { menu = false; onRename(s) }
                    )
                    DropdownMenuItem(
                        text = { Text("মুছুন") },
                        leadingIcon = { Ico(R.drawable.ic_delete, size = 20.dp) },
                        onClick = { menu = false; vm.deleteSession(s) }
                    )
                }
            }
        }
    }
}
