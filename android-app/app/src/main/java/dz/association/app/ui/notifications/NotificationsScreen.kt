package dz.association.app.ui.notifications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.AppNotification
import dz.association.app.core.network.Result
import dz.association.app.core.util.formatDateTime
import dz.association.app.core.util.loc
import dz.association.app.data.AuthRepository
import dz.association.app.data.ParticipationRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.StateMessage
import dz.association.app.ui.components.UiState
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(private val repo: ParticipationRepository, private val auth: AuthRepository) : ViewModel() {
    val state = MutableStateFlow<UiState<List<AppNotification>>>(UiState.Loading)
    val signedIn get() = auth.isSignedIn
    init { load() }
    fun load() = viewModelScope.launch {
        if (!auth.isSignedIn) { state.value = UiState.Success(emptyList()); return@launch }
        state.value = UiState.Loading
        state.value = when (val r = repo.notifications()) { is Result.Ok -> UiState.Success(r.value); is Result.Err -> UiState.Error(r.error) }
    }
}

@Composable
fun NotificationsScreen(back: () -> Unit, nav: (String) -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.notifications_title), back) }) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> if (s.data.isEmpty()) {
                    if (vm.signedIn) StateMessage(Icons.Outlined.NotificationsNone, stringResource(R.string.empty_generic))
                    else StateMessage(Icons.Outlined.NotificationsNone, stringResource(R.string.account_guest_body), stringResource(R.string.login), { nav(Routes.LOGIN) })
                } else LazyColumn {
                    items(s.data, key = { it.id }) { n ->
                        AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = Routes.fromDeepLink(n.deepLink)?.let { r -> { nav(r) } }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(loc(n.titleAr, n.titleFr), style = MaterialTheme.typography.titleSmall)
                                Text(loc(n.bodyAr, n.bodyFr), style = MaterialTheme.typography.bodyMedium)
                                Text(formatDateTime(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}
