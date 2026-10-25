package dz.association.app.ui.news

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.AppError
import dz.association.app.core.network.HomeNews
import dz.association.app.core.network.NewsItem
import dz.association.app.core.network.Result
import dz.association.app.core.util.formatDate
import dz.association.app.core.util.loc
import dz.association.app.data.ContentRepository
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.EmptyState
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.OfflineBanner
import dz.association.app.ui.components.Pill
import dz.association.app.ui.components.RemoteImage
import dz.association.app.ui.components.UiState
import dz.association.app.ui.home.NewsRow
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewsListState(
    val items: List<NewsItem> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val hasMore: Boolean = false,
    val page: Int = 0,
    val fromCache: Boolean = false,
    val category: String? = null,
    val query: String = "",
)

@HiltViewModel
class NewsListViewModel @Inject constructor(private val repo: ContentRepository) : ViewModel() {
    val state = MutableStateFlow(NewsListState())
    private var job: Job? = null

    init { reload() }

    fun setCategory(c: String?) { state.value = state.value.copy(category = c); reload() }
    fun setQuery(q: String) { state.value = state.value.copy(query = q) }

    fun reload() {
        state.value = state.value.copy(items = emptyList(), page = 0, hasMore = false, error = null)
        loadMore()
    }

    fun loadMore() {
        if (job?.isActive == true) return
        val s = state.value
        job = viewModelScope.launch {
            state.value = s.copy(loading = true, error = null)
            when (val r = repo.news(s.page + 1, s.category, s.query.takeIf { it.isNotBlank() })) {
                is Result.Ok -> state.value = state.value.copy(
                    items = state.value.items + r.value.data.items, page = s.page + 1, hasMore = r.value.data.hasMore,
                    loading = false, fromCache = r.value.fromCache,
                )
                is Result.Err -> state.value = state.value.copy(loading = false, error = r.error)
            }
        }
    }
}

private val CATEGORIES = listOf(null to R.string.cat_all, "announcement" to R.string.cat_announcement, "news" to R.string.cat_news, "campaign" to R.string.cat_campaign, "report" to R.string.cat_report)

@Composable
fun NewsListScreen(nav: (String) -> Unit, back: () -> Unit, vm: NewsListViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.news_title), back) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { OfflineBanner(s.fromCache) }
            item {
                OutlinedTextField(
                    value = s.query, onValueChange = vm::setQuery, singleLine = true,
                    placeholder = { Text(stringResource(R.string.news_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.reload() }),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CATEGORIES) { (key, label) ->
                        FilterChip(selected = s.category == key, onClick = { vm.setCategory(key) }, label = { Text(stringResource(label)) })
                    }
                }
            }
            if (s.items.isEmpty() && s.loading) item { FullScreenLoading() }
            if (s.items.isEmpty() && !s.loading && s.error == null) item { EmptyState() }
            if (s.items.isEmpty() && s.error != null) item { ErrorState(s.error!!, vm::reload) }
            items(s.items, key = { it.id }) { n ->
                NewsRow(HomeNews(n.id, n.titleAr, n.titleFr, n.imageUrl, n.category, n.publishedAt, n.excerptAr, n.excerptFr)) { nav(Routes.news(n.id)) }
            }
            if (s.hasMore) item {
                LaunchedEffect(s.page) { vm.loadMore() }
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@HiltViewModel
class NewsDetailViewModel @Inject constructor(handle: SavedStateHandle, private val repo: ContentRepository) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])
    val state = MutableStateFlow<UiState<NewsItem>>(UiState.Loading)
    init { load() }
    fun load() = viewModelScope.launch {
        state.value = UiState.Loading
        state.value = when (val r = repo.newsDetail(id)) {
            is Result.Ok -> UiState.Success(r.value.data.item, r.value.fromCache)
            is Result.Err -> UiState.Error(r.error)
        }
    }
}

@Composable
fun categoryLabel(c: String) = stringResource(
    when (c) { "announcement" -> R.string.cat_announcement; "campaign" -> R.string.cat_campaign; "report" -> R.string.cat_report; else -> R.string.cat_news },
)

@Composable
fun NewsDetailScreen(back: () -> Unit, @Suppress("UNUSED_PARAMETER") nav: (String) -> Unit, vm: NewsDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val shareLabel = stringResource(R.string.share)
    Scaffold(topBar = {
        AppTopBar(stringResource(R.string.news_title), back) {
            (state as? UiState.Success)?.data?.let { n ->
                IconButton(onClick = {
                    val text = loc(n.titleAr, n.titleFr) + "\n\n" + loc(n.bodyAr.orEmpty(), n.bodyFr).take(500)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), shareLabel))
                }) { Icon(Icons.Outlined.Share, shareLabel) }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    OfflineBanner(s.fromCache)
                    val n = s.data
                    if (n.imageUrl != null) RemoteImage(n.imageUrl, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Column(Modifier.padding(20.dp)) {
                        Pill(categoryLabel(n.category))
                        Spacer(Modifier.height(10.dp))
                        Text(loc(n.titleAr, n.titleFr), style = MaterialTheme.typography.headlineSmall)
                        n.publishedAt?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.published_on, formatDate(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(loc(n.bodyAr.orEmpty(), n.bodyFr), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
