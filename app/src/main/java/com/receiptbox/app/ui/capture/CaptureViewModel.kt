package com.receiptbox.app.ui.capture

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.receiptbox.app.data.AppConstants
import com.receiptbox.app.data.HeaderOverrides
import com.receiptbox.app.data.PreferencesRepository
import com.receiptbox.app.data.ReceiptRepository
import com.receiptbox.app.ocr.OcrHelper
import com.receiptbox.app.ocr.ParsedLineItem
import com.receiptbox.app.ocr.ParsedPayment
import com.receiptbox.app.ocr.StructuredReceiptParse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CaptureStep { Pick, Scanning, Edit }

data class CaptureUiState(
    val step: CaptureStep = CaptureStep.Pick,
    val photoUri: String? = null,
    val merchant: String = "",
    val companyName: String = "",
    val address: String = "",
    val receiptNumber: String = "",
    val amount: String = "",
    val subtotal: String = "",
    val tax: String = "",
    val currency: String = "USD",
    val dateMillis: Long = System.currentTimeMillis(),
    val category: String = "Uncategorized",
    val notes: String = "",
    val status: String = "PURCHASE",
    val lineItems: List<ParsedLineItem> = emptyList(),
    val payments: List<ParsedPayment> = emptyList(),
    val confidence: Float = 0f,
    val parse: StructuredReceiptParse? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val savedId: Long? = null,
    val needsPaywall: Boolean = false
)

class CaptureViewModel(
    private val receiptRepository: ReceiptRepository,
    private val preferencesRepository: PreferencesRepository,
    private val ocrHelper: OcrHelper
) : ViewModel() {

    private val _ui = MutableStateFlow(CaptureUiState())
    val ui: StateFlow<CaptureUiState> = _ui.asStateFlow()

    fun onPhotoSelected(uri: Uri) {
        _ui.update {
            it.copy(step = CaptureStep.Scanning, photoUri = uri.toString(), error = null)
        }
        viewModelScope.launch {
            try {
                val parsed = ocrHelper.recognizeStructured(uri)
                _ui.update { state ->
                    state.copy(
                        step = CaptureStep.Edit,
                        merchant = parsed.merchant.orEmpty(),
                        companyName = parsed.companyName.orEmpty(),
                        address = parsed.address.orEmpty(),
                        receiptNumber = parsed.receiptNumber.orEmpty(),
                        amount = parsed.total?.let { "%.2f".format(it) }.orEmpty(),
                        subtotal = parsed.subtotal?.let { "%.2f".format(it) }.orEmpty(),
                        tax = parsed.tax?.let { "%.2f".format(it) }.orEmpty(),
                        currency = parsed.currency,
                        dateMillis = parsed.datetimeMillis ?: System.currentTimeMillis(),
                        status = when {
                            parsed.isRefund -> "REFUND"
                            parsed.isVoid -> "VOID"
                            else -> "PURCHASE"
                        },
                        lineItems = parsed.lineItems,
                        payments = parsed.payments,
                        confidence = parsed.confidence,
                        parse = parsed,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        step = CaptureStep.Edit,
                        parse = StructuredReceiptParse(rawText = ""),
                        error = "OCR failed: ${e.message ?: "unknown"}. Fill fields manually."
                    )
                }
            }
        }
    }

    fun updateMerchant(v: String) = _ui.update { it.copy(merchant = v) }
    fun updateCompany(v: String) = _ui.update { it.copy(companyName = v) }
    fun updateAddress(v: String) = _ui.update { it.copy(address = v) }
    fun updateReceiptNumber(v: String) = _ui.update { it.copy(receiptNumber = v) }
    fun updateAmount(v: String) = _ui.update { it.copy(amount = v) }
    fun updateSubtotal(v: String) = _ui.update { it.copy(subtotal = v) }
    fun updateTax(v: String) = _ui.update { it.copy(tax = v) }
    fun updateCurrency(v: String) = _ui.update { it.copy(currency = v) }
    fun updateDate(millis: Long) = _ui.update { it.copy(dateMillis = millis) }
    fun updateCategory(v: String) = _ui.update { it.copy(category = v) }
    fun updateNotes(v: String) = _ui.update { it.copy(notes = v) }
    fun updateStatus(v: String) = _ui.update { it.copy(status = v) }
    fun resetToPick() = _ui.update { CaptureUiState() }

    fun save() {
        viewModelScope.launch {
            val prefs = preferencesRepository.preferences.first()
            val count = receiptRepository.count()
            if (!prefs.hasPro && count >= AppConstants.FREE_RECEIPT_LIMIT) {
                _ui.update { it.copy(needsPaywall = true) }
                return@launch
            }
            val state = _ui.value
            val uri = state.photoUri ?: run {
                _ui.update { it.copy(error = "Missing photo") }
                return@launch
            }
            _ui.update { it.copy(saving = true, error = null) }
            try {
                val base = state.parse ?: StructuredReceiptParse(rawText = "")
                val merged = base.copy(
                    lineItems = state.lineItems,
                    payments = state.payments
                )
                val id = receiptRepository.saveFromParse(
                    photoUri = uri,
                    parse = merged,
                    category = state.category,
                    notes = state.notes.trim(),
                    overrides = HeaderOverrides(
                        merchant = state.merchant.trim().ifBlank { null },
                        companyName = state.companyName.trim().ifBlank { null },
                        storeName = state.merchant.trim().ifBlank { null },
                        address = state.address.trim().ifBlank { null },
                        receiptNumber = state.receiptNumber.trim().ifBlank { null },
                        datetime = state.dateMillis,
                        currency = state.currency.trim().ifBlank { base.currency.ifBlank { "USD" } },
                        subtotal = state.subtotal.replace(',', '.').toDoubleOrNull(),
                        tax = state.tax.replace(',', '.').toDoubleOrNull(),
                        total = state.amount.replace(',', '.').toDoubleOrNull(),
                        status = state.status
                    )
                )
                if (!prefs.hasPro) preferencesRepository.incrementFreeReceiptsUsed()
                _ui.update { it.copy(saving = false, savedId = id) }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = e.message) }
            }
        }
    }

    fun consumePaywall() = _ui.update { it.copy(needsPaywall = false) }
    fun consumeSaved() = _ui.update { it.copy(savedId = null) }

    companion object {
        fun factory(
            receiptRepository: ReceiptRepository,
            preferencesRepository: PreferencesRepository,
            ocrHelper: OcrHelper
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CaptureViewModel(receiptRepository, preferencesRepository, ocrHelper) as T
            }
        }
    }
}
