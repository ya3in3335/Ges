package dz.association.admin.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dz.association.admin.R
import dz.association.admin.data.AdminApi
import dz.association.admin.data.AdminMedia
import dz.association.admin.data.AdminPoll
import dz.association.admin.data.AdminVolunteer
import dz.association.admin.data.AssignRequest
import dz.association.admin.data.ExternalMediaInput
import dz.association.admin.data.MediaUpdate
import dz.association.admin.data.MembersRequest
import dz.association.admin.data.OptionInput
import dz.association.admin.data.PollInput
import dz.association.admin.data.PollResultsAdmin
import dz.association.admin.data.ReviewRequest
import dz.association.admin.ui.CheckRow
import dz.association.admin.ui.Choice
import dz.association.admin.ui.ConfirmDialog
import dz.association.admin.ui.DateTimeField
import dz.association.admin.ui.EditorDialog
import dz.association.admin.ui.ErrorText
import dz.association.admin.ui.Field
import dz.association.admin.ui.ListVm
import dz.association.admin.ui.PagedList
import dz.association.admin.ui.ProblemsText
import dz.association.admin.ui.scheduleProblems
import dz.association.admin.ui.Tag
import dz.association.admin.ui.blankToNull
import dz.association.admin.ui.statusLabel
import dz.association.app.core.network.Activity
import dz.association.app.core.network.ApiService
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.RemoteImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import javax.inject.Inject

// ───────────────────────── Polls ─────────────────────────

data class PollDraft(
    val id: String? = null,
    val titleAr: String = "",
    val titleFr: String = "",
    val descriptionAr: String = "",
    val startsAt: Long = 0,
    val endsAt: Long = 0,
    val eligibility: String = "verified",
    val resultVisibility: String = "after_close",
    val anonymous: Boolean = true,
    val status: String = "draft",
    val options: List<String> = listOf("", ""),
    val locked: Boolean = false,
)

@HiltViewModel
class PollsVm @Inject constructor(private val api: AdminApi) : ListVm<AdminPoll>() {
    val draft = MutableStateFlow<PollDraft?>(null)
    val results = MutableStateFlow<Pair<AdminPoll, PollResultsAdmin>?>(null)
    val membersFor = MutableStateFlow<AdminPoll?>(null)
    val membersOut = MutableStateFlow<Pair<Int, List<String>>?>(null)
    init { reload() }
    override suspend fun fetch(page: Int): Result<Pair<List<AdminPoll>, Boolean>> =
        when (val r = apiCall { api.polls() }) { is Result.Ok -> Result.Ok(r.value.items to false); is Result.Err -> r }

    fun newPoll() { draft.value = PollDraft() }
    fun edit(p: AdminPoll) = act(reloadAfter = false) {
        apiCall { api.pollDetail(p.id) }.also { r ->
            if (r is Result.Ok) draft.value = PollDraft(
                p.id, p.titleAr, p.titleFr.orEmpty(), p.descriptionAr.orEmpty(), p.startsAt, p.endsAt, p.eligibility, p.resultVisibility,
                p.anonymous, p.status, r.value.options.map { it.labelAr }, locked = p.votes > 0,
            )
        }
    }

    fun save(d: PollDraft) = act(onOk = { draft.value = null }) {
        val base = PollInput(titleAr = d.titleAr.trim(), titleFr = d.titleFr.blankToNull(), descriptionAr = d.descriptionAr.blankToNull(), endsAt = d.endsAt, status = d.status)
        // Once votes exist the server refuses rule changes, so they are not sent.
        val input = if (d.locked) base else base.copy(
            startsAt = d.startsAt, eligibility = d.eligibility, resultVisibility = d.resultVisibility, anonymous = d.anonymous,
            options = d.options.filter { it.isNotBlank() }.map { OptionInput(it.trim()) },
        )
        if (d.id == null) apiCall { api.createPoll(input) } else apiCall { api.updatePoll(d.id, input) }
    }

    fun showResults(p: AdminPoll) = act(reloadAfter = false) { apiCall { api.pollResults(p.id) }.also { if (it is Result.Ok) results.value = p to it.value } }

