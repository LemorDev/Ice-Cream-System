package com.icecreampost.pos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.screen.activation.ActivationScreen
import com.icecreampost.pos.ui.screen.catalog.ProductCatalogScreen
import com.icecreampost.pos.ui.screen.checkout.CartScreen
import com.icecreampost.pos.ui.screen.checkout.PaymentScreen
import com.icecreampost.pos.ui.screen.history.TransactionHistoryScreen
import com.icecreampost.pos.ui.screen.home.HomeScreen
import com.icecreampost.pos.ui.screen.login.LoginScreen
import com.icecreampost.pos.ui.screen.receipt.ReceiptScreen
import com.icecreampost.pos.ui.screen.settings.SettingsScreen

@Composable
fun AppNavHost(viewModel: PosViewModel) {
    val navController = rememberNavController()
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
                onCart = { navController.navigate(Routes.CART) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CART) {
            CartScreen(
                viewModel = viewModel,
                onPayment = { navController.navigate(Routes.PAYMENT) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PAYMENT) {
            PaymentScreen(
                viewModel = viewModel,
                onSuccess = { navController.navigate(Routes.RECEIPT) },
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
