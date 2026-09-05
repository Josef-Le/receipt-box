package com.receiptbox.app.ui.paywall

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.receiptbox.app.billing.BillingManager
import com.receiptbox.app.data.AppConstants
import com.receiptbox.app.data.PreferencesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    billingManager: BillingManager,
    preferencesRepository: PreferencesRepository,
    onBack: () -> Unit
) {
    val prefs by preferencesRepository.preferences.collectAsState(
        initial = com.receiptbox.app.data.AppPreferences()
    )
    val status by billingManager.statusMessage.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ReceiptBox Pro") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                if (prefs.hasPro) "You're on Pro — thank you!"
                else "One-time unlock ${billingManager.formattedPrice()}",
                style = MaterialTheme.typography.headlineMedium
            )
            Feature("Unlimited receipts", "Free stops at ${AppConstants.FREE_RECEIPT_LIMIT}.", Icons.Outlined.AllInclusive)
            Feature("Full analytics", "Store/product/period/payment filters + correlations.", Icons.Outlined.Analytics)
            Feature("Clean exports", "CSV + JSON relational dump without watermark.", Icons.Outlined.CleaningServices)

            if (!prefs.hasPro) {
                Button(
                    onClick = {
                        val activity = context as? Activity ?: return@Button
                        billingManager.launchPurchase(activity)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Unlock Pro · ${billingManager.formattedPrice()}") }
            }
            OutlinedButton(
                onClick = { billingManager.restorePurchases() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore purchase") }
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Product ID: ${AppConstants.PRO_PRODUCT_ID}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Feature(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
