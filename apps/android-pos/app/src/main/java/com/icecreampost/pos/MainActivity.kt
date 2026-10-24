package com.icecreampost.pos

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import com.icecreampost.pos.ui.IceCreamPosApp
import com.icecreampost.pos.ui.theme.IceCreamPosTheme

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IceCreamPosTheme {
                IceCreamPosApp()
            }
        }
    }
}
