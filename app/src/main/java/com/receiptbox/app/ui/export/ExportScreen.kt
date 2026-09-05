package com.receiptbox.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.receiptbox.app.util.Formatters
import com.receiptbox.app.util.ShareUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    viewModel: ExportViewModel,
    onBack: () -> Unit,
    onPaywall: () -> Unit
) {
    val state by viewModel.ui.collectAsState()
    val context = LocalContext.current
    var fromText by remember(state.from) { mutableStateOf(Formatters.formatDateInput(state.from)) }
    var toText by remember(state.to) { mutableStateOf(Formatters.formatDateInput(state.to)) }

    LaunchedEffect(state.lastFile, state.lastMime) {
        val file = state.lastFile
        val mime = state.lastMime
        if (file != null && mime != null) {
            ShareUtils.shareFile(context, file, mime, "Share export")
            viewModel.consumeFile()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (state.prefs.hasPro) "Pro: clean CSV / JSON relational dump + PDF"
                else "Free: watermarked exports. Upgrade for clean dumps + full schema JSON.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = fromText,
                onValueChange = {
                    fromText = it
                    Formatters.parseDateInput(it)?.let { f ->
                        viewModel.setRange(f, state.to)
                    }
                },
                label = { Text("From (yyyy-MM-dd)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = toText,
                onValueChange = {
                    toText = it
                    Formatters.parseDateInput(it)?.let { t ->
                        viewModel.setRange(state.from, t + 86_399_000L)
                    }
                },
                label = { Text("To (yyyy-MM-dd)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = { viewModel.exportCsv(context) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export relational CSV") }
            Button(
                onClick = {
                    if (!state.prefs.hasPro) onPaywall() else viewModel.exportAccountantCsv(context)
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.prefs.hasPro) "Export IL accountant CSV (ח.פ. / VAT)" else "IL accountant CSV (Pro)")
            }
            Button(
                onClick = {
                    if (!state.prefs.hasPro) onPaywall() else viewModel.exportJson(context)
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.prefs.hasPro) "Export JSON dump" else "Export JSON (Pro)") }
            OutlinedButton(
                onClick = { viewModel.exportPdf(context) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export PDF summary") }
            Spacer(Modifier.height(8.dp))
            state.message?.let { Text(it) }
        }
    }
}
