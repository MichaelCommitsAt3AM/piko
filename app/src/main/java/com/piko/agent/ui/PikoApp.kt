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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    TopLevelDestination("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun PikoApp(viewModel: PikoViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
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
    val showBottomBar = destinations.any { it.route == currentRoute }
    DisposableEffect(currentRoute) {
        val window = (context as? Activity)?.window
        if (currentRoute == "settings") window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }


    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                PikoBottomBar(
                    navController = navController,
                    currentRoute = currentRoute,
                )
            }
        },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
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
                    onStartTask = { previousId ->
                        navController.navigate("task") { launchSingleTop = true }
                        scope.launch {
                            val started = withTimeoutOrNull(10_000) {
                                viewModel.runtime.first { it.taskId != previousId }
                            }
                            if (started != null && started.isActive) {
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

@Composable
private fun PikoBottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        destinations.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                    selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
