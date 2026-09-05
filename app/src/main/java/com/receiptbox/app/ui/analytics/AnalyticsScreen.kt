package com.receiptbox.app.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.receiptbox.app.data.Categories
import com.receiptbox.app.data.PeriodSpend
import com.receiptbox.app.util.Formatters
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel,
    onBack: () -> Unit,
    onPaywall: () -> Unit
) {
    val state by viewModel.ui.collectAsState()
    val hasPro = state.prefs.hasPro

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (!hasPro) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Full analytics is a Pro feature", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Unlock spend by store/product/period, discount rate, payment mix, and correlations.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onPaywall) { Text("Upgrade to Pro") }
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FiltersBlock(state, viewModel)
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            val s = state.summary
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryCard("Spend", Formatters.formatMoney(s.totalSpend, "USD"), Modifier.weight(1f))
                SummaryCard("Receipts", s.receiptCount.toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryCard("Avg basket", Formatters.formatMoney(s.avgBasket, "USD"), Modifier.weight(1f))
                SummaryCard("Refunds", s.refundCount.toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryCard("Discounts", Formatters.formatMoney(s.discountTotal, "USD"), Modifier.weight(1f))
                SummaryCard("Discount rate", "${"%.1f".format(s.discountRate * 100)}%", Modifier.weight(1f))
            }

            Text("Spend over time", style = MaterialTheme.typography.titleLarge)
            SimpleBarChart(s.byPeriod, MaterialTheme.colorScheme.primary)

            Text("By store", style = MaterialTheme.typography.titleLarge)
            s.byStore.take(10).forEach { row ->
                RankRow(
                    title = row.storeName,
                    subtitle = "${row.receiptCount} receipts · avg ${Formatters.formatMoney(row.avgBasket, "USD")}" +
                        (row.companyName?.let { " · $it" } ?: ""),
                    value = Formatters.formatMoney(row.totalSpend, "USD")
                )
            }

            Text("Top products / SKUs", style = MaterialTheme.typography.titleLarge)
            s.byProduct.take(15).forEach { row ->
                RankRow(
                    title = row.name,
                    subtitle = buildString {
                        append("qty ${"%.1f".format(row.quantity)} · ${row.receiptCount} receipts")
                        row.sku?.let { append(" · SKU $it") }
                        row.barcode?.let { append(" · $it") }
                    },
                    value = Formatters.formatMoney(row.totalSpend, "USD")
                )
            }

            Text("By payment method", style = MaterialTheme.typography.titleLarge)
            s.byPayment.forEach { row ->
                RankRow(row.method, "${row.receiptCount} receipts", Formatters.formatMoney(row.totalSpend, "USD"))
            }

            if (s.topProductsAtTopStore.isNotEmpty() && s.byStore.isNotEmpty()) {
                Text(
                    "Top products at ${s.byStore.first().storeName}",
                    style = MaterialTheme.typography.titleLarge
                )
                s.topProductsAtTopStore.forEach { row ->
                    RankRow(row.name, "qty ${"%.1f".format(row.quantity)}", Formatters.formatMoney(row.totalSpend, "USD"))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersBlock(state: AnalyticsUiState, viewModel: AnalyticsViewModel) {
    var storeExp by remember { mutableStateOf(false) }
    var companyExp by remember { mutableStateOf(false) }
    var catExp by remember { mutableStateOf(false) }
    var payExp by remember { mutableStateOf(false) }

    Text("Filters", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.filters.period == "day",
            onClick = { viewModel.setPeriod("day") },
            label = { Text("Day") }
        )
        FilterChip(
            selected = state.filters.period == "week",
            onClick = { viewModel.setPeriod("week") },
            label = { Text("Week") }
        )
        FilterChip(
            selected = state.filters.period == "month",
            onClick = { viewModel.setPeriod("month") },
            label = { Text("Month") }
        )
        FilterChip(
            selected = state.filters.refundsOnly,
            onClick = { viewModel.setRefundsOnly(!state.filters.refundsOnly) },
            label = { Text("Refunds") }
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 30, 90).forEach { days ->
            FilterChip(
                selected = false,
                onClick = {
                    val cal = Calendar.getInstance()
                    val to = cal.timeInMillis
                    cal.add(Calendar.DAY_OF_YEAR, -days)
                    viewModel.setRange(cal.timeInMillis, to)
                },
                label = { Text("${days}d") }
            )
        }
    }

    ExposedDropdownMenuBox(expanded = storeExp, onExpandedChange = { storeExp = it }) {
        OutlinedTextField(
            value = state.stores.firstOrNull { it.id == state.filters.storeId }?.name ?: "All stores",
            onValueChange = {},
            readOnly = true,
            label = { Text("Store") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(storeExp) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = storeExp, onDismissRequest = { storeExp = false }) {
            DropdownMenuItem(text = { Text("All stores") }, onClick = { viewModel.setStore(null); storeExp = false })
            state.stores.forEach { s ->
                DropdownMenuItem(text = { Text(s.name) }, onClick = { viewModel.setStore(s.id); storeExp = false })
            }
        }
    }

    ExposedDropdownMenuBox(expanded = companyExp, onExpandedChange = { companyExp = it }) {
        OutlinedTextField(
            value = state.companies.firstOrNull { it.id == state.filters.companyId }?.legalName ?: "All companies",
            onValueChange = {},
            readOnly = true,
            label = { Text("Company") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(companyExp) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = companyExp, onDismissRequest = { companyExp = false }) {
            DropdownMenuItem(text = { Text("All companies") }, onClick = { viewModel.setCompany(null); companyExp = false })
            state.companies.forEach { c ->
                DropdownMenuItem(text = { Text(c.legalName) }, onClick = { viewModel.setCompany(c.id); companyExp = false })
            }
        }
    }

    ExposedDropdownMenuBox(expanded = catExp, onExpandedChange = { catExp = it }) {
        OutlinedTextField(
            value = state.filters.category ?: "All categories",
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExp) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = catExp, onDismissRequest = { catExp = false }) {
            DropdownMenuItem(text = { Text("All categories") }, onClick = { viewModel.setCategory(null); catExp = false })
            Categories.ALL.forEach { c ->
                DropdownMenuItem(text = { Text(c) }, onClick = { viewModel.setCategory(c); catExp = false })
            }
        }
    }

    ExposedDropdownMenuBox(expanded = payExp, onExpandedChange = { payExp = it }) {
        OutlinedTextField(
            value = state.filters.paymentMethod ?: "All payments",
            onValueChange = {},
            readOnly = true,
            label = { Text("Payment method") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(payExp) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = payExp, onDismissRequest = { payExp = false }) {
            DropdownMenuItem(text = { Text("All payments") }, onClick = { viewModel.setPayment(null); payExp = false })
            listOf("CASH", "CARD", "MOBILE", "CHECK", "GIFT_CARD", "UNKNOWN").forEach { m ->
                DropdownMenuItem(text = { Text(m) }, onClick = { viewModel.setPayment(m); payExp = false })
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RankRow(title: String, subtitle: String, value: String) {
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SimpleBarChart(periods: List<PeriodSpend>, color: Color) {
    if (periods.isEmpty()) {
        Text("No data in range", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val max = periods.maxOf { kotlin.math.abs(it.totalSpend) }.coerceAtLeast(1.0)
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                val barWidth = size.width / (periods.size * 1.4f)
                periods.forEachIndexed { i, p ->
                    val h = (kotlin.math.abs(p.totalSpend) / max * size.height * 0.9f).toFloat()
                    val x = i * barWidth * 1.4f
                    drawRect(
                        color = if (p.totalSpend < 0) Color(0xFFEF4444) else color,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barWidth, h)
                    )
                }
            }
            Text(
                "${periods.first().periodKey} → ${periods.last().periodKey}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
