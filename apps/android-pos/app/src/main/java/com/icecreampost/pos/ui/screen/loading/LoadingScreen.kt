package com.icecreampost.pos.ui.screen.loading

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.icecreampost.pos.R

@Composable
fun LoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF5E239D)) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "Coolerz Ice Cream",
                modifier = Modifier.size(132.dp),
            )
            Text(
                text = "COOLERZ POS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "Preparing your counter…",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
            )
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color(0xFFFFD77A),
                strokeWidth = 3.dp,
            )
        }
    }
}