    fun addMembers(text: String) {
        val p = membersFor.value ?: return
        val ids = text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return
        act { apiCall { api.addMembers(p.id, MembersRequest(ids)) }.also { if (it is Result.Ok) membersOut.value = it.value.added to it.value.notFound } }
    }
}

@Composable
private fun visLabel(v: String) = stringResource(when (v) { "live" -> R.string.p_vis_live; "admins" -> R.string.p_vis_admins; else -> R.string.p_vis_after })

@Composable
private fun stateLabel(s: String) = stringResource(when (s) { "active" -> R.string.p_state_active; "closed" -> R.string.p_state_closed; else -> R.string.p_state_upcoming })

@Composable
fun PollsScreen(back: () -> Unit, vm: PollsVm = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val membersFor by vm.membersFor.collectAsStateWithLifecycle()
    val membersOut by vm.membersOut.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.a_polls), back) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { vm.newPoll() }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.a_add)) }) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList(s, vm::reload, vm::loadMore, header = {
                item { Column(Modifier.padding(16.dp)) { Text(stringResource(R.string.p_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); ErrorText(err) } }
            }) { list ->
                items(list, key = { it.id }) { p ->
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(p.titleAr, style = MaterialTheme.typography.titleSmall)
                            Text("${formatDateTime(p.startsAt)} ← ${formatDateTime(p.endsAt)}", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Tag(statusLabel(p.status), if (p.status == "published") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                Tag(stateLabel(p.state))
                                Tag(stringResource(R.string.p_votes, p.votes), MaterialTheme.colorScheme.surfaceVariant)
                                if (p.anonymous) Tag(stringResource(R.string.p_anonymous), MaterialTheme.colorScheme.surfaceVariant)
                                Tag(if (p.eligibility == "members") stringResource(R.string.p_elig_members) + " (${p.members})" else stringResource(R.string.p_elig_verified), MaterialTheme.colorScheme.surfaceVariant)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.edit(p) }) { Text(stringResource(R.string.a_edit)) }
                                OutlinedButton(onClick = { vm.showResults(p) }) { Text(stringResource(R.string.p_results)) }
                                if (p.eligibility == "members") OutlinedButton(onClick = { vm.membersOut.value = null; vm.membersFor.value = p }) { Text(stringResource(R.string.p_members)) }
                            }
                        }
                    }
                }
            }
        }
    }
    draft?.let { PollEditor(it, vm) }
    results?.let { (p, r) ->
        EditorDialog("${stringResource(R.string.p_results)} — ${p.titleAr}", { vm.results.value = null }) {
            Text(stringResource(R.string.p_turnout, r.totalVotes, r.eligibleVoters), style = MaterialTheme.typography.titleSmall)
            r.options.forEach { o ->
                val pct = if (r.totalVotes == 0) 0f else o.votes.toFloat() / r.totalVotes
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { Text(o.labelAr, Modifier.weight(1f)); Text("${o.votes} (${(pct * 100).toInt()}%)") }
                    LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    membersFor?.let { p ->
        var text by rememberSaveable { mutableStateOf("") }
        EditorDialog("${stringResource(R.string.p_members)} — ${p.titleAr}", { vm.membersFor.value = null }) {
            Field(text, { text = it }, stringResource(R.string.p_members_hint), lines = 8, ltr = true)
            membersOut?.let { (added, missing) -> Text(stringResource(R.string.p_added, added, missing.joinToString("، ").ifEmpty { "—" })) }
            ErrorText(err)
            PrimaryButton(stringResource(R.string.a_add), { vm.addMembers(text); text = "" })
        }
    }
}

@Composable
private fun PollEditor(initial: PollDraft, vm: PollsVm) {
    var d by remember(initial) { mutableStateOf(initial) }
    val opts = remember(initial) { mutableStateListOf(*initial.options.toTypedArray()) }
    var tried by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    val problems = scheduleProblems(d.titleAr, d.startsAt, d.endsAt) +
        listOfNotNull(if (opts.count { it.isNotBlank() } < 2) stringResource(R.string.a_problem_options) else null)
    val valid = problems.isEmpty()
    EditorDialog(stringResource(if (d.id == null) R.string.a_add else R.string.a_edit), { vm.draft.value = null }) {
        if (d.locked) Text(stringResource(R.string.p_locked), color = MaterialTheme.colorScheme.tertiary)
        Field(d.titleAr, { d = d.copy(titleAr = it) }, stringResource(R.string.a_title_ar), isError = tried && d.titleAr.trim().length < 3)
        Field(d.titleFr, { d = d.copy(titleFr = it) }, stringResource(R.string.a_title_fr), ltr = true)
        Field(d.descriptionAr, { d = d.copy(descriptionAr = it) }, stringResource(R.string.a_desc_ar), lines = 3)
        DateTimeField(stringResource(R.string.a_starts), d.startsAt, enabled = !d.locked, isError = tried && d.startsAt <= 0) {
            d = d.copy(startsAt = it, endsAt = if (d.endsAt <= it) it + 24 * 3600_000 else d.endsAt)
        }
        DateTimeField(stringResource(R.string.a_ends), d.endsAt, isError = tried && d.endsAt <= d.startsAt) {
            d = d.copy(endsAt = if (d.startsAt > 0 && it <= d.startsAt) d.startsAt + 24 * 3600_000 else it)
        }
        Choice(stringResource(R.string.p_eligibility), d.eligibility, listOf("verified" to stringResource(R.string.p_elig_verified), "members" to stringResource(R.string.p_elig_members)), enabled = !d.locked) { d = d.copy(eligibility = it) }
        Choice(stringResource(R.string.p_visibility), d.resultVisibility, listOf("live", "after_close", "admins").map { it to visLabel(it) }, enabled = !d.locked) { d = d.copy(resultVisibility = it) }
        Choice(stringResource(R.string.a_status), d.status, listOf("draft", "published", "cancelled").map { it to statusLabel(it) }) { d = d.copy(status = it) }
        CheckRow(stringResource(R.string.p_anonymous), d.anonymous, enabled = !d.locked) { d = d.copy(anonymous = it) }
        Text(stringResource(R.string.p_options), style = MaterialTheme.typography.titleSmall)
        opts.forEachIndexed { i, o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(o, { opts[i] = it }, enabled = !d.locked, singleLine = true, label = { Text(stringResource(R.string.p_option_n, i + 1)) }, modifier = Modifier.weight(1f))
                if (opts.size > 2 && !d.locked) IconButton(onClick = { opts.removeAt(i) }) { Icon(Icons.Outlined.Close, stringResource(R.string.a_delete)) }
            }
        }
        if (!d.locked) TextButton(onClick = { opts.add("") }) { Text(stringResource(R.string.p_add_option)) }
        if (tried) ProblemsText(problems)
        ErrorText(err)
        PrimaryButton(stringResource(dz.association.app.core.R.string.save), { tried = true; if (valid) vm.save(d.copy(options = opts.toList())) }, loading = busy)
    }
}

// ───────────────────────── Volunteers ─────────────────────────

@HiltViewModel
class VolunteersVm @Inject constructor(private val api: AdminApi, private val publicApi: ApiService) : ListVm<AdminVolunteer>() {
    val status = MutableStateFlow<String?>("pending")
    val activities = MutableStateFlow<List<Activity>>(emptyList())
    val info = MutableStateFlow<Int?>(null)
    init { reload() }
    override suspend fun fetch(page: Int): Result<Pair<List<AdminVolunteer>, Boolean>> =
        when (val r = apiCall { api.volunteers(page, status.value, null) }) { is Result.Ok -> Result.Ok(r.value.items to r.value.hasMore); is Result.Err -> r }
    fun review(v: AdminVolunteer, s: String, note: String) = act { apiCall { api.reviewVolunteer(v.id, ReviewRequest(s, note.blankToNull())) } }
    fun loadActivities() = act(reloadAfter = false) { apiCall { publicApi.activities("current") }.also { if (it is Result.Ok) activities.value = it.value.items } }
    fun assign(v: AdminVolunteer, activityId: String) = act(reloadAfter = false, onOk = { info.value = R.string.v_assigned }) { apiCall { api.assignVolunteer(v.id, AssignRequest(activityId)) } }
}

@Composable
fun VolunteersScreen(back: () -> Unit, vm: VolunteersVm = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val acts by vm.activities.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<AdminVolunteer?>(null) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.a_volunteers), back) }) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList(s, vm::reload, vm::loadMore, header = {
                item {
                    FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("pending", "approved", "rejected", null).forEach { st ->
                            FilterChip(status == st, { vm.status.value = st; vm.reload() }, { Text(if (st == null) stringResource(R.string.a_all) else statusLabel(st)) })
                        }
                    }
                }
            }) { list ->
                items(list, key = { it.id }) { v ->
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { open = v; vm.info.value = null; vm.loadActivities() }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(v.fullName, style = MaterialTheme.typography.titleSmall)
                            Text(v.skills, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Tag(statusLabel(v.status))
                                if (v.phoneVerifiedAt != null) Tag(stringResource(R.string.u_verified), MaterialTheme.colorScheme.primaryContainer)
                            }
                        }
                    }
                }
            }
        }
    }
    open?.let { v ->
        var note by rememberSaveable(v.id) { mutableStateOf(v.reviewNote.orEmpty()) }
        var activityId by rememberSaveable(v.id) { mutableStateOf("") }
        EditorDialog(v.fullName, { open = null }) {
            Labeled(stringResource(dz.association.app.core.R.string.volunteer_contact), v.contact, ltr = true)
            Labeled(stringResource(dz.association.app.core.R.string.volunteer_skills), v.skills)
            Labeled(stringResource(dz.association.app.core.R.string.volunteer_availability), v.availability)
            v.preferredAreas?.let { Labeled(stringResource(dz.association.app.core.R.string.volunteer_areas), it) }
            v.experience?.let { Labeled(stringResource(dz.association.app.core.R.string.volunteer_experience), it) }
            Field(note, { note = it }, stringResource(R.string.v_note), lines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.v_approve), { vm.review(v, "approved", note); open = null }, Modifier.weight(1f))
                OutlinedButton(onClick = { vm.review(v, "rejected", note); open = null }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.v_reject), color = MaterialTheme.colorScheme.error) }
            }
            if (v.status == "approved" && acts.isNotEmpty()) {
                Choice(stringResource(R.string.v_assign), activityId, acts.map { it.id to it.titleAr }) { activityId = it }
                OutlinedButton(onClick = { vm.assign(v, activityId) }, enabled = activityId.isNotEmpty()) { Text(stringResource(R.string.v_assign)) }
            }
            info?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
            ErrorText(err)
        }
    }
}

