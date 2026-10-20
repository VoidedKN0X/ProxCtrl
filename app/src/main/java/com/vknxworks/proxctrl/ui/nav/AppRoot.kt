package com.vknxworks.proxctrl.ui.nav

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.console.ConsoleTarget
import com.vknxworks.proxctrl.ui.console.ConsoleScreen
import com.vknxworks.proxctrl.ui.guests.BackupsScreen
import com.vknxworks.proxctrl.ui.tasks.tasksDestinations
import com.vknxworks.proxctrl.ui.node.nodeDestinations
import com.vknxworks.proxctrl.ui.storage.storageDestinations
import com.vknxworks.proxctrl.ui.guests.GuestOptionsRoute
import com.vknxworks.proxctrl.ui.guests.GuestOptionsScreen
import com.vknxworks.proxctrl.ui.guests.SnapshotsScreen
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.DeepLinks
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.vknxworks.proxctrl.ui.settings.NotificationsScreen
import com.vknxworks.proxctrl.ui.settings.ServersScreen
import com.vknxworks.proxctrl.ui.setup.SetupScreen
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.toRoute
import com.vknxworks.proxctrl.ui.components.LocalBottomClearance
import com.vknxworks.proxctrl.ui.components.LocalHazeState
import com.vknxworks.proxctrl.ui.guests.GuestScreen
import com.vknxworks.proxctrl.ui.guests.GuestsScreen
import com.vknxworks.proxctrl.ui.node.NodeScreen
import com.vknxworks.proxctrl.ui.overview.OverviewScreen
import com.vknxworks.proxctrl.ui.settings.SettingsScreen
import com.vknxworks.proxctrl.ui.settings.YouScreen
import com.vknxworks.proxctrl.ui.storage.StorageScreen
import com.vknxworks.proxctrl.ui.tasks.TasksScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
fun AppRoot() {
    val c = LocalContext.current.container
    val servers by c.sessions.servers.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (servers.isEmpty()) {
            // First start (or every server removed): setup fills the screen, no tabs.
            SetupScreen(onDone = {}, onCancel = null)
        } else {
            MainScaffold()
        }
    }
}

