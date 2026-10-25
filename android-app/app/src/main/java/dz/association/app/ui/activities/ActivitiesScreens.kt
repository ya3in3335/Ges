package dz.association.app.ui.activities

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.Activity
import dz.association.app.core.network.ActivityDetail
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.core.util.formatDateTime
import dz.association.app.core.util.loc
import dz.association.app.data.AuthRepository
import dz.association.app.data.ContentRepository
import dz.association.app.data.ParticipationRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.EmptyState
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.OfflineBanner
import dz.association.app.ui.components.Pill
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.RemoteImage
import dz.association.app.ui.components.UiState
import dz.association.app.ui.components.errorMessage
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActivitiesViewModel @Inject constructor(private val repo: ContentRepository) : ViewModel() {
    val phase = MutableStateFlow("upcoming")
    val state = MutableStateFlow<UiState<List<Activity>>>(UiState.Loading)
    init { load() }
    fun select(p: String) { phase.value = p; load() }
    fun load() = viewModelScope.launch {
        state.value = UiState.Loading
        state.value = when (val r = repo.activities(phase.value, 1)) {
            is Result.Ok -> UiState.Success(r.value.data.items, r.value.fromCache)
            is Result.Err -> UiState.Error(r.error)
        }
    }
}

private val PHASES = listOf("upcoming" to R.string.phase_upcoming, "ongoing" to R.string.phase_ongoing, "completed" to R.string.phase_completed)

@Composable
fun ActivitiesScreen(nav: (String) -> Unit, vm: ActivitiesViewModel = hiltViewModel()) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.activities_title)) }) { pad ->
        Column(Modifier.padding(pad)) {
            PrimaryTabRow(selectedTabIndex = PHASES.indexOfFirst { it.first == phase }) {
                PHASES.forEach { (key, label) -> Tab(selected = phase == key, onClick = { vm.select(key) }, text = { Text(stringResource(label)) }) }
            }
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> if (s.data.isEmpty()) EmptyState() else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item { OfflineBanner(s.fromCache) }
                    items(s.data, key = { it.id }) { a -> ActivityListCard(a) { nav(Routes.activity(a.id)) } }
                }
            }
        }
    }
}

@Composable
fun phaseLabel(p: String) = stringResource(
    when (p) { "ongoing" -> R.string.phase_ongoing; "completed" -> R.string.phase_completed; "cancelled" -> R.string.phase_cancelled; else -> R.string.phase_upcoming },
)

