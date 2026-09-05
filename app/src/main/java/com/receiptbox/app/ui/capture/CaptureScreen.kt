package com.receiptbox.app.ui.capture

import android.Manifest
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.receiptbox.app.data.Categories
import com.receiptbox.app.util.Formatters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    initialUri: String?,
    onSaved: (Long) -> Unit,
    onPaywall: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.ui.collectAsState()

    LaunchedEffect(initialUri) {
        if (!initialUri.isNullOrBlank() && state.photoUri == null) {
            viewModel.onPhotoSelected(Uri.parse(initialUri))
        }
    }
    LaunchedEffect(state.savedId) {
        state.savedId?.let {
            viewModel.consumeSaved()
            onSaved(it)
        }
    }
    LaunchedEffect(state.needsPaywall) {
        if (state.needsPaywall) {
            viewModel.consumePaywall()
            onPaywall()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.onPhotoSelected(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            CaptureStep.Pick -> "Capture"
                            CaptureStep.Scanning -> "Parsing receipt…"
                            CaptureStep.Edit -> "Review & edit"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.step == CaptureStep.Edit) viewModel.resetToPick() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (state.step) {
            CaptureStep.Pick -> PickStep(
                modifier = Modifier.fillMaxSize().padding(padding),
                onGallery = { galleryLauncher.launch("image/*") },
                onCaptured = { viewModel.onPhotoSelected(it) }
            )
            CaptureStep.Scanning -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("On-device OCR → structured entities…")
                }
            }
            CaptureStep.Edit -> EditStep(
                modifier = Modifier.fillMaxSize().padding(padding),
                state = state,
                viewModel = viewModel
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PickStep(modifier: Modifier, onGallery: () -> Unit, onCaptured: (Uri) -> Unit) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (cameraPermission.status.isGranted) {
            CameraCaptureBox(
                modifier = Modifier.fillMaxWidth().weight(1f),
                onCaptured = onCaptured
            )
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission needed to snap receipts.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                        Text("Grant camera")
                    }
                }
            }
        }
        OutlinedButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text("Choose from gallery")
        }
    }
}

@Composable
private fun CameraCaptureBox(modifier: Modifier, onCaptured: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Button(
            onClick = {
                val photoFile = createImageFile(context)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", photoFile
                )
                val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                imageCapture.takePicture(
                    output, executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                            onCaptured(uri)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("Capture", "Capture failed", exception)
                        }
                    }
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        ) {
            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text("Snap")
        }
    }
}

private fun createImageFile(context: Context): File {
    val dir = File(context.filesDir, "receipts").apply { mkdirs() }
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return File(dir, "IMG_$name.jpg")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditStep(modifier: Modifier, state: CaptureUiState, viewModel: CaptureViewModel) {
    var catExpanded by remember { mutableStateOf(false) }
    var statusExpanded by remember { mutableStateOf(false) }
    var dateText by remember(state.dateMillis) {
        mutableStateOf(Formatters.formatDateInput(state.dateMillis))
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.photoUri?.let { uri ->
            AsyncImage(
                model = uri,
                contentDescription = "Receipt photo",
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
        }
        Text(
            "Parse confidence: ${"%.0f".format(state.confidence * 100)}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(state.merchant, viewModel::updateMerchant, label = { Text("Merchant / store") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(state.companyName, viewModel::updateCompany, label = { Text("Company / brand") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(state.address, viewModel::updateAddress, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.receiptNumber, viewModel::updateReceiptNumber, label = { Text("Receipt #") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.amount, viewModel::updateAmount, label = { Text("Total") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(state.currency, viewModel::updateCurrency, label = { Text("CCY") }, modifier = Modifier.weight(0.5f), singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.subtotal, viewModel::updateSubtotal, label = { Text("Subtotal") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(state.tax, viewModel::updateTax, label = { Text("Tax") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        OutlinedTextField(
            value = dateText,
            onValueChange = {
                dateText = it
                Formatters.parseDateInput(it)?.let(viewModel::updateDate)
            },
            label = { Text("Date (yyyy-MM-dd)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = it }) {
            OutlinedTextField(
                value = state.status,
                onValueChange = {},
                readOnly = true,
                label = { Text("Status") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                listOf("PURCHASE", "REFUND", "VOID").forEach { s ->
                    DropdownMenuItem(text = { Text(s) }, onClick = { viewModel.updateStatus(s); statusExpanded = false })
                }
            }
        }

        ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
            OutlinedTextField(
                value = state.category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                Categories.ALL.forEach { cat ->
                    DropdownMenuItem(text = { Text(cat) }, onClick = { viewModel.updateCategory(cat); catExpanded = false })
                }
            }
        }

        OutlinedTextField(state.notes, viewModel::updateNotes, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

        Text("Line items (${state.lineItems.size})", style = MaterialTheme.typography.titleMedium)
        if (state.lineItems.isEmpty()) {
            Text("No line items detected — you can still save the header.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.lineItems.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "qty ${item.quantity} · unit ${item.unitPrice ?: "-"} · total ${"%.2f".format(item.lineTotal)}" +
                                (item.sku?.let { " · SKU $it" } ?: "") +
                                (item.barcode?.let { " · GTIN $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        val discounts = state.parse?.discounts.orEmpty()
        if (discounts.isNotEmpty()) {
            Text("Discounts (${discounts.size})", style = MaterialTheme.typography.titleMedium)
            discounts.forEach { d ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.description ?: "Discount", style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                d.amount?.let { "amount ${"%.2f".format(it)}" },
                                d.percent?.let { "${it}%" },
                                d.code?.let { "code $it" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (state.payments.isNotEmpty()) {
            Text("Payments", style = MaterialTheme.typography.titleMedium)
            state.payments.forEach { p ->
                Text(
                    "${p.method}: ${"%.2f".format(p.amount)}" +
                        (p.last4?.let { " ·••••$it" } ?: "") +
                        (p.authCode?.let { " · auth $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.saving) "Saving…" else "Save structured receipt")
        }
    }
}
