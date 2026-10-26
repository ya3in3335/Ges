package dz.association.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.admin.R
import dz.association.admin.data.AdminApi
import dz.association.admin.data.AdminRoles
import dz.association.admin.data.AdminUserRow
import dz.association.admin.data.AuditRow
import dz.association.admin.data.TelegramStats
import dz.association.admin.data.UpdateUserRequest
import dz.association.admin.ui.Choice
import dz.association.admin.ui.ConfirmDialog
import dz.association.admin.ui.ErrorText
import dz.association.admin.ui.ListVm
import dz.association.admin.ui.PagedList
import dz.association.admin.ui.Tag
import dz.association.admin.ui.statusLabel
import dz.association.app.core.auth.SessionManager
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.app.core.util.formatDate
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.roleLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private val ROLES = listOf("USER", "VOLUNTEER", "EDITOR", "ADMIN", "SUPER_ADMIN")

@HiltViewModel
class UsersVm @Inject constructor(private val api: AdminApi, session: SessionManager) : ListVm<AdminUserRow>() {
    val query = MutableStateFlow("")
    val role = MutableStateFlow<String?>(null)
    val verified = MutableStateFlow<String?>(null)
    val total = MutableStateFlow(0)
    val myId = session.user.value?.id
    init { reload() }
    override suspend fun fetch(page: Int): Result<Pair<List<AdminUserRow>, Boolean>> =
        when (val r = apiCall { api.users(page, query.value.trim().ifEmpty { null }, role.value, verified.value) }) {
            is Result.Ok -> { total.value = r.value.total; Result.Ok(r.value.items to (page * 25 < r.value.total)) }
            is Result.Err -> r
        }
    fun setRole(u: AdminUserRow, r: String) = act { apiCall { api.updateUser(u.id, UpdateUserRequest(role = r)) } }
    fun setStatus(u: AdminUserRow, s: String) = act { apiCall { api.updateUser(u.id, UpdateUserRequest(status = s)) } }
    fun resetVerification(u: AdminUserRow) = act { apiCall { api.resetVerification(u.id) } }
}

