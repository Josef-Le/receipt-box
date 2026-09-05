package com.receiptbox.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.receiptbox.app.data.ReceiptDetail
import com.receiptbox.app.data.ReceiptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailUiState(
    val detail: ReceiptDetail? = null,
    val loading: Boolean = true,
    val deleted: Boolean = false,
    val error: String? = null
)

class DetailViewModel(
    private val receiptId: Long,
    private val repository: ReceiptRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val detail = repository.getDetail(receiptId)
            _ui.value = DetailUiState(detail = detail, loading = false)
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.deleteReceipt(receiptId)
            _ui.value = _ui.value.copy(deleted = true)
        }
    }

    companion object {
        fun factory(id: Long, repository: ReceiptRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DetailViewModel(id, repository) as T
            }
        }
    }
}
