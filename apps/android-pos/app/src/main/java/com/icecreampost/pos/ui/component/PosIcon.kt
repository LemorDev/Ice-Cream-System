package com.icecreampost.pos.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class PosSymbol { Search, Add, Bell, Trend, Orders, ChevronUp, Packaging, Ingredient }

@Composable
internal fun PosIcon(symbol: PosSymbol, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(20.dp)) {
        val scale = size.width / 24f
        val stroke = Stroke(1.8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun outline(vararg points: Pair<Int, Int>, closed: Boolean = false) {
            val path = Path().apply {
                moveTo(points.first().first * scale, points.first().second * scale)
                points.drop(1).forEach { (x, y) -> lineTo(x * scale, y * scale) }
                if (closed) close()
            }
            drawPath(path, color, style = stroke)
        }
        when (symbol) {
            PosSymbol.Search -> {
                drawCircle(color, 6f * scale, Offset(10f * scale, 10f * scale), style = stroke)
                outline(15 to 15, 21 to 21)
            }
            PosSymbol.Add -> {
                drawCircle(color, 9f * scale, style = stroke)
                outline(8 to 12, 16 to 12)
                outline(12 to 8, 12 to 16)
            }
            PosSymbol.Bell -> {
                outline(4 to 17, 6 to 14, 6 to 9, 8 to 5, 12 to 3, 16 to 5, 18 to 9, 18 to 14, 20 to 17, closed = true)
                outline(10 to 21, 14 to 21)
            }
            PosSymbol.Trend -> {
                outline(3 to 17, 9 to 11, 13 to 15, 21 to 7)
                outline(15 to 7, 21 to 7, 21 to 13)
            }
            PosSymbol.Orders -> {
                outline(5 to 3, 19 to 3, 19 to 21, 5 to 21, closed = true)
                outline(9 to 8, 15 to 8)
                outline(9 to 12, 15 to 12)
                outline(9 to 16, 12 to 16)
            }
            PosSymbol.ChevronUp -> outline(6 to 15, 12 to 9, 18 to 15)
            PosSymbol.Packaging -> {
                outline(4 to 6, 20 to 6, 17 to 21, 7 to 21, closed = true)
                outline(3 to 3, 21 to 3)
            }
            PosSymbol.Ingredient -> {
                outline(7 to 3, 17 to 3, 17 to 7, 20 to 10, 20 to 21, 4 to 21, 4 to 10, 7 to 7, closed = true)
                outline(7 to 7, 17 to 7)
                outline(8 to 13, 16 to 13, 16 to 17, 8 to 17, closed = true)
            }
        }
    }
}
