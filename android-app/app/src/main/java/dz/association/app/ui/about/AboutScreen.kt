package dz.association.app.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.BuildConfig
import dz.association.app.R
import dz.association.app.core.network.Result
import dz.association.app.core.util.locOrNull
import dz.association.app.data.ContentRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(private val repo: ContentRepository) : ViewModel() {
    val state = MutableStateFlow<UiState<Map<String, String>>>(UiState.Loading)
    init { load() }
    fun load() = viewModelScope.launch {
        state.value = UiState.Loading
        state.value = when (val r = repo.settings()) { is Result.Ok -> UiState.Success(r.value.data.settings, r.value.fromCache); is Result.Err -> UiState.Error(r.error) }
    }
}

@Composable
fun AboutScreen(back: () -> Unit, vm: AboutViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Scaffold(topBar = { AppTopBar(stringResource(R.string.about_title), back) }) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> {
                    val m = s.data
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Official logo, proportions preserved.
                        Image(painterResource(R.drawable.logo), locOrNull(m["association_name_ar"], m["association_name_fr"]), Modifier.fillMaxWidth(0.8f))
                        locOrNull(m["association_name_ar"], m["association_name_fr"])?.let { Text(it, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center) }
                        Text(locOrNull(m["about_ar"], m["about_fr"]) ?: stringResource(R.string.about_empty), style = MaterialTheme.typography.bodyLarge)
                        AppCard(Modifier.fillMaxWidth()) {
                            Column {
                                @Composable fun row(icon: ImageVector, label: Int, value: String?, intent: (() -> Intent)?) {
                                    if (value.isNullOrBlank()) return
                                    ListItem(
                                        leadingContent = { Icon(icon, null) },
                                        headlineContent = { Text(value) },
                                        overlineContent = { Text(stringResource(label)) },
                                        modifier = if (intent != null) Modifier.clickable { runCatching { ctx.startActivity(intent()) } } else Modifier,
                                    )
                                }
                                row(Icons.Outlined.Phone, R.string.contact_phone, m["contact_phone"]) { Intent(Intent.ACTION_DIAL, Uri.parse("tel:${m["contact_phone"]}")) }
                                row(Icons.Outlined.Email, R.string.contact_email, m["contact_email"]) { Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${m["contact_email"]}")) }
                                row(Icons.Outlined.Place, R.string.contact_address, locOrNull(m["contact_address_ar"], m["contact_address_fr"]), null)
                                listOf("website_url", "facebook_url", "instagram_url", "youtube_url", "telegram_channel_url").forEach { k ->
                                    m[k]?.takeIf { it.startsWith("https://") }?.let { url -> row(Icons.Outlined.Language, R.string.home_contact, url) { Intent(Intent.ACTION_VIEW, Uri.parse(url)) } }
                                }
                            }
                        }
                        Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Box(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
