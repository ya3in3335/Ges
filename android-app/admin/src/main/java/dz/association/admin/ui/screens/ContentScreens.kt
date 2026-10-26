package dz.association.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.admin.R
import dz.association.admin.data.ActivityInput
import dz.association.admin.data.AdminActivity
import dz.association.admin.data.AdminApi
import dz.association.admin.data.AdminNews
import dz.association.admin.data.AdminRoles
import dz.association.admin.data.NewsInput
import dz.association.admin.data.Participant
import dz.association.admin.data.StatusBody
import dz.association.admin.ui.CheckRow
import dz.association.admin.ui.Choice
import dz.association.admin.ui.ConfirmDialog
import dz.association.admin.ui.DateTimeField
import dz.association.admin.ui.EditorDialog
import dz.association.admin.ui.ErrorText
import dz.association.admin.ui.Field
import dz.association.admin.ui.ListVm
import dz.association.admin.ui.PagedList
import dz.association.admin.ui.Tag
import dz.association.admin.ui.blankToNull
import dz.association.admin.ui.statusLabel
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.app.core.util.formatDate
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.RemoteImage
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

private fun validUrl(u: String) = u.isBlank() || u.trim().startsWith("https://") || u.trim().startsWith("/media/")

// ───────────────────────── News ─────────────────────────

@HiltViewModel
class NewsVm @Inject constructor(private val api: AdminApi) : ListVm<AdminNews>() {
    val filter = MutableStateFlow<String?>(null)
    init { reload() }
    override suspend fun fetch(page: Int): Result<Pair<List<AdminNews>, Boolean>> =
        when (val r = apiCall { api.news(page, filter.value) }) { is Result.Ok -> Result.Ok(r.value.items to r.value.hasMore); is Result.Err -> r }
    fun save(id: String?, input: NewsInput, onOk: () -> Unit) = act(onOk = onOk) {
        if (id == null) apiCall { api.createNews(input) } else apiCall { api.updateNews(id, input) }
    }
    fun delete(id: String) = act { apiCall { api.deleteNews(id) } }
}

@Composable
private fun catLabel(c: String) = stringResource(
    when (c) {
        "announcement" -> dz.association.app.core.R.string.cat_announcement; "campaign" -> dz.association.app.core.R.string.cat_campaign
        "report" -> dz.association.app.core.R.string.cat_report; else -> dz.association.app.core.R.string.cat_news
    },
)

