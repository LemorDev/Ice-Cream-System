package com.icecreampost.pos.ui.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.icecreampost.pos.R

@Composable
fun CoolerzLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.coolerz_logo), contentDescription = "Coolerz Ice Cream logo", modifier = modifier)
}