private data class TopDest(val route: Any, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

/** The five tabs. Overview is the only summary screen; account and settings open from the profile picture. */
private val topDestinations = listOf(
    TopDest(OverviewRoute, R.string.tab_overview, Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
    TopDest(GuestsRoute, R.string.tab_guests, Icons.Outlined.Computer, Icons.Filled.Computer),
    TopDest(StorageRoute, R.string.tab_storage, Icons.Outlined.Storage, Icons.Filled.Storage),
    TopDest(TasksRoute, R.string.tab_tasks, Icons.Outlined.Task, Icons.Filled.Task),
    TopDest(NodeRoute, R.string.tab_node, Icons.Outlined.Dns, Icons.Filled.Dns),
)

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    val backStack by navController.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val selectedIndex = topDestinations.indexOfFirst { top -> dest?.hierarchy?.any { it.hasRoute(top.route::class) } == true }
    // Consoles get the whole screen and stay out of the glass effect, which they don't need.
    val inConsole = dest?.hasRoute(ConsoleRoute::class) == true
    val tabs = topDestinations.map { TabItem(stringResource(it.label), it.icon, it.selectedIcon) }

    fun selectTab(i: Int) {
        navController.navigate(topDestinations[i].route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val openOverview by com.vknxworks.proxctrl.ui.widgets.WidgetActions.openOverview.collectAsStateWithLifecycle()
    LaunchedEffect(openOverview) {
        if (openOverview) {
            selectTab(topDestinations.indexOfFirst { it.route == OverviewRoute })
            com.vknxworks.proxctrl.ui.widgets.WidgetActions.overviewShown()
        }
    }

    // proxctrl://guest/<vmid>: open that guest's page, or the Guests tab when it isn't on this server.
    val c = LocalContext.current.container

    val pendingGuest by DeepLinks.pendingGuest.collectAsStateWithLifecycle()
    LaunchedEffect(pendingGuest) {
        val vmid = pendingGuest ?: return@LaunchedEffect
        val serverId = c.sessions.active.value?.server?.id
        var guest = c.guests.state.value.guests.firstOrNull { it.vmid == vmid && c.guests.state.value.serverId == serverId }
        if (guest == null) {
            // The list may still be loading from the cache after a cold start.
            withTimeoutOrNull(5_000) { c.guests.state.first { it.serverId == serverId } }
            c.guests.refresh()
            guest = c.guests.state.value.guests.firstOrNull { it.vmid == vmid }
        }
        DeepLinks.consumed()
        if (guest != null) {
            navController.navigate(GuestRoute(guest.node, guest.type.api, guest.vmid)) { launchSingleTop = true }
        } else {
            selectTab(topDestinations.indexOfFirst { it.route == GuestsRoute })
        }
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (maxWidth >= 600.dp) {
                // Tablets, unfolded foldables and landscape: a side rail.
                Row(Modifier.fillMaxSize()) {
                    if (!inConsole) NavigationRail(
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxHeight().statusBarsPadding(),
                    ) {
                        Spacer(Modifier.weight(1f))
                        tabs.forEachIndexed { i, tab ->
                            val selected = i == selectedIndex
                            NavigationRailItem(
                                selected = selected,
                                onClick = { selectTab(i) },
                                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()) { AppNavHost(navController) }
                }
            } else {
                PhoneLayout(
                    navController = navController,
                    tabs = tabs,
                    showTabs = selectedIndex >= 0,
                    blurContent = !inConsole,
                    selectedIndex = selectedIndex,
                    onSelect = ::selectTab,
                )
            }
        }
    }
}

/**
 * Phones: content runs edge to edge and scrolls under a floating glass island.
 * The island shows on the main tabs; pushed screens get the full height.
 */
@Composable
private fun PhoneLayout(
    navController: NavHostController,
    tabs: List<TabItem>,
    showTabs: Boolean,
    blurContent: Boolean,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    var overlayHeight by remember { mutableStateOf(0.dp) }

    CompositionLocalProvider(
        LocalHazeState provides hazeState,
        LocalBottomClearance provides if (showTabs) overlayHeight else 0.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if (blurContent) Modifier.hazeSource(hazeState) else Modifier)) {
                AppNavHost(navController)
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { overlayHeight = with(density) { it.height.toDp() } }
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(
                    visible = showTabs,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                ) {
                    GlassTabBar(items = tabs, selectedIndex = selectedIndex, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(navController, startDestination = OverviewRoute) {
        tasksDestinations()
        nodeDestinations()
        storageDestinations()
        composable<OverviewRoute> { OverviewScreen() }
        composable<GuestsRoute> { GuestsScreen() }
        composable<StorageRoute> { StorageScreen() }
        composable<TasksRoute> { TasksScreen() }
        composable<NodeRoute> { NodeScreen() }

        composable<GuestRoute> {
            val r = it.toRoute<GuestRoute>()
            GuestScreen(r.node, r.type, r.vmid)
        }

        composable<SnapshotsRoute> {
            val r = it.toRoute<SnapshotsRoute>()
            SnapshotsScreen(r.node, r.type, r.vmid, r.name)
        }
        composable<GuestOptionsRoute> {
            val r = it.toRoute<GuestOptionsRoute>()
            GuestOptionsScreen(r.node, r.type, r.vmid, r.name)
        }
        composable<BackupsRoute> {
            val r = it.toRoute<BackupsRoute>()
            BackupsScreen(r.node, r.type, r.vmid, r.name)
        }

        composable<ConsoleRoute> {
            val r = it.toRoute<ConsoleRoute>()
            val target = when (r.kind) {
                "node" -> ConsoleTarget.NodeShell(r.node)
                "lxc" -> ConsoleTarget.Container(r.node, r.vmid)
                else -> ConsoleTarget.Vm(r.node, r.vmid, r.serial)
            }
            ConsoleScreen(target, r.title)
        }

        composable<YouRoute> { YouScreen() }
        composable<SettingsRoute> { SettingsScreen() }
        composable<NotificationsRoute> { NotificationsScreen() }
        composable<ServersRoute> { ServersScreen() }
        composable<AddServerRoute> {
            val navigator = LocalNavigator.current
            SetupScreen(onDone = { navController.popBackStack(OverviewRoute, false) }, onCancel = navigator::back)
        }
        composable<EditServerRoute> {
            val navigator = LocalNavigator.current
            SetupScreen(editId = it.toRoute<EditServerRoute>().id, onDone = navigator::back, onCancel = navigator::back)
        }
    }
}
