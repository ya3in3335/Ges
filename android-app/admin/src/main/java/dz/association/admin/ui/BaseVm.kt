package dz.association.admin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class ListState<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val error: AppError? = null,
)

/** Paged list + one-shot actions with shared busy/error state. */
abstract class ListVm<T> : ViewModel() {
    val state = MutableStateFlow(ListState<T>())
    val busy = MutableStateFlow(false)
    val actionError = MutableStateFlow<AppError?>(null)
    private var job: Job? = null

    /** Returns the items of [page] and whether more pages exist. */
    protected abstract suspend fun fetch(page: Int): Result<Pair<List<T>, Boolean>>

    fun reload() {
        job?.cancel()
        state.value = ListState(loading = true)
        loadMore()
    }

    fun loadMore() {
        if (job?.isActive == true) return
        val s = state.value
        job = viewModelScope.launch {
            state.value = s.copy(loading = true, error = null)
            state.value = when (val r = fetch(s.page + 1)) {
                is Result.Ok -> s.copy(items = s.items + r.value.first, page = s.page + 1, hasMore = r.value.second, loading = false)
                is Result.Err -> s.copy(loading = false, error = r.error)
            }
        }
    }

    /** Runs a write, reloads the list on success and reports errors to the screen. */
    fun act(reloadAfter: Boolean = true, onOk: () -> Unit = {}, block: suspend () -> Result<*>) = viewModelScope.launch {
        busy.value = true
        actionError.value = null
        when (val r = block()) {
            is Result.Ok -> { onOk(); if (reloadAfter) reload() }
            is Result.Err -> actionError.value = r.error
        }
        busy.value = false
    }
}
