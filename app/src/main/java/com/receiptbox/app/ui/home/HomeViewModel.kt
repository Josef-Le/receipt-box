package com.receiptbox.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.receiptbox.app.data.AppConstants
import com.receiptbox.app.data.AppPreferences
import com.receiptbox.app.data.PreferencesRepository
import com.receiptbox.app.data.ReceiptListItem
import com.receiptbox.app.data.ReceiptRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val receipts: List<ReceiptListItem> = emptyList(),
    val prefs: AppPreferences = AppPreferences(),
    val remainingFree: Int = AppConstants.FREE_RECEIPT_LIMIT,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val totalCount: Int = 0
)

class HomeViewModel(
    private val receiptRepository: ReceiptRepository,
    preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val searchResults = MutableStateFlow<List<ReceiptListItem>?>(null)
    private var searchJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        receiptRepository.observeList(),
        preferencesRepository.preferences,
        searchQuery,
        searchResults
    ) { allReceipts, prefs, query, results ->
        val used = if (prefs.hasPro) 0 else allReceipts.size.coerceAtLeast(prefs.freeReceiptsUsed)
        val shown = if (query.isBlank()) allReceipts else (results ?: emptyList())
        HomeUiState(
            receipts = shown,
            prefs = prefs,
            remainingFree = if (prefs.hasPro) Int.MAX_VALUE
            else (AppConstants.FREE_RECEIPT_LIMIT - used).coerceAtLeast(0),
            searchQuery = query,
            searching = query.isNotBlank() && results == null,
            totalCount = allReceipts.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults.value = null
            return
        }
        searchResults.value = null
        searchJob = viewModelScope.launch {
            searchResults.value = receiptRepository.search(query)
        }
    }

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
