package com.resync.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resync.app.ui.components.AppUpdateDialog
import com.resync.app.ui.components.FloatingBottomNavBar
import com.resync.app.data.model.BackupOriginFilter
import com.resync.app.ui.navigation.Screen
import com.resync.app.ui.screens.*
import com.resync.app.ui.theme.*
import com.resync.app.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val pendingRouteFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAllPermissions()
        viewModel.refreshRootStatus(forceNewShell = false)
        viewModel.checkForUpdateOnResume()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("OPEN_ACTIVE_OPERATIONS", false) == true) {
            pendingRouteFlow.tryEmit(Screen.ActiveBackups.createRoute(fromStarted = false))
        }
        if (intent?.getBooleanExtra("OPEN_APP_UPDATE", false) == true) {
            try {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm?.cancel(com.resync.app.worker.AppUpdateWorker.NOTIFICATION_ID)
            } catch (_: Throwable) {}
            viewModel.checkForUpdateManual()
            pendingRouteFlow.tryEmit(Screen.SystemSettings.route)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val prefs = getSharedPreferences("resync_prefs", Context.MODE_PRIVATE)
        val legacyPrefs = getSharedPreferences("odin_prefs", Context.MODE_PRIVATE)
        val isSetupDone = prefs.getBoolean("setup_completed", false) || legacyPrefs.getBoolean("setup_completed", false)

        // Clean up stale notifications from previous run
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(com.resync.app.worker.FileUploadWorker.NOTIFICATION_ID)
        } catch (_: Throwable) {}

        setContent {
            val currentAccent by viewModel.themeAccent.collectAsState()
            val activeOperations by viewModel.activeOperations.collectAsStateWithLifecycle()
            val backupProgress by viewModel.backupProgress.collectAsStateWithLifecycle()
            val backupExecutionState by viewModel.backupExecutionState.collectAsStateWithLifecycle()
            val restoreProgress by viewModel.restoreProgress.collectAsStateWithLifecycle()

            val primaryAccent = getAccentColor(currentAccent)

            ReSyncTheme(accent = currentAccent) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    LaunchedEffect(Unit) {
                        pendingRouteFlow.collect { route ->
                            try {
                                navController.navigate(route) {
                                    launchSingleTop = true
                                }
                            } catch (_: Throwable) {}
                        }
                    }

                    val bottomNavRoutes = listOf(
                        Screen.Dashboard.route,
                        Screen.AppsList.route,
                        Screen.FileExplorer.route,
                        Screen.CloudSync.route,
                        Screen.Account.route
                    )
                    val showBottomNav = currentRoute in bottomNavRoutes

                    // Ao abrir o app, se houver backup ou restauração em andamento, reconectar à tela de "Operações em andamento"
                    var hasCheckedActiveBackupOnLaunch by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(activeOperations) {
                        if (!hasCheckedActiveBackupOnLaunch) {
                            val hasRunningOp = activeOperations.any { !it.isFinished && it.status != com.resync.app.engine.OperationStatus.CANCELLED && it.status != com.resync.app.engine.OperationStatus.ERROR }
                            if (hasRunningOp && currentRoute?.startsWith("active_backups") != true) {
                                hasCheckedActiveBackupOnLaunch = true
                                navController.navigate(Screen.ActiveBackups.createRoute(fromStarted = false)) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    }

                    Scaffold(
                        containerColor = DarkBg,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            val isScreenWithOwnBottomBar = currentRoute in listOf(
                                Screen.Restore.route,
                                Screen.RestoreSelection.route,
                                Screen.BackupManager.route,
                                Screen.CustomBackupWizard.route
                            )
                            val isDetailScreen = currentRoute?.startsWith("active_backups") == true

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                            ) {
                                if (!isScreenWithOwnBottomBar && !isDetailScreen) {
                                    com.resync.app.ui.components.ActiveOperationsBottomBanner(
                                        viewModel = viewModel,
                                        primaryAccent = primaryAccent,
                                        onNavigateToActive = {
                                            try {
                                                navController.navigate(Screen.ActiveBackups.createRoute(fromStarted = false)) {
                                                    launchSingleTop = true
                                                }
                                            } catch (_: Exception) {}
                                        },
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }

                                if (showBottomNav) {
                                    val dashboardNode = navController.graph.findNode(Screen.Dashboard.route)
                                    val dashboardId = dashboardNode?.id ?: navController.graph.findStartDestination().id

                                    FloatingBottomNavBar(
                                        currentRoute = currentRoute,
                                        primaryAccent = primaryAccent,
                                        onNavigateToDashboard = {
                                            Log.d("NAV", "Click Início | currentRoute=$currentRoute | destination=${Screen.Dashboard.route} | targetId=$dashboardId")
                                            viewModel.resetCloudInternalState()

                                            var navigated = false
                                            try {
                                                navController.navigate(Screen.Dashboard.route) {
                                                    popUpTo(dashboardId) {
                                                        inclusive = false
                                                        saveState = false
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = false
                                                }
                                                navigated = navController.currentBackStackEntry?.destination?.route == Screen.Dashboard.route
                                            } catch (t: Throwable) {
                                                Log.e("NAV", "navController.navigate para Dashboard falhou: ${t.message}", t)
                                                navigated = false
                                            }

                                            if (!navigated || navController.currentBackStackEntry?.destination?.route != Screen.Dashboard.route) {
                                                val popped = navController.popBackStack(dashboardId, inclusive = false)
                                                if (!popped) {
                                                    navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                                                }
                                            }
                                        },
                                        onNavigateToApps = {
                                            Log.d("NAV", "Click Aplicativos | currentRoute=$currentRoute | destination=${Screen.AppsList.route}")
                                            viewModel.resetCloudInternalState()
                                            try {
                                                navController.navigate(Screen.AppsList.route) {
                                                    popUpTo(dashboardId) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            } catch (t: Throwable) {
                                                Log.e("NAV", "Falha ao navegar para AppsList: ${t.message}", t)
                                                navController.navigate(Screen.AppsList.route) { launchSingleTop = true }
                                            }
                                        },
                                        onNavigateToFiles = {
                                            Log.d("NAV", "Click Arquivos | currentRoute=$currentRoute | destination=${Screen.FileExplorer.route}")
                                            viewModel.resetCloudInternalState()
                                            try {
                                                navController.navigate(Screen.FileExplorer.route) {
                                                    popUpTo(dashboardId) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            } catch (t: Throwable) {
                                                Log.e("NAV", "Falha ao navegar para FileExplorer: ${t.message}", t)
                                                navController.navigate(Screen.FileExplorer.route) { launchSingleTop = true }
                                            }
                                        },
                                        onNavigateToCloud = {
                                            Log.d("NAV", "Click Nuvem | currentRoute=$currentRoute | destination=${Screen.CloudSync.route}")
                                            try {
                                                navController.navigate(Screen.CloudSync.route) {
                                                    popUpTo(dashboardId) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            } catch (t: Throwable) {
                                                Log.e("NAV", "Falha ao navegar para CloudSync: ${t.message}", t)
                                                navController.navigate(Screen.CloudSync.route) { launchSingleTop = true }
                                            }
                                        },
                                        onNavigateToSettings = {
                                            Log.d("NAV", "Click Conta | currentRoute=$currentRoute | destination=${Screen.Account.route}")
                                            viewModel.resetCloudInternalState()
                                            try {
                                                navController.navigate(Screen.Account.route) {
                                                    popUpTo(dashboardId) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            } catch (t: Throwable) {
                                                Log.e("NAV", "Falha ao navegar para Account: ${t.message}", t)
                                                navController.navigate(Screen.Account.route) { launchSingleTop = true }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = if (isSetupDone) Screen.Dashboard.route else Screen.InitialSetup.route,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                composable(Screen.InitialSetup.route) {
                                    InitialSetupScreen(
                                        viewModel = viewModel,
                                        onSetupComplete = {
                                            navController.navigate(Screen.Dashboard.route) {
                                                popUpTo(Screen.InitialSetup.route) { inclusive = true }
                                            }
                                        }
                                    )
                                }

                                composable(Screen.Dashboard.route) {
                                    DashboardScreen(
                                        viewModel = viewModel,
                                        onNavigateToApps = { navController.navigate(Screen.AppsList.route) },
                                        onNavigateToFiles = {
                                            try {
                                                val dashboardId = navController.graph.findStartDestination().id
                                                navController.navigate(Screen.FileExplorer.route) {
                                                    popUpTo(dashboardId) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            } catch (t: Throwable) {
                                                navController.navigate(Screen.FileExplorer.route) { launchSingleTop = true }
                                            }
                                        },
                                        onNavigateToCloud = { navController.navigate(Screen.CloudSync.route) },
                                        onNavigateToDns = { navController.navigate(Screen.DnsSettings.route) },
                                        onNavigateToCustomWizard = { navController.navigate(Screen.CustomBackupWizard.route) },
                                        onNavigateToRestore = { navController.navigate(Screen.Restore.createRoute(source = "all", autoSingle = false)) },
                                        onNavigateToPermissions = { navController.navigate(Screen.Permissions.route) },
                                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                                        onNavigateToActiveBackups = { navController.navigate(Screen.ActiveBackups.createRoute(fromStarted = false)) },
                                        onNavigateToBackupManager = { navController.navigate(Screen.BackupManager.createRoute(source = "all", autoSingle = false)) },
                                        onNavigateToCategory = { cat -> navController.navigate(Screen.CategoryBackup.createRoute(cat)) },
                                        onNavigateToStorageDetails = { navController.navigate(Screen.StorageDetails.route) },
                                        onNavigateToScheduledBackups = { navController.navigate(Screen.ScheduledBackups.route) },
                                        onNavigateToFolders = { navController.navigate(Screen.FoldersBackup.route) }
                                    )
                                }

                                composable(Screen.AppsList.route) {
                                    AppsListScreen(
                                        viewModel = viewModel,
                                        onAppClick = { app ->
                                            viewModel.selectAppForDetail(app)
                                            navController.navigate(Screen.AppDetail.route)
                                        },
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.AppDetail.route) {
                                    val selectedApp by viewModel.selectedAppForDetail.collectAsState()
                                    if (selectedApp != null) {
                                        AppDetailScreen(
                                            app = selectedApp!!,
                                            viewModel = viewModel,
                                            onBack = { navController.popBackStack() },
                                            onSelectFiles = { navController.navigate(Screen.FileExplorer.route) },
                                            onViewBackup = { navController.navigate(Screen.BackupManager.route) }
                                        )
                                    } else {
                                        navController.popBackStack()
                                    }
                                }

                                composable(Screen.FileExplorer.route) {
                                    FileExplorerScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onStartBackupWithFiles = { paths, _ ->
                                            viewModel.launchBackup(
                                                title = "Backup de Pastas e Arquivos",
                                                selectedApps = emptyList(),
                                                selectedFiles = paths,
                                                includeSms = false,
                                                includeCalls = false,
                                                includeContacts = false,
                                                includeDns = false,
                                                includeWallpaper = false
                                            )
                                        }
                                    )
                                }

                                composable(Screen.DnsSettings.route) {
                                    DnsSettingsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.SystemSettings.route) {
                                    SettingsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onNavigateToPermissions = { navController.navigate(Screen.Permissions.route) },
                                        onNavigateToDns = { navController.navigate(Screen.DnsSettings.route) },
                                        onNavigateToInitialSetup = { navController.navigate(Screen.InitialSetup.route) }
                                    )
                                }

                                composable(Screen.Account.route) {
                                    AccountScreen(
                                        viewModel = viewModel,
                                        onNavigateToSettings = { navController.navigate(Screen.SystemSettings.route) },
                                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) }
                                    )
                                }

                                composable(Screen.StorageDetails.route) {
                                    StorageDetailsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onNavigateToAppDetail = { app ->
                                            viewModel.selectAppForDetail(app)
                                            navController.navigate(Screen.AppDetail.route)
                                        },
                                        onNavigateToFiles = {
                                            viewModel.setPreselectedFileCategory(null)
                                            navController.navigate(Screen.FileExplorer.route)
                                        },
                                        onNavigateToFilesCategory = { cat ->
                                            viewModel.setPreselectedFileCategory(cat)
                                            navController.navigate(Screen.FileExplorer.route)
                                        },
                                        onNavigateToStorageCache = {
                                            navController.navigate(Screen.StorageCache.route)
                                        },
                                        onNavigateToStorageSystem = {
                                            navController.navigate(Screen.StorageSystem.route)
                                        }
                                    )
                                }

                                composable(Screen.StorageCache.route) {
                                    StorageCacheScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.StorageSystem.route) {
                                    StorageSystemScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.ScheduledBackups.route) {
                                    ScheduledBackupsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.FoldersBackup.route) {
                                    FileExplorerScreen(
                                        viewModel = viewModel,
                                        title = "Pastas",
                                        onBack = { navController.popBackStack() },
                                        onStartBackupWithFiles = { paths, _ ->
                                            viewModel.launchBackup(
                                                title = "Backup de Pastas",
                                                selectedApps = emptyList(),
                                                selectedFiles = paths,
                                                includeSms = false,
                                                includeCalls = false,
                                                includeContacts = false,
                                                includeDns = false,
                                                includeWallpaper = false
                                            )
                                        }
                                    )
                                }


                                composable(Screen.CustomBackupWizard.route) {
                                    CustomBackupWizardScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onFinished = { navController.popBackStack(Screen.Dashboard.route, false) },
                                        onStartBackup = {
                                            navController.navigate(Screen.ActiveBackups.createRoute(fromStarted = true)) {
                                                popUpTo(Screen.Dashboard.route) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        },
                                        onNavigateToActive = {
                                            navController.navigate(Screen.ActiveBackups.createRoute(fromStarted = false)) {
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                }

                                composable(Screen.Permissions.route) {
                                    PermissionsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.CloudSync.route) {
                                    CloudSyncCenterScreen(
                                        viewModel = viewModel,
                                        onBack = {
                                            viewModel.resetCloudInternalState()
                                            navController.popBackStack()
                                        },
                                        onViewLogs = { navController.navigate(Screen.Logs.route) },
                                        onNavigateToRestore = { source ->
                                            val srcStr = if (source == com.resync.app.data.model.RestoreSource.DRIVE) "drive" else "local"
                                            navController.navigate(Screen.Restore.createRoute(source = srcStr, autoSingle = false))
                                        }
                                    )
                                }

                                composable(
                                    route = Screen.Restore.route,
                                    arguments = listOf(
                                        navArgument("source") {
                                            type = NavType.StringType
                                            defaultValue = "all"
                                        },
                                        navArgument("autoSingle") {
                                            type = NavType.BoolType
                                            defaultValue = false
                                        }
                                    )
                                ) { backStackEntry ->
                                    val sourceArg = backStackEntry.arguments?.getString("source") ?: "all"
                                    val autoSingleArg = backStackEntry.arguments?.getBoolean("autoSingle") ?: false
                                    val initialFilter = when (sourceArg.lowercase()) {
                                        "drive", "cloud" -> BackupOriginFilter.DRIVE
                                        "local", "device" -> BackupOriginFilter.LOCAL
                                        else -> BackupOriginFilter.ALL
                                    }
                                    BackupRestoreScreen(
                                        viewModel = viewModel,
                                        initialFilter = initialFilter,
                                        autoOpenSingle = autoSingleArg,
                                        onBack = { navController.popBackStack() },
                                        onNavigateToActive = {
                                            navController.navigate(Screen.ActiveBackups.createRoute(fromStarted = false)) {
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                }

                                composable(
                                    route = Screen.BackupManager.route,
                                    arguments = listOf(
                                        navArgument("source") {
                                            type = NavType.StringType
                                            defaultValue = "all"
                                        },
                                        navArgument("autoSingle") {
                                            type = NavType.BoolType
                                            defaultValue = false
                                        }
                                    )
                                ) { backStackEntry ->
                                    val sourceArg = backStackEntry.arguments?.getString("source") ?: "all"
                                    val autoSingleArg = backStackEntry.arguments?.getBoolean("autoSingle") ?: false
                                    val initialFilter = when (sourceArg.lowercase()) {
                                        "drive", "cloud" -> BackupOriginFilter.DRIVE
                                        "local", "device" -> BackupOriginFilter.LOCAL
                                        else -> BackupOriginFilter.ALL
                                    }
                                    BackupRestoreScreen(
                                        viewModel = viewModel,
                                        initialFilter = initialFilter,
                                        autoOpenSingle = autoSingleArg,
                                        onBack = { navController.popBackStack() },
                                        onNavigateToActive = {
                                            navController.navigate(Screen.ActiveBackups.createRoute(fromStarted = false)) {
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                }

                                composable(Screen.Logs.route) {
                                    LogsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(
                                    route = Screen.ActiveBackups.route,
                                    arguments = listOf(
                                        navArgument("fromStarted") {
                                            type = NavType.BoolType
                                            defaultValue = false
                                        }
                                    )
                                ) { backStackEntry ->
                                    val fromStarted = backStackEntry.arguments?.getBoolean("fromStarted") ?: false
                                    com.resync.app.ui.screens.ActiveBackupsScreen(
                                        viewModel = viewModel,
                                        fromJustStarted = fromStarted,
                                        onNavigateBack = { navController.popBackStack() },
                                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) }
                                    )
                                }

                                composable(
                                    route = Screen.CategoryBackup.route,
                                    arguments = listOf(
                                        navArgument("category") {
                                            type = NavType.StringType
                                            defaultValue = "messages"
                                        }
                                    )
                                ) { backStackEntry ->
                                    val categoryArg = backStackEntry.arguments?.getString("category") ?: "messages"
                                    CategoryBackupScreen(
                                        category = categoryArg,
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }

                            // Auto-updater dialog
                            AppUpdateDialog(viewModel = viewModel, primaryAccent = primaryAccent)
                        }
                    }
                }
            }
        }
    }
}


