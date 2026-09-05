package com.receiptbox.app.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.receiptbox.app.data.AnalyticsSummary
import com.receiptbox.app.data.AppPreferences
import com.receiptbox.app.data.Company
import com.receiptbox.app.data.PreferencesRepository
import com.receiptbox.app.data.ReceiptRepository
import com.receiptbox.app.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class AnalyticsFilters(
    val from: Long,
    val to: Long,
    val storeId: Long? = null,
    val companyId: Long? = null,
    val category: String? = null,
    val status: String? = null,
    val paymentMethod: String? = null,
    val period: String = "day",
    val refundsOnly: Boolean = false
)

data class AnalyticsUiState(
    val filters: AnalyticsFilters,
    val summary: AnalyticsSummary = AnalyticsSummary(),
    val stores: List<Store> = emptyList(),
    val companies: List<Company> = emptyList(),
    val loading: Boolean = false,
    val prefs: AppPreferences = AppPreferences()
)

class AnalyticsViewModel(
    private val repository: ReceiptRepository,
    preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val defaultRange: Pair<Long, Long> get() {
        val cal = Calendar.getInstance()
        val to = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -30)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        return cal.timeInMillis to to
    }

    private val _ui = MutableStateFlow(
        AnalyticsUiState(
            filters = defaultRange.let { (f, t) -> AnalyticsFilters(from = f, to = t) }
        )
    )
    val ui: StateFlow<AnalyticsUiState> = _ui.asStateFlow()

    val prefs = preferencesRepository.preferences.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences()
    )

    init {
        viewModelScope.launch {
            prefs.collect { p -> _ui.update { it.copy(prefs = p) } }
        }
        refreshMeta()
        refresh()
    }

    private fun refreshMeta() {
        viewModelScope.launch {
            _ui.update {
                it.copy(stores = repository.getStores(), companies = repository.getCompanies())
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val f = _ui.value.filters
            val status = when {
                f.refundsOnly -> "REFUND"
                else -> f.status
            }
            val summary = repository.buildAnalytics(
                from = f.from,
                to = f.to,
                storeId = f.storeId,
                companyId = f.companyId,
                status = status,
                category = f.category,
                paymentMethod = f.paymentMethod,
                period = f.period
            )
            _ui.update { it.copy(summary = summary, loading = false) }
        }
    }

    fun setStore(id: Long?) { updateFilter { it.copy(storeId = id) } }
    fun setCompany(id: Long?) { updateFilter { it.copy(companyId = id) } }
    fun setCategory(v: String?) { updateFilter { it.copy(category = v) } }
    fun setPayment(v: String?) { updateFilter { it.copy(paymentMethod = v) } }
    fun setPeriod(v: String) { updateFilter { it.copy(period = v) } }
    fun setRefundsOnly(v: Boolean) { updateFilter { it.copy(refundsOnly = v) } }
    fun setRange(from: Long, to: Long) { updateFilter { it.copy(from = from, to = to) } }

    private fun updateFilter(block: (AnalyticsFilters) -> AnalyticsFilters) {
        _ui.update { it.copy(filters = block(it.filters)) }
        refresh()
    }

    companion object {
        fun factory(repository: ReceiptRepository, preferencesRepository: PreferencesRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AnalyticsViewModel(repository, preferencesRepository) as T
                }
            }
    }
}
