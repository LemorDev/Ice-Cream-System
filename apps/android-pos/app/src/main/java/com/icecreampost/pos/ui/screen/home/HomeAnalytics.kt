package com.icecreampost.pos.ui.screen.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.ui.component.businessZone
import com.icecreampost.pos.ui.component.formatMoney
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class AnalyticsPeriod(
    val start: LocalDate,
    val end: LocalDate,
    val revenueCents: Long,
    val orders: Int,
)

internal fun salesAnalytics(
    transactions: List<TransactionEntity>,
    stallId: String?,
    today: LocalDate,
    days: Int,
): List<AnalyticsPeriod> {
    require(days == 7 || days == 30)
    val daily = transactions.asSequence()
        .filter { it.stallId == stallId && it.status == "completed" && it.deletedAt == null }
        .mapNotNull { transaction ->
            val date = runCatching { Instant.parse(transaction.occurredAt).atZone(businessZone).toLocalDate() }
                .getOrNull() ?: runCatching { LocalDate.parse(transaction.occurredAt.take(10)) }.getOrNull()
            date?.let { it to transaction }
        }
        .groupBy({ it.first }, { it.second })
    val first = today.minusDays((days - 1).toLong())
    val bucketDays = if (days == 7) 1 else 5
    return (0 until days / bucketDays).map { index ->
        val start = first.plusDays((index * bucketDays).toLong())
        val end = start.plusDays((bucketDays - 1).toLong())
        val sales = (0 until bucketDays).flatMap { offset -> daily[start.plusDays(offset.toLong())].orEmpty() }
        AnalyticsPeriod(start, end, sales.sumOf { it.totalCents }, sales.size)
    }
}

@Composable
internal fun HomeAnalytics(transactions: List<TransactionEntity>, stallId: String?, amountsVisible: Boolean) {
    var days by rememberSaveable { mutableIntStateOf(7) }
    var selectedStart by rememberSaveable { mutableStateOf<String?>(null) }
    val today = LocalDate.now(businessZone)
    val periods = remember(transactions, stallId, today, days) { salesAnalytics(transactions, stallId, today, days) }
    val selected = periods.firstOrNull { it.start.toString() == selectedStart } ?: periods.last()
    val maxRevenue = periods.maxOf { it.revenueCents }.coerceAtLeast(1L)
    val maxOrders = periods.maxOf { it.orders }.coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }

    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sales analytics", style = MaterialTheme.typography.titleLarge)
            Text("Completed sales stored here · tap a bar to inspect it",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30).forEach { option ->
                    FilterChip(selected = days == option, onClick = { days = option; selectedStart = null },
                        label = { Text("$option days") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AnalyticsLegend(primary, if (amountsVisible) "Sales" else "Sales hidden")
                AnalyticsLegend(secondary, "Orders")
            }
            Row(Modifier.fillMaxWidth().height(142.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                periods.forEach { period ->
                    val active = selected.start == period.start
                    val name = if (days == 7) period.start.dayOfWeek.name.take(3).lowercase()
                        .replaceFirstChar { it.uppercase() }
                    else "${period.start.dayOfMonth}–${period.end.dayOfMonth}"
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                MaterialTheme.shapes.small)
                            .clickable { selectedStart = period.start.toString() }
                            .semantics {
                                contentDescription = "$name, ${formatMoney(period.revenueCents, amountsVisible)} sales, ${period.orders} orders"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Row(Modifier.height(104.dp), verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (amountsVisible) Box(Modifier.width(9.dp).height(barHeight(period.revenueCents.toDouble() / maxRevenue)).background(primary, MaterialTheme.shapes.small))
                            Box(Modifier.width(9.dp).height(barHeight(period.orders.toDouble() / maxOrders)).background(secondary, MaterialTheme.shapes.small))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val dateLabel = if (selected.start == selected.end) selected.start.format(dateFormat)
                else "${selected.start.format(dateFormat)} – ${selected.end.format(dateFormat)}"
            Text("$dateLabel · ${formatMoney(selected.revenueCents, amountsVisible)} sales · ${selected.orders} orders",
                style = MaterialTheme.typography.bodyMedium)
            Text("Each bar color uses its own scale.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun barHeight(fraction: Double) = (if (fraction <= 0) 3.0 else 8.0 + 88.0 * fraction).dp

@Composable
private fun AnalyticsLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
