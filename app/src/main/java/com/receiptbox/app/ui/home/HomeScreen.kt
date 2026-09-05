package com.receiptbox.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.receiptbox.app.data.AppConstants
import com.receiptbox.app.data.ReceiptListItem
import com.receiptbox.app.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onCapture: () -> Unit,
    onOpen: (Long) -> Unit,
    onExport: () -> Unit,
    onAnalytics: () -> Unit,
    onSettings: () -> Unit,
    onPaywall: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ReceiptBox") },
                actions = {
                    if (!state.prefs.hasPro) {
                        IconButton(onClick = onPaywall) {
                            Icon(Icons.Outlined.WorkspacePremium, contentDescription = "Upgrade")
                        }
                    }
                    IconButton(onClick = onAnalytics) {
                        Icon(Icons.Outlined.Analytics, contentDescription = "Analytics")
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = "Export")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (!state.prefs.hasPro && state.receipts.size >= AppConstants.FREE_RECEIPT_LIMIT) {
                    onPaywall()
                } else {
                    onCapture()
                }
            }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add receipt")
            }
        }
    ) { padding ->
        if (state.receipts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Outlined.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.height(72.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("No receipts yet", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Snap a receipt — we parse line items, taxes, discounts, and payments on-device.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onCapture) { Text("Scan first receipt") }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (!state.prefs.hasPro) {
                    Text(
                        text = "Free plan: ${state.receipts.size}/${AppConstants.FREE_RECEIPT_LIMIT} receipts · Upgrade for full analytics",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.receipts, key = { it.id }) { receipt ->
                        ReceiptRow(receipt = receipt, onClick = { onOpen(receipt.id) })
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ReceiptRow(receipt: ReceiptListItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    receipt.merchantDisplay.ifBlank { receipt.storeName ?: "Unknown" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                val meta = buildList {
                    add(Formatters.formatDate(receipt.datetime))
                    add(receipt.category)
                    if (receipt.status != "PURCHASE") add(receipt.status)
                    receipt.paymentMethod?.let { add(it) }
                }.joinToString(" · ")
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                Formatters.formatMoney(receipt.total, receipt.currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (receipt.status == "REFUND") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}
