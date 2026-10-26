package dz.association.admin.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.admin.R
import dz.association.admin.data.AdminApi
import dz.association.admin.data.AdminRoles
import dz.association.admin.data.LectureInput
import dz.association.admin.data.LectureRegistration
import dz.association.admin.data.StatusBody
import dz.association.admin.ui.CheckRow
import dz.association.admin.ui.Choice
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
import dz.association.app.core.network.Lecture
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.LectureLabels
import dz.association.app.ui.components.PrimaryButton
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import dz.association.app.core.R as CoreR

@HiltViewModel
class LecturesVm @Inject constructor(private val api: AdminApi) : ListVm<Lecture>() {
    val registrations = MutableStateFlow<Pair<Lecture, List<LectureRegistration>>?>(null)
    init { reload() }
    override suspend fun fetch(page: Int): Result<Pair<List<Lecture>, Boolean>> =
        when (val r = apiCall { api.lectures() }) { is Result.Ok -> Result.Ok(r.value.items to false); is Result.Err -> r }
    fun save(id: String?, input: LectureInput, onOk: () -> Unit) = act(onOk = onOk) {
        if (id == null) apiCall { api.createLecture(input) } else apiCall { api.updateLecture(id, input) }
    }
    fun delete(id: String, onOk: () -> Unit) = act(onOk = onOk) { apiCall { api.deleteLecture(id) } }
    fun openRegistrations(l: Lecture) = act(reloadAfter = false) {
        apiCall { api.lectureRegistrations(l.id) }.also { if (it is Result.Ok) registrations.value = l to it.value.items }
    }
    fun setStatus(userId: String, status: String) {
        val l = registrations.value?.first ?: return
        act(reloadAfter = false) { apiCall { api.setLectureRegistration(l.id, userId, StatusBody(status)) }.also { if (it is Result.Ok) openRegistrations(l) } }
    }
}

@Composable
fun LecturesScreen(back: () -> Unit, role: String, vm: LecturesVm = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val regs by vm.registrations.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Lecture?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = { AppTopBar(stringResource(CoreR.string.lectures_title), back) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.a_add)) }) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList(s, vm::reload, vm::loadMore) { list ->
                items(list, key = { it.id }) { l ->
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { editing = l }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(l.titleAr, style = MaterialTheme.typography.titleSmall)
                            Text(formatDateTime(l.startsAt), style = MaterialTheme.typography.bodySmall)
                            l.speaker?.let { Text(stringResource(CoreR.string.lec_speaker, it), style = MaterialTheme.typography.bodySmall) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Tag(LectureLabels.kind(l.kind))
                                Tag(statusLabel(l.status), if (l.status == "published") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                Tag(stringResource(R.string.a_registered_n, l.registeredCount) + (l.capacity?.let { " / $it" } ?: ""), MaterialTheme.colorScheme.surfaceVariant)
                            }
                            OutlinedButton(onClick = { vm.openRegistrations(l) }) { Text(stringResource(R.string.a_participants)) }
                        }
                    }
                }
            }
        }
    }
    if (creating || editing != null) LectureEditor(editing, vm, canDelete = AdminRoles.atLeast(role, "ADMIN")) { creating = false; editing = null }
    regs?.let { (l, list) -> RegistrationsDialog(l, list, vm) }
}

@Composable
private fun RegistrationsDialog(l: Lecture, list: List<LectureRegistration>, vm: LecturesVm) {
    val ctx = LocalContext.current
    val csv = registrationsCsv(list)
    EditorDialog("${stringResource(R.string.a_participants)} — ${l.titleAr}", { vm.registrations.value = null }) {
        if (list.isEmpty()) dz.association.app.ui.components.EmptyState()
        else TextButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_SUBJECT, l.titleAr).putExtra(Intent.EXTRA_TEXT, csv)
            ctx.startActivity(Intent.createChooser(send, null))
        }) { Icon(Icons.Outlined.Share, null); Text(" CSV") }
        list.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider()
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${r.firstName} ${r.lastName}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(r.phone, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                r.email?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)) }
                val bits = listOfNotNull(
                    LectureLabels.gender(r.gender), r.birthYear?.toString(), r.wilaya,
                    LectureLabels.marital(r.maritalStatus) + (r.childrenCount?.let { " · ${stringResource(CoreR.string.lec_children)}: $it" } ?: ""),
                    LectureLabels.occupation(r.occupation) + (r.occupationDetail?.let { " ($it)" } ?: ""),
                )
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                r.note?.let { Text("💬 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(statusLabelReg(r.status))
                    TextButton(onClick = { vm.setStatus(r.userId, "attended") }) { Text(stringResource(R.string.a_attended)) }
                    TextButton(onClick = { vm.setStatus(r.userId, "cancelled") }) { Text(stringResource(R.string.a_cancel_reg)) }
                }
            }
        }
    }
}

@Composable
private fun statusLabelReg(s: String) = when (s) {
    "attended" -> stringResource(R.string.a_attended)
    "cancelled" -> stringResource(R.string.a_cancel_reg)
    else -> stringResource(CoreR.string.lec_registered)
}

