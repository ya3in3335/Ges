package dz.association.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CoPresent
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import dz.association.admin.ui.adminError
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.admin.R
import dz.association.admin.data.AdminApi
import dz.association.admin.data.AdminRoles
import dz.association.admin.data.AdminSession
import dz.association.admin.ui.ConfirmDialog
import dz.association.admin.ui.ErrorText
import dz.association.admin.ui.Module
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.LogoImage
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.Skeleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActivationVm @Inject constructor(private val session: AdminSession) : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    /** null = form, "pending" = waiting for the owner, "rejected"/"expired" = finished. */
    val stage = MutableStateFlow<String?>(if (session.pendingRequestId != null) "pending" else null)

    fun request(name: String) = viewModelScope.launch {
        busy.value = true; error.value = null
        when (val r = session.requestActivation(name)) { is Result.Ok -> stage.value = "pending"; is Result.Err -> error.value = r.error }
        busy.value = false
    }

    /** Polls every 4 s while the screen is visible; stops on a final answer. */
    suspend fun waitForDecision() {
        while (stage.value == "pending") {
            when (val r = session.checkActivation()) {
                is Result.Ok -> if (r.value != "pending") stage.value = if (r.value == "approved") "approved" else r.value
                is Result.Err -> Unit
            }
            kotlinx.coroutines.delay(4_000)
        }
    }

    fun restart() { session.cancelRequest(); stage.value = null; error.value = null }

    fun activateWithCode(code: String) = viewModelScope.launch {
        busy.value = true; error.value = null
        val r = session.activate(code)
        if (r is Result.Err) error.value = r.error
        busy.value = false
    }
}

/**
 * First launch: the supervisor sends an activation request with their name; the owner accepts it
 * from the Telegram bot and the app opens by itself. A one-time code remains available as a fallback.
 */
@Composable
fun ActivationScreen(vm: ActivationVm = hiltViewModel()) {
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var useCode by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val stage by vm.stage.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(stage) {
        if (stage == "pending") lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { vm.waitForDecision() }
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        LogoImage(Modifier.height(130.dp))
        Text(stringResource(R.string.act_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        when {
            stage == "pending" -> {
                androidx.compose.material3.CircularProgressIndicator()
                Text(stringResource(R.string.req_waiting), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(stringResource(R.string.req_waiting_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                androidx.compose.material3.TextButton(onClick = { vm.restart() }) { Text(stringResource(R.string.req_cancel)) }
            }
            stage == "rejected" || stage == "expired" || stage == "claimed" -> {
                Text(stringResource(if (stage == "rejected") R.string.req_rejected else R.string.req_expired), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                PrimaryButton(stringResource(R.string.req_again), { vm.restart() })
            }
            !useCode -> {
                Text(stringResource(R.string.req_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text(stringResource(R.string.req_name)) }, singleLine = true, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth())
                error?.let { Text(if ((it as? AppError.Api)?.code == "approval_not_configured") stringResource(R.string.req_not_configured) else adminError(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                PrimaryButton(stringResource(R.string.req_button), { vm.request(name) }, enabled = name.trim().length >= 2, loading = busy)
                androidx.compose.material3.TextButton(onClick = { useCode = true }) { Text(stringResource(R.string.req_have_code)) }
            }
            else -> {
                Text(stringResource(R.string.act_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter(Char::isDigit).take(8) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 6.sp, textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(if ((it as? AppError.Api)?.code == "invalid_code") stringResource(R.string.act_invalid) else adminError(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                PrimaryButton(stringResource(R.string.act_button), { vm.activateWithCode(code) }, enabled = code.length == 8, loading = busy)
                androidx.compose.material3.TextButton(onClick = { useCode = false }) { Text(stringResource(R.string.req_use_bot)) }
            }
        }
    }
}

@HiltViewModel
class HomeVm @Inject constructor(private val api: AdminApi, private val session: AdminSession) : ViewModel() {
    val stats = MutableStateFlow<Map<String, Int>?>(null)
    val error = MutableStateFlow<AppError?>(null)
    val refreshing = MutableStateFlow(false)
    val user = session.user
    init { load() }
    fun load() = viewModelScope.launch {
        refreshing.value = true
        session.refresh() // picks up role changes made by another administrator
        when (val r = apiCall { api.overview() }) { is Result.Ok -> { stats.value = r.value; error.value = null }; is Result.Err -> error.value = r.error }
        refreshing.value = false
    }
    fun disconnect() = viewModelScope.launch { session.disconnect() }
}

private val STATS = listOf(
    "users" to R.string.s_users, "verifiedUsers" to R.string.s_verified, "newUsersLast7Days" to R.string.s_new7,
    "pendingVolunteers" to R.string.s_pending_vol, "approvedVolunteers" to R.string.s_approved_vol, "activePolls" to R.string.s_active_polls,
    "upcomingActivities" to R.string.s_upcoming_acts, "publishedNews" to R.string.s_news, "verificationsLast24h" to R.string.s_verif24,
    "channelBacklog" to R.string.s_backlog,
)

private data class Tile(val module: Module, val label: Int, val icon: ImageVector)
private val TILES = listOf(
    Tile(Module.NEWS, R.string.a_news, Icons.Outlined.Newspaper),
    Tile(Module.LECTURES, dz.association.app.core.R.string.lectures_title, Icons.Outlined.CoPresent),
    Tile(Module.ACTIVITIES, R.string.a_activities, Icons.Outlined.Event),
    Tile(Module.POLLS, R.string.a_polls, Icons.Outlined.HowToVote),
    Tile(Module.VOLUNTEERS, R.string.a_volunteers, Icons.Outlined.VolunteerActivism),
    Tile(Module.USERS, R.string.a_users, Icons.Outlined.Group),
    Tile(Module.DEVICES, R.string.dev_title, Icons.Outlined.PhoneAndroid),
    Tile(Module.MEDIA, R.string.a_media, Icons.Outlined.PhotoLibrary),
    Tile(Module.NOTIFICATIONS, R.string.a_notifications, Icons.Outlined.Notifications),
    Tile(Module.DONATIONS, R.string.a_donations, Icons.Outlined.Savings),
    Tile(Module.TELEGRAM, R.string.a_telegram, Icons.Outlined.Send),
    Tile(Module.AUDIT, R.string.a_audit, Icons.Outlined.History),
    Tile(Module.SETTINGS, R.string.a_settings, Icons.Outlined.Settings),
)

@Composable
fun HomeScreen(role: String, open: (Module) -> Unit, vm: HomeVm = hiltViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    PullToRefreshBox(refreshing, onRefresh = { vm.load() }, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.statusBarsPadding()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoImage(Modifier.height(56.dp).width(86.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.a_hello, user?.fullName?.substringBefore(' ').orEmpty()), style = MaterialTheme.typography.titleLarge)
                        Text(dz.association.app.ui.components.roleLabel(role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { confirmLogout = true }) { Icon(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.dev_disconnect)) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.a_overview), style = MaterialTheme.typography.titleMedium) }
            if (error != null && stats == null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorText(error) }
            if (stats == null && error == null) items(6) { Skeleton(Modifier.fillMaxWidth().height(84.dp)) }
            stats?.let { st ->
                items(STATS) { (k, l) ->
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("${st[k] ?: 0}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(l), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(4.dp)) }
            items(TILES.filter { AdminRoles.atLeast(role, it.module.minRole) }) { t ->
                AppCard(Modifier.fillMaxWidth(), onClick = { open(t.module) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(t.label), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
    if (confirmLogout) ConfirmDialog(stringResource(R.string.dev_disconnect_confirm), { vm.disconnect() }, { confirmLogout = false })
}

