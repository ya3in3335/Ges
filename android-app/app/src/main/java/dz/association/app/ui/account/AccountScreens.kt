package dz.association.app.ui.account

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.BuildConfig
import dz.association.app.R
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Preferences
import dz.association.app.core.network.Result
import dz.association.app.core.prefs.AppPreferences
import dz.association.app.core.prefs.LocalPrefs
import dz.association.app.core.prefs.ThemeMode
import dz.association.app.core.push.PushManager
import dz.association.app.core.util.AppLocale
import dz.association.app.data.AuthRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PasswordField
import dz.association.app.ui.components.Pill
import dz.association.app.ui.components.roleLabel
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.SectionHeader
import dz.association.app.ui.components.errorMessage
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val prefs: AppPreferences,
    private val push: PushManager,
) : ViewModel() {
    val user = auth.user
    val local = prefs.prefs
    val error = MutableStateFlow<AppError?>(null)
    val busy = MutableStateFlow(false)
    val saved = MutableStateFlow(false)

    fun refresh() = viewModelScope.launch { if (auth.isSignedIn) auth.refreshMe() }

    fun logout(onDone: () -> Unit) = viewModelScope.launch {
        push.unlinkDevice()
        auth.logout()
        onDone()
    }

    fun setTheme(m: ThemeMode) = viewModelScope.launch { prefs.setTheme(m) }
    fun setPlayIntro(v: Boolean) = viewModelScope.launch { prefs.setPlayIntro(v) }

    fun setLanguage(tag: String) = viewModelScope.launch {
        if (auth.isSignedIn) auth.updateProfile(locale = tag)
        AppLocale.set(tag) // recreates the activity with the new locale / layout direction
        push.scheduleSync()
    }

    /** Notification preferences are stored locally (topic subscriptions) and synced to the account. */
    fun setNotifications(p: LocalPrefs, showName: Boolean? = null) = viewModelScope.launch {
        prefs.setNotifications(p.notifyNews, p.notifyActivities, p.notifyPolls, p.notifyVolunteer)
        push.scheduleSync()
        if (auth.isSignedIn) {
            val current = user.value?.preferences ?: Preferences()
            auth.updateProfile(prefs = Preferences(p.notifyNews, p.notifyActivities, p.notifyPolls, p.notifyVolunteer, showName ?: current.showNamePublic))
        }
    }

    fun changePassword(current: String, new: String, onOk: () -> Unit) = viewModelScope.launch {
        busy.value = true; error.value = null
        when (val r = auth.changePassword(current, new)) { is Result.Ok -> { saved.value = true; onOk() }; is Result.Err -> error.value = r.error }
        busy.value = false
    }

    fun deleteAccount(password: String, onDone: () -> Unit) = viewModelScope.launch {
        busy.value = true; error.value = null
        when (val r = auth.deleteAccount(password)) { is Result.Ok -> { push.unlinkDevice(); onDone() }; is Result.Err -> error.value = r.error }
        busy.value = false
    }
}


