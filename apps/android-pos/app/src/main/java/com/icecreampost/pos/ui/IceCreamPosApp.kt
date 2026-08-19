package com.icecreampost.pos.ui

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.icecreampost.pos.ui.navigation.AppNavHost

@Composable
fun IceCreamPosApp() {
    AppNavHost(viewModel = hiltViewModel())
}
