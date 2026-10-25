package dz.association.app.ui.donations

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.DonationsResponse
import dz.association.app.core.network.Result
import dz.association.app.core.util.loc
import dz.association.app.core.util.locOrNull
import dz.association.app.data.ContentRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.EmptyState
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.OfflineBanner
import dz.association.app.ui.components.SectionHeader
import dz.association.app.ui.components.UiState
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DonationsViewModel @Inject constructor(private val repo: ContentRepository) : ViewModel() {
    val state = MutableStateFlow<UiState<DonationsResponse>>(UiState.Loading)
    init { load() }
    fun load() = viewModelScope.launch {
        state.value = UiState.Loading
        state.value = when (val r = repo.donations()) { is Result.Ok -> UiState.Success(r.value.data, r.value.fromCache); is Result.Err -> UiState.Error(r.error) }
    }
}

private fun copy(ctx: Context, label: String, value: String, done: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(ctx, done, Toast.LENGTH_SHORT).show()
}

@Composable
private fun CopyRow(label: String, value: String) {
    val ctx = LocalContext.current
    val done = stringResource(R.string.copied)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr))
        }
        IconButton(onClick = { copy(ctx, label, value, done) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy)) }
    }
}

@Composable
fun DonationsScreen(back: () -> Unit, nav: (String) -> Unit, vm: DonationsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.donations_title), back) }) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> {
                    val d = s.data.donation
                    val hasAny = listOf(d.ccpAccount, d.bankAccount, d.instructionsAr).any { !it.isNullOrBlank() }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        OfflineBanner(s.fromCache)
                        if (!hasAny) EmptyState(stringResource(R.string.donations_empty))
                        if (!d.ccpAccount.isNullOrBlank()) AppCard(Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.donations_ccp), style = MaterialTheme.typography.titleMedium)
                                CopyRow(stringResource(R.string.donations_ccp), d.ccpAccount)
                                d.ccpKey?.let { CopyRow(stringResource(R.string.donations_ccp_key), it) }
                                d.ccpHolder?.let { Text("${stringResource(R.string.donations_holder)}: $it", style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        if (!d.bankAccount.isNullOrBlank()) AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(d.bankName ?: stringResource(R.string.donations_bank), style = MaterialTheme.typography.titleMedium)
                                CopyRow(stringResource(R.string.donations_bank), d.bankAccount)
                                d.bankHolder?.let { Text("${stringResource(R.string.donations_holder)}: $it", style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        locOrNull(d.instructionsAr, d.instructionsFr)?.let {
                            SectionHeader(stringResource(R.string.donations_instructions))
                            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                        if (s.data.campaigns.isNotEmpty()) {
                            SectionHeader(stringResource(R.string.donations_campaigns))
                            s.data.campaigns.forEach { c ->
                                AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { nav(Routes.activity(c.id)) }) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(loc(c.titleAr, c.titleFr), style = MaterialTheme.typography.titleSmall)
                                        locOrNull(c.descriptionAr, c.descriptionFr)?.let { Text(it, maxLines = 3, style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.outline)
                            Text(stringResource(R.string.donations_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}