@Composable
fun AccountScreen(nav: (String) -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val user by vm.user.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.nav_account)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val u = user
            if (u == null) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        dz.association.app.ui.components.LogoImage(Modifier.height(90.dp))
                        Text(stringResource(R.string.account_guest_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.account_guest_body), style = MaterialTheme.typography.bodyMedium)
                        PrimaryButton(stringResource(R.string.login), { nav(Routes.LOGIN) })
                        OutlinedButton(onClick = { nav(Routes.REGISTER) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.register)) }
                    }
                }
            } else {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Initials avatar (no photo is collected).
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp),
                        ) {
                            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                Text(
                                    u.fullName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) },
                                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Text(u.fullName, style = MaterialTheme.typography.titleLarge)
                        Text(u.email, style = ltr.merge(MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(u.phone, style = ltr.merge(MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(roleLabel(u.role))
                            if (u.phoneVerified) Pill(stringResource(R.string.phone_verified), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                if (!u.phoneVerified) {
                    AppCard(Modifier.fillMaxWidth(), onClick = { nav(Routes.VERIFY) }) {
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
                            headlineContent = { Text(stringResource(R.string.phone_not_verified)) },
                            supportingContent = { Text(stringResource(R.string.verify_phone_telegram)) },
                        )
                    }
                } else {
                    ListItem(leadingContent = { Icon(Icons.Outlined.Verified, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text(stringResource(R.string.phone_verified)) })
                }
                AppCard(Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(modifier = Modifier.selectable(false, onClick = { nav(Routes.MY_ACTIVITIES) }), leadingContent = { Icon(Icons.Outlined.Event, null) }, headlineContent = { Text(stringResource(R.string.my_activities)) })
                        HorizontalDivider()
                        ListItem(modifier = Modifier.selectable(false, onClick = { nav(Routes.SETTINGS) }), leadingContent = { Icon(Icons.Outlined.Settings, null) }, headlineContent = { Text(stringResource(R.string.settings)) })
                    }
                }
            }
            if (u == null) {
                AppCard(Modifier.fillMaxWidth(), onClick = { nav(Routes.SETTINGS) }) {
                    ListItem(leadingContent = { Icon(Icons.Outlined.Settings, null) }, headlineContent = { Text(stringResource(R.string.settings)) })
                }
            }
            Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
fun SettingsScreen(back: () -> Unit, onLoggedOut: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val user by vm.user.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle(initialValue = LocalPrefs())
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { AppTopBar(stringResource(R.string.settings), back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.language))
            val lang = remember { AppLocale.current() }
            RadioRow(stringResource(R.string.lang_ar), lang == "ar") { vm.setLanguage("ar") }
            RadioRow(stringResource(R.string.lang_fr), lang == "fr") { vm.setLanguage("fr") }

            SectionHeader(stringResource(R.string.theme))
            listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark).forEach { (m, l) ->
                RadioRow(stringResource(l), local.theme == m) { vm.setTheme(m) }
            }
            SwitchRow(stringResource(R.string.play_intro), local.playIntro) { vm.setPlayIntro(it) }

            SectionHeader(stringResource(R.string.notifications_prefs))
            val ask = { if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            SwitchRow(stringResource(R.string.notif_news), local.notifyNews) { ask(); vm.setNotifications(local.copy(notifyNews = it)) }
            SwitchRow(stringResource(R.string.notif_activities), local.notifyActivities) { ask(); vm.setNotifications(local.copy(notifyActivities = it)) }
            SwitchRow(stringResource(R.string.notif_polls), local.notifyPolls) { ask(); vm.setNotifications(local.copy(notifyPolls = it)) }
            SwitchRow(stringResource(R.string.notif_volunteer), local.notifyVolunteer) { ask(); vm.setNotifications(local.copy(notifyVolunteer = it)) }
            SwitchRow(stringResource(R.string.notif_important), true, enabled = false) { }

            val u = user
            if (u != null) {
                SectionHeader(stringResource(R.string.privacy))
                SwitchRow(stringResource(R.string.privacy_show_name), u.preferences.showNamePublic) { vm.setNotifications(local, showName = it) }
                TextButton(onClick = { dialog = "password" }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.change_password)) }
                TextButton(onClick = { dialog = "delete" }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.logout(onLoggedOut) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.logout))
                }
            }
        }
    }

    when (dialog) {
        "password" -> {
            var cur by remember { mutableStateOf("") }
            var new by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.change_password)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PasswordField(cur, { cur = it }, stringResource(R.string.current_password))
                        PasswordField(new, { new = it }, stringResource(R.string.reset_new_password), supporting = stringResource(R.string.password_rules))
                        error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(enabled = !busy && AuthValidation.passwordValid(new), onClick = { vm.changePassword(cur, new) { dialog = null } }) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        "delete" -> {
            var pw by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.delete_account)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.delete_account_body))
                        PasswordField(pw, { pw = it }, stringResource(R.string.password))
                        error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && pw.isNotEmpty(), onClick = { scope.launch { vm.deleteAccount(pw) { dialog = null; onLoggedOut() } } }) {
                        Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
