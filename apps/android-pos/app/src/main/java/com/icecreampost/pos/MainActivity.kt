package com.icecreampost.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import com.icecreampost.pos.ui.IceCreamPosApp
import com.icecreampost.pos.ui.theme.IceCreamPosTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IceCreamPosTheme {
                IceCreamPosApp()
            }
        }
    }
}