@Composable
fun NewsScreen(back: () -> Unit, role: String, vm: NewsVm = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AdminNews?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.a_news), back) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.a_add)) }) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList(s, vm::reload, vm::loadMore, header = {
                item {
                    FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null, "published", "draft", "archived").forEach { st ->
                            androidx.compose.material3.FilterChip(filter == st, { vm.filter.value = st; vm.reload() }, { Text(if (st == null) stringResource(R.string.a_all) else statusLabel(st)) })
                        }
                    }
                }
            }) { list ->
                items(list, key = { it.id }) { n ->
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { editing = n }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (n.imageUrl != null) { RemoteImage(n.imageUrl, null, Modifier.padding(end = 12.dp).fillMaxWidth(0.25f).androidAspect()) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text((if (n.featured) "★ " else "") + n.titleAr, style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Tag(statusLabel(n.status), if (n.status == "published") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    Tag(catLabel(n.category))
                                    n.publishedAt?.let { Tag(formatDate(it), MaterialTheme.colorScheme.surfaceVariant) }
                                }
                            }
                            if (AdminRoles.atLeast(role, "ADMIN")) TextButton(onClick = { deleting = n.id }) { Text(stringResource(R.string.a_delete), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    if (creating || editing != null) NewsEditor(editing, vm) { creating = false; editing = null }
    deleting?.let { id -> ConfirmDialog(stringResource(R.string.a_confirm_delete), { vm.delete(id) }, { deleting = null }) }
}

private fun Modifier.androidAspect() = this.aspectRatio(1f)

@Composable
private fun NewsEditor(n: AdminNews?, vm: NewsVm, close: () -> Unit) {
    var titleAr by rememberSaveable { mutableStateOf(n?.titleAr.orEmpty()) }
    var titleFr by rememberSaveable { mutableStateOf(n?.titleFr.orEmpty()) }
    var bodyAr by rememberSaveable { mutableStateOf(n?.bodyAr.orEmpty()) }
    var bodyFr by rememberSaveable { mutableStateOf(n?.bodyFr.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(n?.category ?: "news") }
    var status by rememberSaveable { mutableStateOf(n?.status ?: "draft") }
    var image by rememberSaveable { mutableStateOf(n?.imageUrl.orEmpty()) }
    var featured by rememberSaveable { mutableStateOf(n?.featured ?: false) }
    var notify by rememberSaveable { mutableStateOf(false) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    EditorDialog(stringResource(if (n == null) R.string.a_add else R.string.a_edit), close) {
        Field(titleAr, { titleAr = it }, stringResource(R.string.a_title_ar), isError = tried && titleAr.isBlank())
        Field(titleFr, { titleFr = it }, stringResource(R.string.a_title_fr), ltr = true)
        Field(bodyAr, { bodyAr = it }, stringResource(R.string.a_body_ar), lines = 6, isError = tried && bodyAr.isBlank())
        Field(bodyFr, { bodyFr = it }, stringResource(R.string.a_body_fr), lines = 4, ltr = true)
        Choice(stringResource(R.string.a_category), category, listOf("news", "announcement", "campaign", "report").map { it to catLabel(it) }) { category = it }
        Choice(stringResource(R.string.a_status), status, listOf("draft", "published", "archived").map { it to statusLabel(it) }) { status = it }
        Field(image, { image = it }, stringResource(R.string.a_image_url), ltr = true, keyboard = KeyboardType.Uri, isError = !validUrl(image))
        if (image.startsWith("https://")) RemoteImage(image, null, Modifier.fillMaxWidth(0.6f).androidAspect())
        CheckRow(stringResource(R.string.a_featured), featured) { featured = it }
        CheckRow(stringResource(R.string.a_notify), notify) { notify = it }
        if (tried && (titleAr.isBlank() || bodyAr.isBlank())) Text(stringResource(R.string.a_required), color = MaterialTheme.colorScheme.error)
        ErrorText(err)
        PrimaryButton(stringResource(dz.association.app.core.R.string.save), {
            tried = true
            if (titleAr.trim().length >= 3 && bodyAr.isNotBlank() && validUrl(image)) {
                vm.save(n?.id, NewsInput(titleAr.trim(), titleFr.blankToNull(), bodyAr.trim(), bodyFr.blankToNull(), category, image.blankToNull(), featured, status, notify), close)
            }
        }, loading = busy)
    }
}

// ───────────────────────── Activities ─────────────────────────

@HiltViewModel
class ActivitiesVm @Inject constructor(private val api: AdminApi) : ListVm<AdminActivity>() {
    val participants = MutableStateFlow<Pair<AdminActivity, List<Participant>>?>(null)
    init { reload() }
    override suspend fun fetch(page: Int): Result<Pair<List<AdminActivity>, Boolean>> =
        when (val r = apiCall { api.activities(page) }) { is Result.Ok -> Result.Ok(r.value.items to r.value.hasMore); is Result.Err -> r }
    fun save(id: String?, input: ActivityInput, onOk: () -> Unit) = act(onOk = onOk) {
        if (id == null) apiCall { api.createActivity(input) } else apiCall { api.updateActivity(id, input) }
    }
    fun openParticipants(a: AdminActivity) = act(reloadAfter = false) {
        apiCall { api.participants(a.id) }.also { if (it is Result.Ok) participants.value = a to it.value.items }
    }
    fun setParticipant(userId: String, status: String) {
        val a = participants.value?.first ?: return
        act(reloadAfter = false) { apiCall { api.setParticipant(a.id, userId, StatusBody(status)) }.also { if (it is Result.Ok) openParticipants(a) } }
    }
}

@Composable
private fun phaseLabel(p: String) = stringResource(
    when (p) {
        "ongoing" -> dz.association.app.core.R.string.phase_ongoing; "completed" -> dz.association.app.core.R.string.phase_completed
        "cancelled" -> dz.association.app.core.R.string.phase_cancelled; else -> dz.association.app.core.R.string.phase_upcoming
    },
)

@Composable
fun ActivitiesScreen(back: () -> Unit, vm: ActivitiesVm = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val people by vm.participants.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AdminActivity?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.a_activities), back) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.a_add)) }) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList(s, vm::reload, vm::loadMore) { list ->
                items(list, key = { it.id }) { a ->
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { editing = a }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(a.titleAr, style = MaterialTheme.typography.titleSmall)
                            Text(formatDateTime(a.startsAt), style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Tag(statusLabel(a.status), if (a.status == "published") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                Tag(phaseLabel(a.phase))
                                if (a.isCampaign) Tag(stringResource(dz.association.app.core.R.string.campaign))
                                Tag(stringResource(R.string.a_registered_n, a.registeredCount) + (a.capacity?.let { " / $it" } ?: ""), MaterialTheme.colorScheme.surfaceVariant)
                            }
                            OutlinedButton(onClick = { vm.openParticipants(a) }) { Text(stringResource(R.string.a_participants)) }
                        }
                    }
                }
            }
        }
    }
    if (creating || editing != null) ActivityEditor(editing, vm) { creating = false; editing = null }
    people?.let { (a, list) ->
        EditorDialog("${stringResource(R.string.a_participants)} — ${a.titleAr}", { vm.participants.value = null }) {
            if (list.isEmpty()) dz.association.app.ui.components.EmptyState()
            list.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.fullName, style = MaterialTheme.typography.bodyLarge)
                        Text(p.phone, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                        Text(p.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    TextButton(onClick = { vm.setParticipant(p.userId, "attended") }) { Text(stringResource(R.string.a_attended)) }
                    TextButton(onClick = { vm.setParticipant(p.userId, "cancelled") }) { Text(stringResource(R.string.a_cancel_reg)) }
                }
            }
        }
    }
}

