package com.receiptbox.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.receiptbox.app.BuildConfig
import com.receiptbox.app.ReceiptBoxApp
import com.receiptbox.app.billing.BillingManager
import com.receiptbox.app.data.AppConstants
import com.receiptbox.app.data.PreferencesRepository
import com.receiptbox.app.ocr.TessLanguagePackManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    billingManager: BillingManager,
    preferencesRepository: PreferencesRepository,
    onBack: () -> Unit,
    onPaywall: () -> Unit
) {
    val prefs by preferencesRepository.preferences.collectAsState(
        initial = com.receiptbox.app.data.AppPreferences()
    )
    val status by billingManager.statusMessage.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val packsManager = (context.applicationContext as ReceiptBoxApp).ocrHelper.languagePacks
    val packs by packsManager.packs.collectAsState()
    val busy by packsManager.busyMessage.collectAsState()

    LaunchedEffect(Unit) { packsManager.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                if (prefs.hasPro) "Plan: Pro" else "Plan: Free (${AppConstants.FREE_RECEIPT_LIMIT} receipts)",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedButton(onClick = onPaywall, modifier = Modifier.fillMaxWidth()) {
                Text(if (prefs.hasPro) "Manage Pro" else "Upgrade to Pro")
            }
            OutlinedButton(
                onClick = { billingManager.restorePurchases() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore purchases") }
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (BuildConfig.DEBUG) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("DEBUG unlock Pro")
                        Text(
                            "Local-only bypass for testing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.debugUnlock,
                        onCheckedChange = { checked ->
                            scope.launch { preferencesRepository.setDebugUnlock(checked) }
                        }
                    )
                }
            }

            Text("OCR language packs", style = MaterialTheme.typography.titleMedium)
            Text(
                "Hebrew is first-class: Tesseract eng+heb ships in the APK (~5 MB). " +
                    "ML Kit Latin alone is insufficient (mojibake on Hebrew). Bundled: " +
                    TessLanguagePackManager.BUNDLED_CODES.joinToString(", ") +
                    ". Default pack (~28 MB) includes eng+heb+ara+rus+deu+fra+spa+por+ita+tur+pol. Download more from tessdata_fast.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            busy?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            packs.forEach { pack ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${pack.label} (${pack.code})")
                        Text(
                            buildString {
                                if (pack.installed) append("Installed") else append("Not installed")
                                if (pack.bundled) append(" · bundled")
                                append(" · ~")
                                append("%.1f".format(pack.approxBytes / 1_000_000.0))
                                append(" MB")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (pack.installed) {
                        if (pack.code !in listOf("eng", "heb")) {
                            OutlinedButton(onClick = {
                                scope.launch { packsManager.deleteLanguage(pack.code) }
                            }) { Text("Remove") }
                        } else {
                            Text("Required", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        OutlinedButton(onClick = {
                            scope.launch { packsManager.downloadLanguage(pack.code) }
                        }) { Text("Download") }
                    }
                }
            }

            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("ReceiptBox ${BuildConfig.VERSION_NAME}")
            Text(
                "Photos and OCR stay on device. Multilingual OCR: ML Kit + Tesseract. No accounts, no cloud sync in v1.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Play product: ${AppConstants.PRO_PRODUCT_ID}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
