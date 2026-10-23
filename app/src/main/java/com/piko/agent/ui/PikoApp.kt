package com.piko.agent.ui

import android.Manifest
import android.view.WindowManager
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.piko.agent.ui.components.PikoMark
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.piko.agent.service.ProjectionService
import com.piko.agent.ui.screens.HistoryDetailScreen
import com.piko.agent.ui.screens.HistoryScreen
import com.piko.agent.ui.screens.HomeScreen
import com.piko.agent.ui.screens.ModelsScreen
import com.piko.agent.ui.screens.ProviderEditorScreen
import com.piko.agent.ui.screens.SettingsScreen
import com.piko.agent.ui.screens.TaskScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    TopLevelDestination("home", "Home", Icons.Outlined.Home),
    TopLevelDestination("models", "Models", Icons.Outlined.SmartToy),
    TopLevelDestination("history", "History", Icons.Outlined.History),
    TopLevelDestination("assistant", "Assistant", Icons.Outlined.Mic),
    TopLevelDestination("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun PikoApp(viewModel: PikoViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    LaunchedEffect(viewModel) {
        viewModel.openAssistant.collect { requested ->
            if (requested) { navController.navigate("assistant") { launchSingleTop = true }; viewModel.openAssistant.value = false }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshPermissions()
        if (!granted) viewModel.emit("Notifications are disabled. Task controls still work in the app and overlay.")
    }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ProjectionService.start(context, result.resultCode, data)
            viewModel.emit("Screen capture permission granted for this session.")
            viewModel.refreshPermissions()
        } else {
            viewModel.emit("Screen capture permission was not granted. Accessibility screenshots will be used when available.")
        }
    }

    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    context.packageName,
                ),
            )
        }
    }

    val requestProjection: () -> Unit = {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    val openAccessibility: () -> Unit = {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    val openOverlay: () -> Unit = {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
    val openAppSettings: () -> Unit = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
    val openBatterySettings: () -> Unit = {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
    DisposableEffect(currentRoute) {
        val window = (context as? Activity)?.window
        if (currentRoute == "settings") window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Box(
                modifier = if (drawerState.isClosed) {
                    Modifier.semantics { hideFromAccessibility() }
                } else {
                    Modifier
                },
            ) {
                PikoNavigationDrawer(
                    navController = navController,
                    currentRoute = currentRoute,
                    onNavigate = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
                modifier = Modifier.padding(innerPadding),
            ) {
                composable("home") {
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenMenu = openMenu,
                        onStartTask = { previousId ->
                            navController.navigate("task") { launchSingleTop = true }
                            scope.launch {
                                val started = withTimeoutOrNull(10_000) {
                                    viewModel.runtime.first { it.taskId != previousId }
                                }
                                if (started != null && started.isActive && started.screenHandoffRequired) {
                                    delay(500)
                                    if (viewModel.runtime.value.isActive) {
                                        context.startActivity(Intent(Intent.ACTION_MAIN)
                                            .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }
                            }
                        },
                        onOpenTask = { navController.navigate("task") { launchSingleTop = true } },
                        onOpenHistoryTask = { id -> navController.navigate("history/$id") },
                        onOpenModels = { navController.navigate("models") },
                        onOpenAccessibility = openAccessibility,
                    )
                }
                composable("assistant") {
                    com.piko.agent.ui.screens.AssistantScreen(viewModel, onOpenMenu = openMenu)
                }
                composable("task") {
                    TaskScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onNewTask = {
                            navController.navigate("home") {
                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                            }
                        },
                    )
                }
                composable("models") {
                    ModelsScreen(
                        viewModel = viewModel,
                        onOpenMenu = openMenu,
                        onEditProvider = { id -> navController.navigate("provider/$id") },
                        onOpenSettings = { navController.navigate("settings") },
                    )
                }
                composable("provider/{providerId}") { entry ->
                    val providerId = entry.arguments?.getString("providerId").orEmpty()
                    ProviderEditorScreen(
                        viewModel = viewModel,
                        providerId = providerId,
                        onOpenKeys = { navController.navigate("settings") },
                        onBack = { navController.popBackStack() },
                        onDeleted = { navController.popBackStack() },
                    )
                }
                composable("history") {
                    HistoryScreen(
                        viewModel = viewModel,
                        onOpenMenu = openMenu,
                        onOpenTask = { id -> navController.navigate("history/$id") },
                    )
                }
                composable("history/{taskId}") { entry ->
                    val taskId = entry.arguments?.getString("taskId").orEmpty()
                    HistoryDetailScreen(
                        viewModel = viewModel,
                        taskId = taskId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenMenu = openMenu,
                        onBack = { navController.popBackStack() },
                        onOpenModels = { navController.navigate("models") },
                        onOpenAccessibility = openAccessibility,
                        onOpenOverlay = openOverlay,
                        onRequestNotifications = requestNotifications,
                        onRequestProjection = requestProjection,
                        onOpenAppSettings = openAppSettings,
                        onOpenBatterySettings = openBatterySettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun PikoNavigationDrawer(
    navController: NavHostController,
    currentRoute: String?,
    onNavigate: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.width(304.dp),
    ) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                PikoMark(size = 44)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Navigation", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Piko on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            destinations.forEach { destination ->
                val selected = currentRoute == destination.route
                NavigationDrawerItem(
                    label = { Text(destination.label, style = MaterialTheme.typography.bodyLarge) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        onNavigate()
                    },
                    shape = MaterialTheme.shapes.large,
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        selectedIconColor = MaterialTheme.colorScheme.onBackground,
                        selectedTextColor = MaterialTheme.colorScheme.onBackground,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
