package dz.association.app.ui.polls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Poll
import dz.association.app.core.network.PollDetail
import dz.association.app.core.network.Result
import dz.association.app.core.util.Connectivity
import dz.association.app.core.util.formatDate
import dz.association.app.core.util.formatDateTime
import dz.association.app.core.util.loc
import dz.association.app.data.AuthRepository
import dz.association.app.data.ParticipationRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.EmptyState
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.Pill
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.UiState
import dz.association.app.ui.components.errorMessage
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PollsViewModel @Inject constructor(private val repo: ParticipationRepository) : ViewModel() {
    val tab = MutableStateFlow("active")
    val state = MutableStateFlow<UiState<List<Poll>>>(UiState.Loading)
    init { load() }
    fun select(t: String) { tab.value = t; load() }
    fun load() = viewModelScope.launch {
        state.value = UiState.Loading
        state.value = when (val r = repo.polls(tab.value)) { is Result.Ok -> UiState.Success(r.value); is Result.Err -> UiState.Error(r.error) }
    }
}

private val TABS = listOf("active" to R.string.poll_active, "upcoming" to R.string.poll_upcoming, "closed" to R.string.poll_closed)

@Composable
fun PollsScreen(nav: (String) -> Unit, vm: PollsViewModel = hiltViewModel()) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.polls_title)) }) { pad ->
        Column(Modifier.padding(pad)) {
            PrimaryTabRow(selectedTabIndex = TABS.indexOfFirst { it.first == tab }) {
                TABS.forEach { (k, l) -> Tab(selected = tab == k, onClick = { vm.select(k) }, text = { Text(stringResource(l)) }) }
            }
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> if (s.data.isEmpty()) EmptyState() else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(s.data, key = { it.id }) { p ->
                        AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { nav(Routes.poll(p.id)) }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(loc(p.titleAr, p.titleFr), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (p.state == "upcoming") stringResource(R.string.poll_starts, formatDate(p.startsAt)) else stringResource(R.string.poll_ends, formatDate(p.endsAt)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (p.hasVoted) Pill(stringResource(R.string.poll_already_voted), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                                    if (p.anonymous) Pill(stringResource(R.string.poll_anonymous))
                                    if (p.eligibility == "members") Pill(stringResource(R.string.poll_members_only))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class PollDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: ParticipationRepository,
    auth: AuthRepository,
    connectivity: Connectivity,
) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])
    val state = MutableStateFlow<UiState<PollDetail>>(UiState.Loading)
    val voting = MutableStateFlow(false)
    val voteError = MutableStateFlow<AppError?>(null)
    val justVoted = MutableStateFlow<String?>(null)
    val online = connectivity.online.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), connectivity.isOnline())
    val signedIn = auth.isSignedIn

    init { load() }
    fun load() = viewModelScope.launch {
        if (state.value !is UiState.Success) state.value = UiState.Loading
        state.value = when (val r = repo.poll(id)) { is Result.Ok -> UiState.Success(r.value); is Result.Err -> UiState.Error(r.error) }
    }

    /** A vote is only shown as recorded after the server confirms it (with a receipt). */
    fun vote(optionId: String) = viewModelScope.launch {
        voting.value = true
        voteError.value = null
        when (val r = repo.vote(id, optionId)) {
            is Result.Ok -> justVoted.value = r.value.receipt
            is Result.Err -> voteError.value = r.error
        }
        voting.value = false
        load()
    }
}

@Composable
fun PollDetailScreen(back: () -> Unit, nav: (String) -> Unit, vm: PollDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val voting by vm.voting.collectAsStateWithLifecycle()
    val voteError by vm.voteError.collectAsStateWithLifecycle()
    val justVoted by vm.justVoted.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.polls_title), back) }) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> {
                    val d = s.data
                    val p = d.poll
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(loc(p.titleAr, p.titleFr), style = MaterialTheme.typography.headlineSmall)
                        locDesc(p)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        Text(stringResource(R.string.poll_ends, formatDateTime(p.endsAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (p.anonymous) Pill(stringResource(R.string.poll_anonymous))
                            if (p.eligibility == "members") Pill(stringResource(R.string.poll_members_only))
                        }

                        if (d.hasVoted) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(stringResource(if (justVoted != null) R.string.poll_voted else R.string.poll_already_voted), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        d.receipt?.let { Text(stringResource(R.string.poll_receipt, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                                    }
                                }
                            }
                        }

                        val canVote = p.state == "active" && !d.hasVoted && d.eligible
                        d.options.forEach { o ->
                            val count = d.results?.byOption?.get(o.id) ?: 0
                            val total = d.results?.totalVotes ?: 0
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth().testTag("option_${o.id}").selectable(
                                    selected = selected == o.id, enabled = canVote, role = Role.RadioButton, onClick = { selected = o.id },
                                ),
                                border = BorderStroke(if (selected == o.id) 2.dp else 1.dp, if (selected == o.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (canVote) RadioButton(selected = selected == o.id, onClick = null)
                                        if (canVote) Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(loc(o.labelAr, o.labelFr), style = MaterialTheme.typography.titleSmall)
                                            o.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        }
                                        if (d.results != null) Text("$count", style = MaterialTheme.typography.titleSmall)
                                    }
                                    if (d.results != null) {
                                        Spacer(Modifier.height(8.dp))
                                        LinearProgressIndicator(progress = { if (total == 0) 0f else count.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }

                        if (d.results != null) Text(stringResource(R.string.poll_total_votes, d.results?.totalVotes ?: 0), style = MaterialTheme.typography.labelLarge)
                        else if (p.resultVisibility == "after_close") Text(stringResource(R.string.poll_results_hidden), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        else if (p.resultVisibility == "admins") Text(stringResource(R.string.poll_results_admins), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                        voteError?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error); dz.association.app.ui.components.NetworkDetail(it) }

                        if (p.state == "active" && !d.hasVoted) {
                            when {
                                !vm.signedIn -> { Hint(stringResource(R.string.poll_need_login)); PrimaryButton(stringResource(R.string.login), { nav(Routes.LOGIN) }) }
                                d.ineligibleReason == "phone_not_verified" -> { Hint(stringResource(R.string.poll_need_verify)); PrimaryButton(stringResource(R.string.verify_phone_telegram), { nav(Routes.VERIFY) }) }
                                d.ineligibleReason == "not_on_member_list" -> Hint(stringResource(R.string.poll_not_member))
                                !online -> Hint(stringResource(R.string.poll_offline))
                                else -> PrimaryButton(stringResource(R.string.poll_vote), { confirm = true }, Modifier.testTag("vote_button"), enabled = selected != null, loading = voting)
                            }
                        }
                    }
                    val opt = d.options.firstOrNull { it.id == selected }
                    if (confirm && opt != null) {
                        AlertDialog(
                            onDismissRequest = { confirm = false },
                            icon = { Icon(Icons.Outlined.Lock, null) },
                            title = { Text(stringResource(R.string.poll_confirm_title)) },
                            text = { Text(stringResource(R.string.poll_confirm_body, loc(opt.labelAr, opt.labelFr))) },
                            confirmButton = { TextButton(onClick = { confirm = false; vm.vote(opt.id) }) { Text(stringResource(R.string.confirm)) } },
                            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
                        )
                    }
                }
            }
        }
    }
}

private fun locDesc(p: Poll): String? = p.descriptionAr?.let { loc(it, p.descriptionFr) }

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
