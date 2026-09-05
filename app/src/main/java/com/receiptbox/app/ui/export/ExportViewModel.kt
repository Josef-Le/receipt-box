package com.receiptbox.app.ui.export

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.receiptbox.app.data.AppPreferences
import com.receiptbox.app.data.PreferencesRepository
import com.receiptbox.app.data.ReceiptRepository
import com.receiptbox.app.export.ExportWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

data class ExportUiState(
    val from: Long,
    val to: Long,
    val busy: Boolean = false,
    val message: String? = null,
    val lastFile: File? = null,
    val lastMime: String? = null,
    val prefs: AppPreferences = AppPreferences()
)

class ExportViewModel(
    private val repository: ReceiptRepository,
    preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val range: Pair<Long, Long> get() {
        val cal = Calendar.getInstance()
        val to = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -90)
        return cal.timeInMillis to to
    }

    private val _ui = MutableStateFlow(range.let { (f, t) -> ExportUiState(from = f, to = t) })
    val ui: StateFlow<ExportUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.preferences.collect { p -> _ui.update { it.copy(prefs = p) } }
        }
    }

    fun setRange(from: Long, to: Long) = _ui.update { it.copy(from = from, to = to) }

    fun exportCsv(context: Context) = runExport(context, "text/csv") { bundle, wm ->
        ExportWriter.writeRelationalCsv(context, bundle, wm)
    }

    fun exportJson(context: Context) = runExport(context, "application/json") { bundle, wm ->
        ExportWriter.writeJson(context, bundle, wm)
    }

    fun exportPdf(context: Context) = runExport(context, "application/pdf") { bundle, wm ->
        ExportWriter.writePdfSummary(context, bundle.details, wm)
    }

    private fun runExport(
        context: Context,
        mime: String,
        writer: (com.receiptbox.app.data.ExportBundle, Boolean) -> File
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            try {
                val state = _ui.value
                val bundle = repository.exportBundle(state.from, state.to)
                val wm = !state.prefs.hasPro
                val file = writer(bundle, wm)
                _ui.update {
                    it.copy(
                        busy = false,
                        lastFile = file,
                        lastMime = mime,
                        message = "Exported ${bundle.details.size} receipts → ${file.name}"
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, message = e.message) }
            }
        }
    }

    fun consumeFile() = _ui.update { it.copy(lastFile = null, lastMime = null) }

    companion object {
        fun factory(repository: ReceiptRepository, preferencesRepository: PreferencesRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ExportViewModel(repository, preferencesRepository) as T
                }
            }
    }
}