@Composable
private fun Labeled(label: String, value: String, ltr: Boolean = false) = Column {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = if (ltr) MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr) else MaterialTheme.typography.bodyLarge)
}

// ───────────────────────── Media ─────────────────────────

/** Streams a picked file to the API without loading it into memory. */
private class UriBody(private val ctx: android.content.Context, private val uri: Uri, private val type: String, private val size: Long) : RequestBody() {
    override fun contentType() = type.toMediaType()
    override fun contentLength() = size
    override fun writeTo(sink: BufferedSink) {
        ctx.contentResolver.openInputStream(uri)?.use { sink.writeAll(it.source()) }
    }
}

@HiltViewModel
class MediaVm @Inject constructor(private val api: AdminApi, @ApplicationContext private val ctx: android.content.Context) : ListVm<AdminMedia>() {
    val r2Enabled = MutableStateFlow(false)
    init { reload() }
    override suspend fun fetch(page: Int): Result<Pair<List<AdminMedia>, Boolean>> =
        when (val r = apiCall { api.media(page) }) { is Result.Ok -> { r2Enabled.value = r.value.r2Enabled; Result.Ok(r.value.items to r.value.hasMore) }; is Result.Err -> r }
    fun addLink(i: ExternalMediaInput, onOk: () -> Unit) = act(onOk = onOk) { apiCall { api.addExternalMedia(i) } }
    fun toggle(m: AdminMedia) = act { apiCall { api.updateMedia(m.id, MediaUpdate(status = if (m.status == "hidden") "published" else "hidden")) } }
    fun delete(m: AdminMedia) = act { apiCall { api.deleteMedia(m.id) } }
    fun upload(uri: Uri, title: String, category: String, onOk: () -> Unit) = act(onOk = onOk) {
        val (name, size) = withContext(Dispatchers.IO) {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) (c.getString(0) ?: "file") to c.getLong(1) else null
            } ?: ("file" to -1L)
        }
        val type = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val text = "text/plain".toMediaType()
        apiCall {
            api.uploadMedia(
                MultipartBody.Part.createFormData("file", name, UriBody(ctx, uri, type, size)),
                title.toRequestBody(text), category.ifBlank { "general" }.toRequestBody(text), "false".toRequestBody(text),
            )
        }
    }
}

