package com.receiptbox.app.ui.detail

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.receiptbox.app.util.Formatters
import com.receiptbox.app.util.ShareUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val state by viewModel.ui.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.receipt?.merchantDisplay ?: "Receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    state.detail?.let { d ->
                        IconButton(onClick = {
                            ShareUtils.shareImageUri(context, Uri.parse(d.receipt.photoUri))
                        }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share photo")
                        }
                        IconButton(onClick = viewModel::delete) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loading -> BoxLoading(Modifier.fillMaxSize().padding(padding))
            state.detail == null -> Text("Not found", modifier = Modifier.padding(padding).padding(16.dp))
            else -> {
                val d = state.detail!!
                val r = d.receipt
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AsyncImage(
                        model = r.photoUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                    Text(Formatters.formatMoney(r.total, r.currency), style = MaterialTheme.typography.headlineMedium)
                    Meta("Status", r.status)
                    Meta("Date", Formatters.formatDate(r.datetime))
                    Meta("Category", r.category)
                    r.receiptNumber?.let { Meta("Receipt #", it) }
                    r.cashier?.let { Meta("Cashier", it) }
                    r.registerId?.let { Meta("Register", it) }
                    d.store?.let {
                        Meta("Store", it.name)
                        it.address?.let { a -> Meta("Address", a) }
                        it.phone?.let { p -> Meta("Phone", p) }
                    }
                    d.company?.let {
                        Meta("Company", it.legalName)
                        it.taxId?.let { t -> Meta("Tax ID", t) }
                    }
                    r.subtotal?.let { Meta("Subtotal", Formatters.formatMoney(it, r.currency)) }
                    r.tax?.let { Meta("Tax", Formatters.formatMoney(it, r.currency)) }
                    Meta("Parse confidence", "${"%.0f".format(r.parseConfidence * 100)}%")
                    d.originalReceipt?.let {
                        Meta("Linked original", "#${it.receiptNumber ?: it.id} · ${Formatters.formatMoney(it.total, it.currency)}")
                    }
                    if (r.notes.isNotBlank()) Meta("Notes", r.notes)

                    Text("Line items", style = MaterialTheme.typography.titleLarge)
                    d.lineItems.forEach { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "qty ${item.quantity} × ${item.unitPrice ?: "-"} = ${"%.2f".format(item.lineTotal)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (d.discounts.isNotEmpty()) {
                        Text("Discounts", style = MaterialTheme.typography.titleLarge)
                        d.discounts.forEach { disc ->
                            Text(
                                "${disc.description ?: disc.code ?: "Discount"}: " +
                                    (disc.amount?.let { "%.2f".format(it) } ?: "") +
                                    (disc.percent?.let { " (${it}%)" } ?: "")
                            )
                        }
                    }

                    if (d.payments.isNotEmpty()) {
                        Text("Payments", style = MaterialTheme.typography.titleLarge)
                        d.payments.forEach { p ->
                            Text(
                                "${p.method}: ${Formatters.formatMoney(p.amount, r.currency)}" +
                                    (p.last4?.let { " ·••••$it" } ?: "")
                            )
                        }
                    }

                    d.rawOcr?.let {
                        Text("Raw OCR (audit)", style = MaterialTheme.typography.titleLarge)
                        Text(
                            it.rawText.take(2000),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BoxLoading(modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
    }
}