@Composable
private fun ActivityEditor(a: AdminActivity?, vm: ActivitiesVm, close: () -> Unit) {
    var titleAr by rememberSaveable { mutableStateOf(a?.titleAr.orEmpty()) }
    var titleFr by rememberSaveable { mutableStateOf(a?.titleFr.orEmpty()) }
    var descAr by rememberSaveable { mutableStateOf(a?.descriptionAr.orEmpty()) }
    var descFr by rememberSaveable { mutableStateOf(a?.descriptionFr.orEmpty()) }
    var location by rememberSaveable { mutableStateOf(a?.location.orEmpty()) }
    var lat by rememberSaveable { mutableStateOf(a?.latitude?.toString().orEmpty()) }
    var lng by rememberSaveable { mutableStateOf(a?.longitude?.toString().orEmpty()) }
    var starts by rememberSaveable { mutableStateOf(a?.startsAt ?: 0L) }
    var ends by rememberSaveable { mutableStateOf(a?.endsAt ?: 0L) }
    var capacity by rememberSaveable { mutableStateOf(a?.capacity?.toString().orEmpty()) }
    var image by rememberSaveable { mutableStateOf(a?.imageUrl.orEmpty()) }
    var video by rememberSaveable { mutableStateOf(a?.videoUrl.orEmpty()) }
    var campaign by rememberSaveable { mutableStateOf(a?.isCampaign ?: false) }
    var featured by rememberSaveable { mutableStateOf(a?.featured ?: false) }
    var regOpen by rememberSaveable { mutableStateOf(a?.registrationOpen ?: true) }
    var status by rememberSaveable { mutableStateOf(a?.status ?: "draft") }
    var notify by rememberSaveable { mutableStateOf(false) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    val valid = titleAr.trim().length >= 3 && descAr.isNotBlank() && starts > 0 && ends >= starts && validUrl(image) && validUrl(video)
    EditorDialog(stringResource(if (a == null) R.string.a_add else R.string.a_edit), close) {
        Field(titleAr, { titleAr = it }, stringResource(R.string.a_title_ar), isError = tried && titleAr.isBlank())
        Field(titleFr, { titleFr = it }, stringResource(R.string.a_title_fr), ltr = true)
        Field(descAr, { descAr = it }, stringResource(R.string.a_desc_ar) + " *", lines = 5, isError = tried && descAr.isBlank())
        Field(descFr, { descFr = it }, stringResource(R.string.a_desc_fr), lines = 3, ltr = true)
        DateTimeField(stringResource(R.string.a_starts), starts) { starts = it; if (ends < it) ends = it + 2 * 3600_000 }
        DateTimeField(stringResource(R.string.a_ends), ends) { ends = it }
        Field(location, { location = it }, stringResource(R.string.a_location))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field(lat, { lat = it }, stringResource(R.string.a_latitude), ltr = true, keyboard = KeyboardType.Decimal) }
            Box(Modifier.weight(1f)) { Field(lng, { lng = it }, stringResource(R.string.a_longitude), ltr = true, keyboard = KeyboardType.Decimal) }
        }
        Field(capacity, { capacity = it.filter(Char::isDigit) }, stringResource(R.string.a_capacity), keyboard = KeyboardType.Number)
        Field(image, { image = it }, stringResource(R.string.a_image_url), ltr = true, keyboard = KeyboardType.Uri, isError = !validUrl(image))
        Field(video, { video = it }, stringResource(R.string.a_video_url), ltr = true, keyboard = KeyboardType.Uri, isError = !validUrl(video))
        Choice(stringResource(R.string.a_status), status, listOf("draft", "published", "cancelled", "archived").map { it to statusLabel(it) }) { status = it }
        CheckRow(stringResource(R.string.a_campaign), campaign) { campaign = it }
        CheckRow(stringResource(R.string.a_featured), featured) { featured = it }
        CheckRow(stringResource(R.string.a_registration_open), regOpen) { regOpen = it }
        if (a == null) CheckRow(stringResource(R.string.a_notify), notify) { notify = it }
        if (tried && !valid) Text(stringResource(R.string.a_required), color = MaterialTheme.colorScheme.error)
        ErrorText(err)
        PrimaryButton(stringResource(dz.association.app.core.R.string.save), {
            tried = true
            if (valid) vm.save(a?.id, ActivityInput(
                titleAr.trim(), titleFr.blankToNull(), descAr.trim(), descFr.blankToNull(), location.blankToNull(),
                lat.toDoubleOrNull(), lng.toDoubleOrNull(), starts, ends, capacity.toIntOrNull()?.takeIf { it > 0 },
                image.blankToNull(), video.blankToNull(), campaign, featured, status, regOpen, notify,
            ), close)
        }, loading = busy)
    }
}

