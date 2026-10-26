package dz.association.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.admin.R
import dz.association.admin.data.AdminApi
import dz.association.admin.data.AdminRoles
import dz.association.admin.data.DonationInput
import dz.association.admin.data.NotificationLog
import dz.association.admin.data.SendNotification
import dz.association.admin.ui.Choice
import dz.association.admin.ui.ConfirmDialog
import dz.association.admin.ui.ErrorText
import dz.association.admin.ui.Field
import dz.association.admin.ui.Tag
import dz.association.admin.ui.blankToNull
import dz.association.app.core.network.AppError
import dz.association.app.core.network.DonationInfo
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.PrimaryButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Load-once / save form state shared by the settings-like screens. */
abstract class FormVm<T> : ViewModel() {
    val data = MutableStateFlow<T?>(null)
    val error = MutableStateFlow<AppError?>(null)
    val busy = MutableStateFlow(false)
    val saved = MutableStateFlow(false)
    protected abstract suspend fun load(): Result<T>
    fun reload() = viewModelScope.launch {
        error.value = null
        when (val r = load()) { is Result.Ok -> data.value = r.value; is Result.Err -> error.value = r.error }
    }
    protected fun save(block: suspend () -> Result<T>) = viewModelScope.launch {
        busy.value = true; error.value = null; saved.value = false
        when (val r = block()) { is Result.Ok -> { data.value = r.value; saved.value = true }; is Result.Err -> error.value = r.error }
        busy.value = false
    }
}