/** Spreadsheet-friendly export (UTF-8, quoted fields; formulas are neutralised). */
fun registrationsCsv(list: List<LectureRegistration>): String {
    fun q(v: Any?): String {
        val s = v?.toString().orEmpty().let { if (it.firstOrNull() in setOf('=', '+', '-', '@')) "'$it" else it }
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
    val head = listOf("first_name", "last_name", "phone", "email", "gender", "birth_year", "wilaya", "marital_status", "children", "occupation", "occupation_detail", "note", "status")
    return (listOf(head.joinToString(",")) + list.map { r ->
        listOf(r.firstName, r.lastName, r.phone, r.email, r.gender, r.birthYear, r.wilaya, r.maritalStatus, r.childrenCount, r.occupation, r.occupationDetail, r.note, r.status).joinToString(",") { q(it) }
    }).joinToString("\n")
}

@Composable
private fun LectureEditor(l: Lecture?, vm: LecturesVm, canDelete: Boolean, close: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(l?.kind ?: "lecture") }
    var titleAr by rememberSaveable { mutableStateOf(l?.titleAr.orEmpty()) }
    var titleFr by rememberSaveable { mutableStateOf(l?.titleFr.orEmpty()) }
    var descAr by rememberSaveable { mutableStateOf(l?.descriptionAr.orEmpty()) }
    var speaker by rememberSaveable { mutableStateOf(l?.speaker.orEmpty()) }
    var location by rememberSaveable { mutableStateOf(l?.location.orEmpty()) }
    var online by rememberSaveable { mutableStateOf(l?.onlineUrl.orEmpty()) }
    var starts by rememberSaveable { mutableStateOf(l?.startsAt ?: 0L) }
    var ends by rememberSaveable { mutableStateOf(l?.endsAt ?: 0L) }
    var capacity by rememberSaveable { mutableStateOf(l?.capacity?.toString().orEmpty()) }
    var regOpen by rememberSaveable { mutableStateOf(l?.registrationOpen ?: true) }
    var status by rememberSaveable { mutableStateOf(l?.status ?: "published") }
    var notify by rememberSaveable { mutableStateOf(true) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    val onlineOk = online.isBlank() || online.trim().startsWith("https://")
    val problems = scheduleProblems(titleAr, starts, ends) + listOfNotNull(if (!onlineOk) stringResource(R.string.a_problem_link) else null)
    val valid = problems.isEmpty()
    EditorDialog(stringResource(if (l == null) R.string.a_add else R.string.a_edit), close) {
        Choice(stringResource(CoreR.string.lec_kind), kind, LectureLabels.kinds.map { it to LectureLabels.kind(it) }) { kind = it }
        Field(titleAr, { titleAr = it }, stringResource(R.string.a_title_ar), isError = tried && titleAr.trim().length < 3, )
        Field(titleFr, { titleFr = it }, stringResource(R.string.a_title_fr), ltr = true)
        Field(speaker, { speaker = it }, stringResource(CoreR.string.lec_speaker_label))
        Field(descAr, { descAr = it }, stringResource(R.string.a_desc_ar), lines = 4)
        DateTimeField(stringResource(R.string.a_starts), starts, isError = tried && starts <= 0) { starts = it; if (ends <= it) ends = it + 2 * 3600_000 }
        DateTimeField(stringResource(R.string.a_ends), ends, isError = tried && ends <= starts) { ends = if (starts > 0 && it <= starts) starts + 2 * 3600_000 else it }
        Field(location, { location = it }, stringResource(R.string.a_location))
        Field(online, { online = it }, "https:// (Meet / Zoom)", ltr = true, keyboard = KeyboardType.Uri, isError = !onlineOk)
        Field(capacity, { capacity = it.filter(Char::isDigit) }, stringResource(R.string.a_capacity), keyboard = KeyboardType.Number)
        Choice(stringResource(R.string.a_status), status, listOf("draft", "published", "cancelled").map { it to statusLabel(it) }) { status = it }
        CheckRow(stringResource(R.string.a_registration_open), regOpen) { regOpen = it }
        if (l == null || l.status != "published") CheckRow(stringResource(R.string.a_notify), notify) { notify = it }
        if (tried) ProblemsText(problems)
        ErrorText(err)
        PrimaryButton(stringResource(CoreR.string.save), {
            tried = true
            if (valid) vm.save(l?.id, LectureInput(
                kind, titleAr.trim(), titleFr.blankToNull(), descAr.blankToNull(), null, speaker.blankToNull(), location.blankToNull(),
                online.blankToNull(), starts, ends, capacity.toIntOrNull()?.takeIf { it > 0 }, regOpen, status, notify,
            ), close)
        }, loading = busy)
        if (l != null && canDelete) {
            if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.a_delete), color = MaterialTheme.colorScheme.error) }
            else OutlinedButton(onClick = { vm.delete(l.id, close) }) { Text(stringResource(R.string.a_confirm_delete), color = MaterialTheme.colorScheme.error) }
        }
    }
}
