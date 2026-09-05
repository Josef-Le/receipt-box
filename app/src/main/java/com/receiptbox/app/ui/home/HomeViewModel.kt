package com.receiptbox.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.receiptbox.app.data.AppConstants
import com.receiptbox.app.data.AppPreferences
import com.receiptbox.app.data.PreferencesRepository
import com.receiptbox.app.data.ReceiptListItem
import com.receiptbox.app.data.ReceiptRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val receipts: List<ReceiptListItem> = emptyList(),
    val prefs: AppPreferences = AppPreferences(),
    val remainingFree: Int = AppConstants.FREE_RECEIPT_LIMIT
)

class HomeViewModel(
    receiptRepository: ReceiptRepository,
    preferencesRepository: PreferencesRepository
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        receiptRepository.observeList(),
        preferencesRepository.preferences
    ) { receipts, prefs ->
        val used = if (prefs.hasPro) 0 else receipts.size.coerceAtLeast(prefs.freeReceiptsUsed)
        HomeUiState(
            receipts = receipts,
            prefs = prefs,
            remainingFree = if (prefs.hasPro) Int.MAX_VALUE
            else (AppConstants.FREE_RECEIPT_LIMIT - used).coerceAtLeast(0)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    companion object {
        fun factory(
            receiptRepository: ReceiptRepository,
            preferencesRepository: PreferencesRepository
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(receiptRepository, preferencesRepository) as T
            }
        }
    }
}