@Composable
fun ActivityListCard(a: Activity, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), onClick = onClick) {
        Column {
            Box {
                RemoteImage(a.imageUrl, null, Modifier.fillMaxWidth().aspectRatio(2.2f))
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(phaseLabel(a.phase))
                    if (a.isCampaign) Pill(stringResource(R.string.campaign), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(loc(a.titleAr, a.titleFr), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InfoLine(Icons.Outlined.CalendarMonth, formatDateTime(a.startsAt))
                a.location?.let { InfoLine(Icons.Outlined.Place, it) }
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class ActivityDetailUi(val detail: ActivityDetail, val fromCache: Boolean)

@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: ContentRepository,
    private val participation: ParticipationRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])
    val state = MutableStateFlow<UiState<ActivityDetailUi>>(UiState.Loading)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<AppError?>(null)
    val signedIn get() = auth.isSignedIn

    init { load() }
    fun load() = viewModelScope.launch {
        if (state.value !is UiState.Success) state.value = UiState.Loading
        state.value = when (val r = repo.activity(id)) {
            is Result.Ok -> UiState.Success(ActivityDetailUi(r.value.data, r.value.fromCache))
            is Result.Err -> UiState.Error(r.error)
        }
    }

    fun toggleRegistration(registered: Boolean) = viewModelScope.launch {
        busy.value = true
        val r = if (registered) participation.cancelActivity(id) else participation.registerActivity(id)
        if (r is Result.Err) message.value = r.error
        busy.value = false
        load()
    }
}

@Composable
fun ActivityDetailScreen(back: () -> Unit, nav: (String) -> Unit, vm: ActivityDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val msgText = msg?.let { errorMessage(it) }
    LaunchedEffect(msgText) { if (msgText != null) { snack.showSnackbar(msgText); vm.message.value = null } }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.activities_title), back) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> {
                    val a = s.data.detail.item
                    val mine = s.data.detail.myRegistration
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        OfflineBanner(s.data.fromCache)
                        RemoteImage(a.imageUrl, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill(phaseLabel(a.phase))
                                if (a.isCampaign) Pill(stringResource(R.string.campaign), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                            }
                            Text(loc(a.titleAr, a.titleFr), style = MaterialTheme.typography.headlineSmall)
                            InfoLine(Icons.Outlined.CalendarMonth, "${formatDateTime(a.startsAt)} — ${formatDateTime(a.endsAt)}")
                            a.location?.let { InfoLine(Icons.Outlined.Place, it) }
                            InfoLine(Icons.Outlined.Groups, if (a.capacity != null) stringResource(R.string.activity_capacity, a.registeredCount, a.capacity) else stringResource(R.string.activity_participants, a.registeredCount))
                            if (a.latitude != null && a.longitude != null) {
                                TextButton(onClick = {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${a.latitude},${a.longitude}?q=${a.latitude},${a.longitude}"))) }
                                }) { Text(stringResource(R.string.activity_open_map)) }
                            }
                            Text(loc(a.descriptionAr, a.descriptionFr), style = MaterialTheme.typography.bodyLarge)
                            a.videoUrl?.let { v ->
                                OutlinedButton(onClick = { nav(Routes.video(v)) }) {
                                    Icon(Icons.Outlined.PlayCircle, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.video_open_external))
                                }
                            }
                            if (s.data.detail.media.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(s.data.detail.media, key = { it.id }) { m ->
                                        AppCard(Modifier.size(110.dp), onClick = { nav(if (m.kind == "video") Routes.video(m.url) else Routes.photo(m.url)) }) {
                                            Box(Modifier.fillMaxSize()) {
                                                RemoteImage(m.thumbnailUrl ?: m.url.takeIf { m.kind == "photo" }, m.titleAr, Modifier.fillMaxSize())
                                                if (m.kind == "video") Icon(Icons.Outlined.PlayCircle, null, Modifier.align(Alignment.Center), tint = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            val registered = mine == "registered" || mine == "attended"
                            when {
                                registered -> {
                                    Text(stringResource(R.string.activity_registered), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                                    if (a.registrationOpen) OutlinedButton(onClick = { vm.toggleRegistration(true) }, enabled = !busy && !s.data.fromCache, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.activity_cancel_registration))
                                    }
                                }
                                !a.registrationOpen -> Text(stringResource(R.string.activity_closed), color = MaterialTheme.colorScheme.outline)
                                a.capacity != null && a.registeredCount >= a.capacity -> Text(stringResource(R.string.activity_full), color = MaterialTheme.colorScheme.error)
                                !vm.signedIn -> PrimaryButton(stringResource(R.string.login), { nav(Routes.LOGIN) })
                                else -> PrimaryButton(stringResource(R.string.activity_register), { vm.toggleRegistration(false) }, Modifier.testTag("register_activity"), enabled = !s.data.fromCache, loading = busy)
                            }
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class MyActivitiesViewModel @Inject constructor(private val participation: ParticipationRepository) : ViewModel() {
    val state = MutableStateFlow<UiState<List<Activity>>>(UiState.Loading)
    init { load() }
    fun load() = viewModelScope.launch {
        state.value = UiState.Loading
        state.value = when (val r = participation.myActivities()) { is Result.Ok -> UiState.Success(r.value); is Result.Err -> UiState.Error(r.error) }
    }
}

@Composable
fun MyActivitiesScreen(back: () -> Unit, nav: (String) -> Unit, vm: MyActivitiesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.my_activities), back) }) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> if (s.data.isEmpty()) EmptyState() else LazyColumn { items(s.data, key = { it.id }) { a -> ActivityListCard(a) { nav(Routes.activity(a.id)) } } }
            }
        }
    }
}