@Composable
fun UsersScreen(back: () -> Unit, myRole: String, vm: UsersVm = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val role by vm.role.collectAsStateWithLifecycle()
    val verified by vm.verified.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) } // action to user id
    Scaffold(topBar = { AppTopBar(stringResource(R.string.a_users), back) }) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList(s, vm::reload, vm::loadMore, header = {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(q, { vm.query.value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.u_search_hint)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.reload() }))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(verified == null, { vm.verified.value = null; vm.reload() }, { Text(stringResource(R.string.a_all)) })
                            FilterChip(verified == "1", { vm.verified.value = "1"; vm.reload() }, { Text(stringResource(R.string.u_verified)) })
                            FilterChip(verified == "0", { vm.verified.value = "0"; vm.reload() }, { Text(stringResource(R.string.u_not_verified)) })
                            ROLES.forEach { r -> FilterChip(role == r, { vm.role.value = if (role == r) null else r; vm.reload() }, { Text(roleLabel(r)) }) }
                        }
                        Text(stringResource(R.string.u_total, total), style = MaterialTheme.typography.labelMedium)
                        ErrorText(err)
                    }
                }
            }) { list ->
                items(list, key = { it.id }) { u ->
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(u.fullName, style = MaterialTheme.typography.titleMedium)
                            Text(u.email, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                            Text(u.phone, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Tag(if (u.phoneVerifiedAt == null) stringResource(R.string.u_not_verified) else if (u.telegramLinked == 1) stringResource(R.string.u_verified_tg) else stringResource(R.string.u_verified),
                                    if (u.phoneVerifiedAt != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                Tag(statusLabel(u.status), if (u.status == "active") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)
                                Tag(formatDate(u.createdAt), MaterialTheme.colorScheme.surfaceVariant)
                            }
                            if (u.id != vm.myId) {
                                // Only roles below the actor's own can be granted (SUPER_ADMIN can grant any); the server re-checks.
                                val assignable = ROLES.filter { myRole == "SUPER_ADMIN" || (AdminRoles.atLeast(myRole, it) && it != myRole) }
                                Choice(stringResource(R.string.u_role), u.role, (assignable + u.role).distinct().map { it to roleLabel(it) }, enabled = u.role in assignable) { if (it != u.role) vm.setRole(u, it) }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (u.status == "active") OutlinedButton(onClick = { confirm = "suspend" to u.id }) { Text(stringResource(R.string.u_suspend)) }
                                    else OutlinedButton(onClick = { vm.setStatus(u, "active") }) { Text(stringResource(R.string.u_activate)) }
                                    if (u.phoneVerifiedAt != null) OutlinedButton(onClick = { confirm = "reset" to u.id }) { Text(stringResource(R.string.u_reset_verification)) }
                                }
                            } else Text(roleLabel(u.role), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    confirm?.let { (action, id) ->
        val u = s.items.firstOrNull { it.id == id } ?: return@let
        ConfirmDialog(
            stringResource(if (action == "suspend") R.string.u_suspend_confirm else R.string.u_reset_confirm),
            { if (action == "suspend") vm.setStatus(u, "suspended") else vm.resetVerification(u) },
            { confirm = null },
        )
    }
}

@HiltViewModel
class TelegramVm @Inject constructor(private val api: AdminApi) : ViewModel() {
    val data = MutableStateFlow<TelegramStats?>(null)
    val error = MutableStateFlow<AppError?>(null)
    init { load() }
    fun load() = viewModelScope.launch {
        error.value = null
        when (val r = apiCall { api.telegramStats() }) { is Result.Ok -> data.value = r.value; is Result.Err -> error.value = r.error }
    }
}

private val TG_LABELS = mapOf(
    "pending" to "بانتظار فتح البوت", "awaiting_contact" to "بانتظار مشاركة الرقم", "verified" to "مؤكد", "failed" to "فشل", "expired" to "منتهي",
    "cancelled" to "ملغى", "sent" to "أُرسل", "started" to "بدء طلب", "bound" to "فتح البوت", "phone_mismatch" to "رقم غير مطابق",
    "foreign_contact" to "جهة اتصال لشخص آخر", "forwarded_contact" to "جهة اتصال مُعاد توجيهها", "typed_number_rejected" to "رقم مكتوب يدوياً",
    "expired_or_closed" to "رابط منتهٍ", "invalid_token" to "رابط غير صالح", "token_used_by_other_telegram_account" to "رابط من حساب آخر",
    "telegram_already_linked" to "تيليغرام مرتبط بحساب آخر", "replay_after_use" to "إعادة استخدام رابط", "admin_reset" to "إلغاء من الإدارة",
)

@Composable
fun TelegramScreen(back: () -> Unit, vm: TelegramVm = hiltViewModel()) {
    val d by vm.data.collectAsStateWithLifecycle()
    val e by vm.error.collectAsStateWithLifecycle()
    // Labels are shown in Arabic with the raw code for French readers (technical screen).
    val label = { k: String -> TG_LABELS[k]?.let { if (dz.association.app.core.util.AppLocale.isFrench) k else it } ?: k }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.a_telegram), back) }) { pad ->
        Box(Modifier.padding(pad)) {
            val data = d
            when {
                data == null && e != null -> ErrorState(e!!, { vm.load() })
                data == null -> FullScreenLoading()
                else -> androidx.compose.foundation.lazy.LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag("${stringResource(R.string.tg_bot)}: ${stringResource(if (data.telegramConfigured) R.string.tg_on else R.string.tg_off)}")
                            Tag("${stringResource(R.string.tg_channel)}: ${stringResource(if (data.channelConfigured) R.string.tg_on else R.string.tg_off)}")
                        }
                    }
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { data.byStatus.forEach { Tag("${label(it.status.orEmpty())}: ${it.n}") } } }
                    item { Text(stringResource(R.string.tg_daily), style = MaterialTheme.typography.titleMedium) }
                    item {
                        val max = (data.verifiedPerDay.maxOfOrNull { it.n } ?: 1).coerceAtLeast(1)
                        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                            data.verifiedPerDay.forEach { day ->
                                Box(Modifier.width(10.dp).fillMaxHeight(day.n.toFloat() / max).background(MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                    item { Text(stringResource(R.string.tg_events), style = MaterialTheme.typography.titleMedium) }
                    items(data.byEvent) { ev -> Row { Text(label(ev.event.orEmpty()), Modifier.weight(1f)); Text("${ev.n}") } }
                    item { Text(stringResource(R.string.tg_outbox), style = MaterialTheme.typography.titleMedium) }
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { data.channelOutbox.forEach { Tag("${label(it.status.orEmpty())}: ${it.n}") } } }
                }
            }
        }
    }
}

@HiltViewModel
class AuditVm @Inject constructor(private val api: AdminApi) : ListVm<AuditRow>() {
    val source = MutableStateFlow("admin")
    init { reload() }
    override suspend fun fetch(page: Int): Result<Pair<List<AuditRow>, Boolean>> =
        when (val r = apiCall { api.audit(source.value, page) }) { is Result.Ok -> Result.Ok(r.value.items to r.value.hasMore); is Result.Err -> r }
}

@Composable
fun AuditScreen(back: () -> Unit, vm: AuditVm = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.a_audit), back) }) { pad ->
        Column(Modifier.padding(pad)) {
            PrimaryTabRow(selectedTabIndex = if (source == "admin") 0 else 1) {
                Tab(source == "admin", { vm.source.value = "admin"; vm.reload() }, text = { Text(stringResource(R.string.au_admin)) })
                Tab(source == "verification", { vm.source.value = "verification"; vm.reload() }, text = { Text(stringResource(R.string.au_verification)) })
            }
            PagedList(s, vm::reload, vm::loadMore) { list ->
                items(list, key = { it.id }) { r ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(formatDateTime(r.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(listOfNotNull(r.actorName, r.action ?: r.event, r.entityType).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                        r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3) }
                    }
                    androidx.compose.material3.HorizontalDivider()
                }
            }
        }
    }
}

