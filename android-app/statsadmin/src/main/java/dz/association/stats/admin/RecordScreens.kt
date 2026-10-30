package dz.association.stats.admin

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dz.association.app.core.R as CoreR
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.core.util.AppLocale
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.UiState
import dz.association.stats.R
import dz.association.stats.data.Registration
import dz.association.stats.ui.CensusForm
import dz.association.stats.ui.ChoiceChips
import dz.association.stats.ui.Dropdown
import dz.association.stats.ui.EmptyNote
import dz.association.stats.ui.ErrorBox
import dz.association.stats.ui.Loading
import dz.association.stats.ui.SectionTitle
import dz.association.stats.ui.StatsCard
import dz.association.stats.ui.StatsColors
import dz.association.stats.ui.StatusBadge
import dz.association.stats.ui.genderLabel
import dz.association.stats.ui.maritalLabel
import dz.association.stats.ui.statusLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val STATUSES = listOf("", "registered", "present", "late", "absent", "cancelled")

@Composable
fun RecordsScreen(meeting: String?, onOpen: (String) -> Unit, vm: RecordsVm = hiltViewModel()) {
    LaunchedEffect(meeting) { vm.update(vm.filters.value.copy(meetingId = meeting)); if (vm.items.value.isEmpty()) vm.load(reset = true) }
    val f by vm.filters.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val meetings by vm.meetings.collectAsStateWithLifecycle()
    val manager = LocalLevel.current == "manager"
    var text by rememberSaveable { mutableStateOf(f.query) }
    var exportMenu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<AppError?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val headers = exportHeaders()
    val labels = exportCellLabels()
    // Search as you type, after a short pause.
    LaunchedEffect(text) { delay(350); vm.update(vm.filters.value.copy(query = text)) }

    fun export(kind: String) = scope.launch {
        exporting = true; exportError = null
        when (val r = vm.exportRows()) {
            is Result.Ok -> {
                val table = Table(context.getString(R.string.st_records), context.getString(R.string.st_generated_at, formatDateTime(System.currentTimeMillis())),
                    headers, r.value.map { exportRow(it, labels) }, rtl = !AppLocale.isFrench)
                val stamp = System.currentTimeMillis()
                when (kind) {
                    "pdf" -> Exporter.share(context, Exporter.pdf(context, table), "census-$stamp.pdf", "application/pdf")
                    "xlsx" -> Exporter.share(context, TableFormats.xlsx(table), "census-$stamp.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    else -> Exporter.share(context, TableFormats.csv(table), "census-$stamp.csv", "text/csv")
                }
            }
            is Result.Err -> exportError = r.error
        }
        exporting = false
    }

    Scaffold(topBar = {
        AppTopBar(stringResource(R.string.st_records), actions = {
            if (manager) {
                if (exporting) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { exportMenu = true }) { Icon(Icons.Outlined.FileDownload, stringResource(R.string.st_export)) }
                DropdownMenu(exportMenu, { exportMenu = false }) {
                    listOf("pdf" to R.string.st_export_pdf, "xlsx" to R.string.st_export_xlsx, "csv" to R.string.st_export_csv).forEach { (k, l) ->
                        DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { exportMenu = false; export(k) })
                    }
                }
            }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(text, { text = it.take(80) }, placeholder = { Text(stringResource(R.string.st_search_hint)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            }
            item {
                val all = stringResource(R.string.st_all)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dropdown(stringResource(R.string.st_meeting), listOf("") + meetings.map { it.id }, f.meetingId ?: "",
                        { id -> meetings.firstOrNull { it.id == id }?.title ?: all }, { vm.update(f.copy(meetingId = it.ifEmpty { null })) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dropdown(stringResource(R.string.st_wilaya), listOf(0) + vm.geo.wilayas.map { it.code }, f.wilaya ?: 0,
                            { c -> vm.geo.wilaya(c)?.label ?: all }, { vm.update(f.copy(wilaya = it.takeIf { c -> c > 0 }, commune = null)) }, Modifier.weight(1f), searchable = true)
                        Dropdown(stringResource(R.string.st_commune), listOf(0) + (f.wilaya?.let { vm.geo.communesOf(it).map { c -> c.id } } ?: emptyList()), f.commune ?: 0,
                            { c -> vm.geo.commune(c)?.label ?: all }, { vm.update(f.copy(commune = it.takeIf { c -> c > 0 })) }, Modifier.weight(1f), enabled = f.wilaya != null, searchable = true)
                    }
                    ChoiceChips(STATUSES, f.status ?: "", { if (it.isEmpty()) all else statusLabel(it) }, { vm.update(f.copy(status = it.ifEmpty { null })) })
                }
            }
            item { Text(stringResource(R.string.st_results_count, total), color = StatsColors.TextSecondary, style = MaterialTheme.typography.labelMedium) }
            exportError?.let { e -> item { ErrorBox(e) } }
            error?.let { e -> item { ErrorBox(e) { vm.load(reset = true) } } }
            if (loading && items.isEmpty()) item { Box(Modifier.fillMaxWidth().height(120.dp)) { Loading() } }
            if (!loading && items.isEmpty() && error == null) item { EmptyNote(stringResource(R.string.st_no_data)) }
            items(items, key = { it.id }) { r -> RegistrationLine(r) { onOpen(r.id) } }
            if (hasMore) item {
                OutlinedButton(onClick = { vm.load(reset = false) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_load_more)) }
            }
        }
    }
}

data class CellLabels(val status: (String) -> String, val marital: (String) -> String, val gender: (String?) -> String, val method: (String?) -> String, val source: (String) -> String)

@Composable
fun exportHeaders() = listOf(
    R.string.st_reg_no, R.string.st_first_name, R.string.st_last_name, R.string.st_gender_dist, R.string.st_wilaya, R.string.st_commune, R.string.st_sec_birth,
    R.string.st_sec_marital, R.string.st_meeting, R.string.st_meeting_date, R.string.st_status_label, R.string.st_confirmed_at, R.string.st_by,
).map { stringResource(it) }

@Composable
fun exportCellLabels(): CellLabels {
    val st = listOf("registered", "present", "late", "absent", "cancelled").associateWith { statusLabel(it) }
    val ma = listOf("single", "married", "divorced", "widowed").associateWith { maritalLabel(it) }
    val ge = mapOf("male" to genderLabel("male"), "female" to genderLabel("female"), "" to genderLabel(null))
    val me = mapOf("qr" to stringResource(R.string.st_method_qr), "manual" to stringResource(R.string.st_method_manual), "number" to stringResource(R.string.st_method_number))
    return CellLabels({ st[it] ?: it }, { ma[it] ?: it }, { ge[it ?: ""] ?: "" }, { me[it] ?: "" }, { it })
}

fun exportRow(r: Registration, l: CellLabels): List<String> = listOf(
    r.regNo.toString(), r.firstName, r.lastName, l.gender(r.gender),
    "${r.wilayaCode.toString().padStart(2, '0')} ${if (AppLocale.isFrench) r.wilayaFr ?: r.wilayaAr.orEmpty() else r.wilayaAr.orEmpty()}",
    (if (AppLocale.isFrench) r.communeFr ?: r.communeAr else r.communeAr).orEmpty(),
    r.birthDate, l.marital(r.maritalStatus), r.meeting?.title.orEmpty(), r.meeting?.startsAt?.let(::formatDateTime).orEmpty(),
    l.status(r.status), r.checkedInAt?.let(::formatDateTime).orEmpty(),
    listOfNotNull(r.checkedInByName, l.method(r.checkinMethod).takeIf { it.isNotEmpty() }).joinToString(" · "),
)

@Composable
fun RecordScreen(id: String, back: () -> Unit, vm: RecordVm = hiltViewModel()) {
    vm.id = id
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val manager = LocalLevel.current == "manager"
    var editing by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        AppTopBar(stringResource(if (editing) R.string.st_edit_record else R.string.st_details), back, actions = {
            val r = (state as? UiState.Success)?.data
            if (manager && r != null && !editing) IconButton(onClick = { vm.startEdit(r); editing = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.st_edit_record)) }
        })
    }) { pad ->
        when (val s = state) {
            UiState.Loading -> Loading(Modifier.padding(pad))
            is UiState.Error -> Box(Modifier.padding(pad).padding(16.dp)) { ErrorBox(s.error) { vm.reload() } }
            is UiState.Success -> Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val r = s.data
                if (!editing) {
                    StatsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.firstName} ${r.lastName}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = StatsColors.Text)
                            StatusBadge(r.status)
                        }
                        Field(stringResource(R.string.st_reg_no), "#${r.regNo}")
                        Field(stringResource(R.string.st_meeting), r.meeting?.let { "${it.title} — ${formatDateTime(it.startsAt)}" }.orEmpty())
                        HorizontalDivider(color = StatsColors.Line)
                        Field(stringResource(R.string.st_wilaya), "${r.wilayaCode.toString().padStart(2, '0')} ${r.wilayaAr.orEmpty()}")
                        Field(stringResource(R.string.st_commune), r.communeAr.orEmpty())
                        Field(stringResource(R.string.st_sec_birth), r.birthDate)
                        Field(stringResource(R.string.st_sec_marital), maritalLabel(r.maritalStatus))
                        Field(stringResource(R.string.st_gender_dist), genderLabel(r.gender))
                        HorizontalDivider(color = StatsColors.Line)
                        r.checkedInAt?.let { Field(stringResource(R.string.st_confirmed_at), formatDateTime(it)) }
                        r.checkedInByName?.let { Field(stringResource(R.string.st_by), it) }
                        r.checkinMethod?.let { Field(stringResource(R.string.st_action), exportCellLabels().method(it)) }
                        Text(stringResource(if (r.source == "member") R.string.st_source_member else R.string.st_source_staff), color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    CensusForm(vm.form, vm.geo, meetings = null)
                    StatsCard {
                        SectionTitle(stringResource(R.string.st_status_label))
                        ChoiceChips(STATUSES.drop(1), status, { statusLabel(it) }, { vm.status.value = it })
                    }
                    actionError?.let { ErrorBox(it) }
                    PrimaryButton(stringResource(R.string.st_save), { vm.save { editing = false } }, loading = busy)
                    OutlinedButton(onClick = { editing = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(CoreR.string.cancel)) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.4f), color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(0.6f), color = StatsColors.Text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ───────────── Staff & audit ─────────────

@Composable
fun StaffScreen(back: () -> Unit, vm: StaffVm = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()
    var identifier by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("officer") }
    var removing by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.st_staff), back) }) { pad ->
        when (val s = state) {
            UiState.Loading -> Loading(Modifier.padding(pad))
            is UiState.Error -> Box(Modifier.padding(pad).padding(16.dp)) { ErrorBox(s.error) { vm.reload() } }
            is UiState.Success -> LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.data.canManage) item {
                    StatsCard {
                        SectionTitle(stringResource(R.string.st_add_staff))
                        OutlinedTextField(identifier, { identifier = it.take(254) }, label = { Text(stringResource(R.string.st_staff_identifier)) }, singleLine = true,
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                        ChoiceChips(listOf("officer", "manager"), level, { stringResource(if (it == "manager") R.string.st_level_manager else R.string.st_level_officer) }, { level = it })
                        actionError?.let { ErrorBox(it) }
                        PrimaryButton(stringResource(R.string.st_add_staff), { vm.grant(identifier, level) { identifier = "" } }, enabled = identifier.trim().length >= 3, loading = busy)
                    }
                }
                if (s.data.items.isEmpty()) item { EmptyNote(stringResource(R.string.st_no_data)) }
                items(s.data.items, key = { it.userId }) { m ->
                    StatsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.fullName, color = StatsColors.Text, style = MaterialTheme.typography.titleSmall)
                                Text(m.email, color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(if (m.level == "manager") R.string.st_level_manager else R.string.st_level_officer), color = StatsColors.Primary, style = MaterialTheme.typography.labelMedium)
                            }
                            if (s.data.canManage) IconButton(onClick = { removing = m.userId }) { Icon(Icons.Outlined.Delete, stringResource(R.string.st_remove), tint = StatsColors.Danger) }
                        }
                    }
                }
            }
        }
    }
    removing?.let { uid ->
        AlertDialog(
            onDismissRequest = { removing = null }, text = { Text(stringResource(R.string.st_remove) + "?") },
            confirmButton = { TextButton(onClick = { vm.revoke(uid); removing = null }) { Text(stringResource(CoreR.string.confirm)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(CoreR.string.cancel)) } },
        )
    }
}

@Composable
fun AuditScreen(back: () -> Unit, vm: AuditVm = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.st_audit), back) }) { pad ->
        when (val s = state) {
            UiState.Loading -> Loading(Modifier.padding(pad))
            is UiState.Error -> Box(Modifier.padding(pad).padding(16.dp)) { ErrorBox(s.error) { vm.reload() } }
            is UiState.Success -> LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.data.isEmpty()) item { EmptyNote(stringResource(R.string.st_no_data)) }
                items(s.data, key = { it.id }) { a ->
                    StatsCard {
                        Text(a.action, color = StatsColors.Primary, style = MaterialTheme.typography.labelLarge)
                        Text("${a.actor ?: "—"} · ${formatDateTime(a.at)}", color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        a.detail?.let { Text(it.take(300), color = StatsColors.Text, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