@Composable
fun MediaScreen(back: () -> Unit, vm: MediaVm = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val r2 by vm.r2Enabled.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    var deleting by remember { mutableStateOf<AdminMedia?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked = it }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.a_media), back) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.m_add_link)) }) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList(s, vm::reload, vm::loadMore, header = {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r2) OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) { Text(stringResource(R.string.m_upload)) }
                        else Text(stringResource(R.string.m_r2_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        ErrorText(err)
                    }
                }
            }) { list ->
                items(list, key = { it.id }) { m ->
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.fillMaxWidth(0.28f).aspectRatio(1f)) {
                                RemoteImage(m.thumbnailUrl ?: m.url.takeIf { m.kind == "photo" }, m.titleAr, Modifier.fillMaxSize())
                                if (m.kind == "video") Icon(Icons.Outlined.PlayCircle, null, Modifier.align(Alignment.Center), tint = Color.White)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(m.titleAr ?: m.url, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Tag(m.category); if (m.status == "hidden") Tag(statusLabel("hidden"), MaterialTheme.colorScheme.errorContainer) }
                                Row {
                                    TextButton(onClick = { vm.toggle(m) }) { Text(stringResource(if (m.status == "hidden") R.string.m_show else R.string.m_hide)) }
                                    TextButton(onClick = { deleting = m }) { Text(stringResource(R.string.a_delete), color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        var kind by rememberSaveable { mutableStateOf("photo") }
        var url by rememberSaveable { mutableStateOf("") }
        var thumb by rememberSaveable { mutableStateOf("") }
        var title by rememberSaveable { mutableStateOf("") }
        var category by rememberSaveable { mutableStateOf("general") }
        val ok = url.trim().startsWith("https://") && (thumb.isBlank() || thumb.trim().startsWith("https://"))
        EditorDialog(stringResource(R.string.m_add_link), { adding = false }) {
            Choice(stringResource(R.string.m_kind), kind, listOf("photo" to stringResource(R.string.m_photo), "video" to stringResource(R.string.m_video))) { kind = it }
            Field(url, { url = it }, stringResource(R.string.m_url), ltr = true, keyboard = KeyboardType.Uri, isError = url.isNotBlank() && !url.trim().startsWith("https://"))
            Field(thumb, { thumb = it }, stringResource(R.string.m_thumb), ltr = true, keyboard = KeyboardType.Uri)
            Field(title, { title = it }, stringResource(R.string.a_title_ar))
            Field(category, { category = it }, stringResource(R.string.a_category))
            ErrorText(err)
            PrimaryButton(stringResource(dz.association.app.core.R.string.save), {
                vm.addLink(ExternalMediaInput(kind, url.trim(), thumb.blankToNull(), title.blankToNull(), category.ifBlank { "general" }), { adding = false })
            }, enabled = ok, loading = busy)
        }
    }
    picked?.let { uri ->
        var title by rememberSaveable { mutableStateOf("") }
        var category by rememberSaveable { mutableStateOf("general") }
        EditorDialog(stringResource(R.string.m_upload), { picked = null }) {
            Field(title, { title = it }, stringResource(R.string.a_title_ar))
            Field(category, { category = it }, stringResource(R.string.a_category))
            if (busy) Text(stringResource(R.string.m_uploading))
            ErrorText(err)
            PrimaryButton(stringResource(R.string.m_upload), { vm.upload(uri, title, category) { picked = null } }, loading = busy)
        }
    }
    deleting?.let { m -> ConfirmDialog(stringResource(R.string.a_confirm_delete), { vm.delete(m) }, { deleting = null }) }
}

