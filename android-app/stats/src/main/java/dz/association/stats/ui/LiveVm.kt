package dz.association.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dz.association.app.core.network.Result
import dz.association.app.ui.components.UiState
import dz.association.stats.data.SyncPoller
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A screen's data that refreshes by itself when the other app changes something (see [SyncPoller]).
 * Background refreshes keep the current content on screen and never replace it with an error.
 */
abstract class LiveVm<T>(poller: SyncPoller) : ViewModel() {
    private val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state
    private var job: Job? = null

    protected abstract suspend fun fetch(): Result<T>

    init {
        viewModelScope.launch { poller.changes.collect { reload(silent = true) } }
    }

    fun reload(silent: Boolean = false) {
        if (silent && job?.isActive == true) return
        job?.cancel()
        job = viewModelScope.launch {
            val hasData = _state.value is UiState.Success
            if (!silent && !hasData) _state.value = UiState.Loading
            when (val r = fetch()) {
                is Result.Ok -> _state.value = UiState.Success(r.value)
                is Result.Err -> if (!(silent && hasData)) _state.value = UiState.Error(r.error)
            }
        }
    }

    protected fun set(value: T) { _state.value = UiState.Success(value) }
}
