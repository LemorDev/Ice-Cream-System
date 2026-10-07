package com.icecreampost.pos.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.AppEnvironmentBanner
import com.icecreampost.pos.ui.screen.activation.ActivationScreen
import com.icecreampost.pos.ui.screen.catalog.ProductCatalogScreen
import com.icecreampost.pos.ui.screen.history.TransactionHistoryScreen
import com.icecreampost.pos.ui.screen.home.HomeScreen
import com.icecreampost.pos.ui.screen.inventory.InventoryScreen
import com.icecreampost.pos.ui.screen.login.LoginScreen
import com.icecreampost.pos.ui.screen.loading.LoadingScreen
import com.icecreampost.pos.ui.screen.more.MoreScreen
import com.icecreampost.pos.ui.screen.operations.OperationsScreen
import com.icecreampost.pos.ui.screen.receipt.ReceiptScreen
import com.icecreampost.pos.ui.screen.receipt.HistoricalReceiptScreen
import com.icecreampost.pos.ui.screen.settings.*

@Composable
fun AppNavHost(viewModel: PosViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val sessionReady by viewModel.sessionReady.collectAsStateWithLifecycle()
    if (!sessionReady) { LoadingScreen(); return }
    // Discard saved tab stacks at a session boundary as well as visible routes.
    key(session?.userId, session?.isActivated) {
        val navController = rememberNavController()
        val entry by navController.currentBackStackEntryAsState()
        val currentRoute = entry?.destination?.route
        val businessDay by viewModel.businessDay.collectAsStateWithLifecycle()
        val isOpen = businessDay != null && businessDay?.closedAt == null && businessDay?.stallId == session?.stallId
        var focusMode by rememberSaveable { mutableStateOf(false) }
        val focusedSale = focusMode && isOpen && currentRoute in listOf(Routes.CATALOG, Routes.RECEIPT)
        val activity = LocalContext.current.findActivity()

        LaunchedEffect(isOpen, currentRoute) {
            if (!isOpen || currentRoute !in listOf(Routes.CATALOG, Routes.RECEIPT)) focusMode = false
        }
        DisposableEffect(focusedSale, activity) {
            val controller = activity?.let { WindowInsetsControllerCompat(it.window, it.window.decorView) }
            if (focusedSale) {
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                if (focusedSale) controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        val start = when {
            session == null -> Routes.LOGIN
            session?.isActivated != true -> Routes.ACTIVATION
            else -> Routes.HOME
        }
        val moreRoutes = listOf(Routes.MORE, Routes.OPERATING_DAY, Routes.DEDUCTIONS, Routes.SYNC, Routes.SETTINGS, Routes.DEVICE)
        val showNavigation = !focusedSale && session?.isActivated == true &&
            (primaryDestinations.any { it.route == currentRoute } || currentRoute in moreRoutes)
        fun openTab(route: String) {
            navController.navigate(route) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = { if (!focusedSale) AppEnvironmentBanner() },
            bottomBar = {
                if (showNavigation) PosNavigationBar(
                    selectedRoute = if (currentRoute in moreRoutes) Routes.MORE else currentRoute,
                    onNavigate = ::openTab,
                )
            },
        ) { padding ->
            NavHost(navController, startDestination = start,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                composable(Routes.LOGIN) { LoginScreen(viewModel) }
                composable(Routes.ACTIVATION) {
                    ActivationScreen(viewModel, onSuccess = {}) // Activation state selects Home.
                }
                composable(Routes.HOME) {
                    HomeScreen(viewModel, onCatalog = { openTab(Routes.CATALOG) },
                        onOperatingDay = { navController.navigate(Routes.OPERATING_DAY) },
                        onInventory = { openTab(Routes.INVENTORY) })
                }
                composable(Routes.CATALOG) {
                    ProductCatalogScreen(viewModel, onSaleComplete = {
                        navController.navigate(Routes.RECEIPT) { launchSingleTop = true }
                    }, onOperatingDay = { navController.navigate(Routes.OPERATING_DAY) },
                        focusMode = focusedSale, onToggleFocusMode = { focusMode = !focusMode })
                }
                composable(Routes.RECEIPT) {
                    ReceiptScreen(viewModel, onDone = {
                        viewModel.dismissReceipt()
                        navController.popBackStack(Routes.CATALOG, inclusive = false)
                    })
                }
                composable(Routes.HISTORY) {
                    TransactionHistoryScreen(viewModel, onReceipt = { id ->
                        viewModel.viewHistoricalReceipt(id)
                        navController.navigate(Routes.HISTORY_RECEIPT) { launchSingleTop = true }
                    })
                }
                composable(Routes.HISTORY_RECEIPT) {
                    HistoricalReceiptScreen(viewModel, onBack = {
                        viewModel.dismissHistoricalReceipt(); navController.popBackStack()
                    })
                }
                composable(Routes.INVENTORY) { InventoryScreen(viewModel) }
                composable(Routes.MORE) { MoreScreen(viewModel, onNavigate = { navController.navigate(it) }) }
                composable(Routes.OPERATING_DAY) { OperationsScreen(viewModel, onBack = { navController.popBackStack() }) }
                composable(Routes.DEDUCTIONS) { OperationsScreen(viewModel, onBack = { navController.popBackStack() }, deductionsOnly = true) }
                composable(Routes.SETTINGS) { SettingsScreen(viewModel, onBack = { navController.popBackStack() }) }
                composable(Routes.SYNC) { SyncScreen(viewModel, onBack = { navController.popBackStack() }) }
                composable(Routes.DEVICE) { DeviceInformationScreen(viewModel, onBack = { navController.popBackStack() }) }
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
