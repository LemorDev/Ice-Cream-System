package com.icecreampost.pos.ui.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

data class PosDestination(val route: String, val label: String)
val primaryDestinations = listOf(
    PosDestination(Routes.HOME, "Home"), PosDestination(Routes.CATALOG, "Sell"),
    PosDestination(Routes.INVENTORY, "Inventory"), PosDestination(Routes.HISTORY, "History"),
    PosDestination(Routes.MORE, "More"),
)

@Composable
fun PosNavigationBar(selectedRoute: String?, onNavigate: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        primaryDestinations.forEach { destination ->
            NavigationBarItem(
                selected = selectedRoute == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = { NavigationOutlineIcon(destination.route) },
                label = { Text(destination.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun NavigationOutlineIcon(route: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val scale = size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(x * scale, y * scale), Offset(x2 * scale, y2 * scale), 1.7f * scale, StrokeCap.Round)
        fun outline(vararg points: Pair<Float, Float>, closed: Boolean = false) {
            val path = Path().apply {
                moveTo(points.first().first * scale, points.first().second * scale)
                points.drop(1).forEach { lineTo(it.first * scale, it.second * scale) }
                if (closed) close()
            }
            drawPath(path, color, style = Stroke(1.7f * scale, cap = StrokeCap.Round))
        }
        when (route) {
            Routes.HOME -> {
                outline(3f to 10f, 12f to 3f, 21f to 10f)
                outline(5f to 9f, 5f to 21f, 10f to 21f, 10f to 14f, 14f to 14f, 14f to 21f, 19f to 21f, 19f to 9f)
            }
            Routes.CATALOG -> {
                outline(2f to 3f, 5f to 3f, 8f to 16f, 19f to 16f, 22f to 7f, 6f to 7f)
                drawCircle(color, 1.5f * scale, Offset(9f * scale, 21f * scale), style = Stroke(1.5f * scale))
                drawCircle(color, 1.5f * scale, Offset(18f * scale, 21f * scale), style = Stroke(1.5f * scale))
            }
            Routes.INVENTORY -> {
                outline(3f to 7f, 12f to 3f, 21f to 7f, 21f to 18f, 12f to 22f, 3f to 18f, closed = true)
                outline(3f to 7f, 12f to 11f, 21f to 7f)
                line(12f, 11f, 12f, 22f); line(7f, 5f, 16f, 9f)
            }
            Routes.HISTORY -> {
                drawArc(color, 35f, 285f, false, Offset(3f * scale, 3f * scale), Size(18f * scale, 18f * scale), style = Stroke(1.7f * scale))
                outline(2f to 4f, 2f to 10f, 8f to 10f)
                outline(12f to 7f, 12f to 12f, 16f to 14f)
            }
            else -> listOf(5f, 12f, 19f).forEach {
                drawCircle(color, 1.5f * scale, Offset(it * scale, 12f * scale), style = Stroke(1.7f * scale))
            }
        }
    }
}
