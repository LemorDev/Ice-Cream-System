package com.icecreampost.pos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.screen.activation.ActivationScreen
import com.icecreampost.pos.ui.screen.catalog.ProductCatalogScreen
import com.icecreampost.pos.ui.screen.history.TransactionHistoryScreen
import com.icecreampost.pos.ui.screen.home.HomeScreen
import com.icecreampost.pos.ui.screen.login.LoginScreen
import com.icecreampost.pos.ui.screen.receipt.ReceiptScreen
import com.icecreampost.pos.ui.screen.settings.SettingsScreen

@Composable
fun AppNavHost(viewModel: PosViewModel) {
    val navController = rememberNavController()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val sessionReady by viewModel.sessionReady.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(sessionReady, session, currentRoute) {
        if (sessionReady && currentRoute == Routes.LOGIN && session != null) {
            val destination = if (session?.isActivated == true) Routes.HOME else Routes.ACTIVATION
            navController.navigate(destination) {
                popUpTo(Routes.LOGIN) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(viewModel = viewModel, onSuccess = { navController.navigate(Routes.ACTIVATION) })
        }
        composable(Routes.ACTIVATION) {
            ActivationScreen(viewModel = viewModel, onSuccess = {
                navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onCatalog = { navController.navigate(Routes.CATALOG) },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.CATALOG) {
            ProductCatalogScreen(
                viewModel = viewModel,
                onSaleComplete = { navController.navigate(Routes.RECEIPT) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.RECEIPT) {
            ReceiptScreen(viewModel = viewModel, onDone = {
                viewModel.dismissReceipt()
                navController.navigate(Routes.CATALOG) { popUpTo(Routes.HOME) }
            })
        }
        composable(Routes.HISTORY) {
            TransactionHistoryScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }
    }
}
