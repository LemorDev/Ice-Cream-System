package com.icecreampost.pos.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large, color = lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primaryContainer, 0.65f), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun SettingsLink(symbol: SettingsSymbol, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        SettingsRow(symbol, title, subtitle) { SettingsIcon(SettingsSymbol.Chevron) }
    }
}

@Composable
internal fun SettingsRow(symbol: SettingsSymbol, title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIcon(symbol)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}

internal enum class SettingsSymbol { Back, Chevron, Eye, Lock, Sync, Device, Calendar, Deductions, Preferences, Account, SignOut }

@Composable
internal fun SettingsIcon(symbol: SettingsSymbol) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        val scale = size.width / 24f
        val stroke = Stroke(1.7f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun outline(vararg points: Pair<Int, Int>, closePath: Boolean = false) {
            val path = Path().apply {
                moveTo(points.first().first * scale, points.first().second * scale)
                points.drop(1).forEach { (x, y) -> lineTo(x * scale, y * scale) }
                if (closePath) close()
            }
            drawPath(path, color, style = stroke)
        }
        when (symbol) {
            SettingsSymbol.Back -> {
                outline(12 to 6, 6 to 12, 12 to 18)
                outline(6 to 12, 20 to 12)
            }
            SettingsSymbol.Chevron -> outline(9 to 6, 15 to 12, 9 to 18)
            SettingsSymbol.Eye -> {
                outline(2 to 12, 7 to 7, 12 to 5, 17 to 7, 22 to 12, 17 to 17, 12 to 19, 7 to 17, closePath = true)
                drawCircle(color, 3f * scale, Offset(12f * scale, 12f * scale), style = stroke)
            }
            SettingsSymbol.Lock -> {
                outline(5 to 10, 19 to 10, 19 to 21, 5 to 21, closePath = true)
                outline(8 to 10, 8 to 6, 10 to 3, 14 to 3, 16 to 6, 16 to 10)
                outline(12 to 14, 12 to 17)
            }
            SettingsSymbol.Sync -> {
                outline(4 to 9, 7 to 5, 13 to 4, 19 to 8, 20 to 10)
                outline(20 to 5, 20 to 10, 15 to 10)
                outline(20 to 15, 17 to 19, 11 to 20, 5 to 16, 4 to 14)
                outline(4 to 19, 4 to 14, 9 to 14)
            }
            SettingsSymbol.Calendar -> {
                outline(4 to 5, 20 to 5, 20 to 21, 4 to 21, closePath = true)
                outline(4 to 10, 20 to 10)
                outline(8 to 3, 8 to 7)
                outline(16 to 3, 16 to 7)
            }
            SettingsSymbol.Deductions -> {
                drawCircle(color, 9f * scale, style = stroke)
                outline(8 to 12, 16 to 12)
            }
            SettingsSymbol.Preferences -> {
                outline(4 to 6, 20 to 6)
                outline(4 to 12, 20 to 12)
                outline(4 to 18, 20 to 18)
                outline(8 to 3, 8 to 9)
                outline(16 to 9, 16 to 15)
                outline(10 to 15, 10 to 21)
            }
            SettingsSymbol.Account -> {
                drawCircle(color, 4f * scale, Offset(12f * scale, 7f * scale), style = stroke)
                outline(4 to 21, 5 to 17, 8 to 15, 16 to 15, 19 to 17, 20 to 21)
            }
            SettingsSymbol.SignOut -> {
                outline(10 to 3, 4 to 3, 4 to 21, 10 to 21)
                outline(9 to 12, 21 to 12)
                outline(16 to 7, 21 to 12, 16 to 17)
            }
            SettingsSymbol.Device -> {
                outline(6 to 2, 18 to 2, 18 to 22, 6 to 22, closePath = true)
                outline(11 to 18, 13 to 18)
            }
        }
    }
}
