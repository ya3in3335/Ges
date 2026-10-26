package dz.association.admin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dz.association.app.ui.components.EmptyState
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.ListSkeleton

/** Lazy list with skeleton, error, empty and infinite-scroll states. */
@Composable
fun <T> PagedList(
    s: ListState<T>,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    header: LazyListScope.() -> Unit = {},
    row: LazyListScope.(List<T>) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        header()
        when {
            s.items.isEmpty() && s.loading -> item { ListSkeleton(4) }
            s.items.isEmpty() && s.error != null -> item { ErrorState(s.error, onRetry) }
            s.items.isEmpty() -> item { EmptyState() }
            else -> row(s.items)
        }
        if (s.hasMore) item {
            LaunchedEffect(s.page) { onMore() }
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