@Composable
private fun <T> FormScreen(title: String, back: () -> Unit, vm: FormVm<T>, note: String?, body: @Composable (T) -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(title, back) }) { pad ->
        Box(Modifier.padding(pad)) {
            val d = data
            when {
                d == null && error != null -> ErrorState(error!!, { vm.reload() })
                d == null -> FullScreenLoading()
                else -> Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    body(d)
                    ErrorText(error)
                    if (saved) Text(stringResource(dz.association.app.core.R.string.saved), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ───────────────────────── Donations ─────────────────────────

@HiltViewModel
class DonationsVm @Inject constructor(private val api: AdminApi) : FormVm<DonationInfo>() {
    init { reload() }
    override suspend fun load() = apiCall { api.donations().donation }
    fun submit(i: DonationInput) = save { apiCall { api.updateDonations(i).donation } }
}

@Composable
fun DonationsScreen(back: () -> Unit, vm: DonationsVm = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    FormScreen(stringResource(R.string.a_donations), back, vm, stringResource(R.string.d_note)) { d ->
        val f = remember(d) {
            mutableStateMapOf(
                "ccpAccount" to d.ccpAccount.orEmpty(), "ccpKey" to d.ccpKey.orEmpty(), "ccpHolder" to d.ccpHolder.orEmpty(),
                "bankName" to d.bankName.orEmpty(), "bankAccount" to d.bankAccount.orEmpty(), "bankHolder" to d.bankHolder.orEmpty(),
                "instructionsAr" to d.instructionsAr.orEmpty(), "instructionsFr" to d.instructionsFr.orEmpty(),
                "contactPhone" to d.contactPhone.orEmpty(), "contactEmail" to d.contactEmail.orEmpty(),
            )
        }
        @Composable fun field(k: String, label: String, lines: Int = 1, ltr: Boolean = false, kb: KeyboardType = KeyboardType.Text) =
            Field(f[k].orEmpty(), { f[k] = it }, label, lines, ltr, kb)
        field("ccpAccount", stringResource(dz.association.app.core.R.string.donations_ccp), ltr = true, kb = KeyboardType.Number)
        field("ccpKey", stringResource(dz.association.app.core.R.string.donations_ccp_key), ltr = true, kb = KeyboardType.Number)
        field("ccpHolder", stringResource(dz.association.app.core.R.string.donations_holder))
        HorizontalDivider()
        field("bankName", stringResource(R.string.d_bank_name))
        field("bankAccount", stringResource(dz.association.app.core.R.string.donations_bank), ltr = true, kb = KeyboardType.Number)
        field("bankHolder", stringResource(dz.association.app.core.R.string.donations_holder))
        HorizontalDivider()
        field("instructionsAr", stringResource(R.string.d_instructions_ar), lines = 4)
        field("instructionsFr", stringResource(R.string.d_instructions_fr), lines = 3, ltr = true)
        field("contactPhone", stringResource(R.string.d_phone), ltr = true, kb = KeyboardType.Phone)
        field("contactEmail", stringResource(R.string.d_email), ltr = true, kb = KeyboardType.Email)
        PrimaryButton(stringResource(dz.association.app.core.R.string.save), {
            vm.submit(DonationInput(
                f["ccpAccount"]?.blankToNull(), f["ccpKey"]?.blankToNull(), f["ccpHolder"]?.blankToNull(), f["bankName"]?.blankToNull(),
                f["bankAccount"]?.blankToNull(), f["bankHolder"]?.blankToNull(), f["instructionsAr"]?.blankToNull(), f["instructionsFr"]?.blankToNull(),
                f["contactPhone"]?.blankToNull(), f["contactEmail"]?.blankToNull(),
            ))
        }, loading = busy)
    }
}

// ───────────────────────── Association settings (SUPER_ADMIN) ─────────────────────────

private val SETTING_FIELDS = listOf(
    Triple("association_name_ar", R.string.set_name_ar, 1), Triple("association_name_fr", R.string.set_name_fr, 1),
    Triple("association_short_description_ar", R.string.set_short_ar, 2), Triple("association_short_description_fr", R.string.set_short_fr, 2),
    Triple("about_ar", R.string.set_about_ar, 5), Triple("about_fr", R.string.set_about_fr, 5),
    Triple("contact_phone", R.string.set_phone, 1), Triple("contact_email", R.string.set_email, 1),
    Triple("contact_address_ar", R.string.set_address_ar, 1), Triple("contact_address_fr", R.string.set_address_fr, 1),
    Triple("website_url", R.string.set_website, 1), Triple("facebook_url", R.string.set_facebook, 1), Triple("instagram_url", R.string.set_instagram, 1),
    Triple("youtube_url", R.string.set_youtube, 1), Triple("telegram_channel_url", R.string.set_telegram, 1),
    Triple("latest_app_version", R.string.set_version, 1), Triple("latest_app_version_code", R.string.set_version_code, 1),
    Triple("apk_url", R.string.set_apk_url, 1), Triple("apk_sha256", R.string.set_apk_sha, 1), Triple("min_supported_version_code", R.string.set_min_version, 1),
)

@HiltViewModel
class SettingsVm @Inject constructor(private val api: AdminApi) : FormVm<Map<String, String>>() {
    init { reload() }
    override suspend fun load() = apiCall { api.settings().settings }
    fun submit(values: Map<String, String>) = save { apiCall { api.updateSettings(values).settings } }
}

@Composable
fun SettingsScreen(back: () -> Unit, vm: SettingsVm = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    FormScreen(stringResource(R.string.a_settings), back, vm, stringResource(R.string.set_note)) { d ->
        val f = remember(d) { mutableStateMapOf<String, String>().apply { SETTING_FIELDS.forEach { (k) -> put(k, d[k].orEmpty()) } } }
        SETTING_FIELDS.forEach { (k, label, lines) ->
            val ltr = k.endsWith("_fr") || k.contains("url") || k.contains("email") || k.contains("phone") || k.contains("version") || k.contains("sha")
            Field(f[k].orEmpty(), { f[k] = it }, stringResource(label), lines = lines, ltr = ltr, isError = k.endsWith("_url") && f[k].orEmpty().isNotBlank() && !f[k].orEmpty().startsWith("https://"))
        }
        PrimaryButton(stringResource(dz.association.app.core.R.string.save), { vm.submit(f.mapValues { it.value.trim() }) }, loading = busy)
    }
}

// ───────────────────────── Notifications ─────────────────────────

@HiltViewModel
class NotificationsVm @Inject constructor(private val api: AdminApi) : FormVm<NotificationLog>() {
    val sentStatus = MutableStateFlow<String?>(null)
    init { reload() }
    override suspend fun load() = apiCall { api.notifications() }
    fun send(n: SendNotification) = viewModelScope.launch {
        busy.value = true; error.value = null
        when (val r = apiCall { api.sendNotification(n) }) {
            is Result.Ok -> { sentStatus.value = r.value.delivery; reload() }
            is Result.Err -> error.value = r.error
        }
        busy.value = false
    }
}

@Composable
private fun deliveryLabel(d: String) = stringResource(
    when (d) { "sent" -> R.string.n_sent; "failed" -> R.string.n_failed; "not_configured" -> R.string.n_not_configured; else -> R.string.n_queued },
)

@Composable
private fun topicLabel(t: String) = stringResource(
    when (t) { "news" -> R.string.n_t_news; "activities" -> R.string.n_t_activities; "polls" -> R.string.n_t_polls; "volunteer" -> R.string.n_t_volunteer; else -> R.string.n_t_important },
)

@Composable
fun NotificationsScreen(back: () -> Unit, role: String, vm: NotificationsVm = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val sent by vm.sentStatus.collectAsStateWithLifecycle()
    var topic by rememberSaveable { mutableStateOf("important") }
    var titleAr by rememberSaveable { mutableStateOf("") }
    var titleFr by rememberSaveable { mutableStateOf("") }
    var bodyAr by rememberSaveable { mutableStateOf("") }
    var bodyFr by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    FormScreen(stringResource(R.string.a_notifications), back, vm, null) { log ->
        if (!log.fcmConfigured) Text(stringResource(R.string.n_fcm_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        if (AdminRoles.atLeast(role, "ADMIN")) {
            Choice(stringResource(R.string.n_topic), topic, listOf("important", "news", "activities", "polls").map { it to topicLabel(it) }) { topic = it }
            Field(titleAr, { if (it.length <= 120) titleAr = it }, stringResource(R.string.a_title_ar))
            Field(titleFr, { if (it.length <= 120) titleFr = it }, stringResource(R.string.a_title_fr), ltr = true)
            Field(bodyAr, { if (it.length <= 500) bodyAr = it }, stringResource(R.string.a_body_ar), lines = 3)
            Field(bodyFr, { if (it.length <= 500) bodyFr = it }, stringResource(R.string.a_body_fr), lines = 2, ltr = true)
            sent?.let { Tag(deliveryLabel(it), MaterialTheme.colorScheme.primaryContainer) }
            PrimaryButton(stringResource(R.string.n_send), { confirm = true }, enabled = titleAr.trim().length >= 2 && bodyAr.trim().length >= 2, loading = busy)
            HorizontalDivider()
        }
        Text(stringResource(R.string.n_log), style = MaterialTheme.typography.titleMedium)
        log.items.forEach { n ->
            Column(Modifier.fillMaxWidth()) {
                Text(n.titleAr, style = MaterialTheme.typography.bodyLarge)
                Text("${formatDateTime(n.createdAt)} · ${topicLabel(n.topic)} · ${stringResource(if (n.targetUserId != null) R.string.n_to_one else R.string.n_to_all)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Tag(deliveryLabel(n.delivery))
            }
        }
    }
    if (confirm) ConfirmDialog(stringResource(R.string.n_confirm), {
        vm.send(SendNotification(topic, titleAr.trim(), titleFr.blankToNull(), bodyAr.trim(), bodyFr.blankToNull()))
        titleAr = ""; titleFr = ""; bodyAr = ""; bodyFr = ""
    }, { confirm = false })
}
