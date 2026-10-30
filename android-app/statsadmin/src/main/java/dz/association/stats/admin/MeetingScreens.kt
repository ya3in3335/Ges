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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dz.association.app.core.R as CoreR
import dz.association.app.core.util.formatDate
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.UiState
import dz.association.stats.R
import dz.association.stats.data.MeetingRequest
import dz.association.stats.ui.EmptyNote
import dz.association.stats.ui.ErrorBox
import dz.association.stats.ui.Loading
import dz.association.stats.ui.SectionTitle
import dz.association.stats.ui.StatsCard
import dz.association.stats.ui.StatsColors
import java.util.Calendar
import java.util.TimeZone

private val ALGIERS: TimeZone = TimeZone.getTimeZone("Africa/Algiers")

@Composable
fun MeetingsScreen(onOpen: (String) -> Unit, onNew: () -> Unit, vm: MeetingsVm = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val manager = LocalLevel.current == "manager"
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.st_meetings)) },
        floatingActionButton = {
            if (manager) ExtendedFloatingActionButton(onClick = onNew, containerColor = StatsColors.Primary, contentColor = StatsColors.Background) {
                Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.st_new_meeting))
            }
        },
    ) { pad ->
        when (val s = state) {
            UiState.Loading -> Loading(Modifier.padding(pad))
            is UiState.Error -> Box(Modifier.padding(pad).padding(16.dp)) { ErrorBox(s.error) { vm.reload() } }
            is UiState.Success -> LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.data.isEmpty()) item { EmptyNote(stringResource(R.string.st_no_data)) }
                items(s.data, key = { it.id }) { m -> MeetingRow(m) { onOpen(m.id) } }
            }
        }
    }
}

@Composable
fun MeetingScreen(id: String, back: () -> Unit, onEdit: () -> Unit, onRegistrants: () -> Unit, onReport: () -> Unit, vm: MeetingVm = hiltViewModel()) {
    vm.id = id
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val manager = LocalLevel.current == "manager"
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(report) { if (report != null) { vm.report.value = null; onReport() } }
    Scaffold(topBar = {
        AppTopBar(stringResource(R.string.st_meeting), back, actions = {
            val m = (state as? UiState.Success)?.data
            if (manager && m?.status == "open") IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.st_edit_meeting)) }
        })
    }) { pad ->
        when (val s = state) {
            UiState.Loading -> Loading(Modifier.padding(pad))
            is UiState.Error -> Box(Modifier.padding(pad).padding(16.dp)) { ErrorBox(s.error) { vm.reload() } }
            is UiState.Success -> {
                val m = s.data
                Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MeetingRow(m) {}
                    StatsCard {
                        m.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = StatsColors.Text, style = MaterialTheme.typography.bodyMedium) }
                        Text("${stringResource(R.string.st_checkin_window)}: ${formatDateTime(m.checkinOpensAt)} → ${formatDateTime(m.checkinClosesAt)}", color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text("${stringResource(R.string.st_status_late)} > ${formatDateTime(m.lateAfter)}", color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        if (m.status == "open") Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.st_registration_open_switch), Modifier.weight(1f), color = StatsColors.Text)
                            Switch(m.registrationEnabled, { vm.setRegistration(it) }, enabled = manager && !busy)
                        }
                    }
                    actionError?.let { ErrorBox(it) }
                    PrimaryButton(stringResource(R.string.st_view_registrants), onRegistrants)
                    OutlinedButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_final_report)) }
                    if (manager && m.status == "open") {
                        PrimaryButton(stringResource(R.string.st_close_meeting), { confirmClose = true }, loading = busy)
                        OutlinedButton(onClick = { confirmCancel = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_cancel_meeting), color = StatsColors.Danger) }
                    }
                }
            }
        }
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false }, text = { Text(stringResource(R.string.st_close_confirm)) },
        confirmButton = { TextButton(onClick = { confirmClose = false; vm.close() }) { Text(stringResource(CoreR.string.confirm)) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(CoreR.string.cancel)) } },
    )
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false }, text = { Text(stringResource(R.string.st_cancel_meeting) + "?") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; vm.cancelMeeting() }) { Text(stringResource(CoreR.string.confirm)) } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

/** Algiers wall-clock date + time → epoch milliseconds. */
fun algiersMillis(dateUtcMidnight: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateUtcMidnight }
    return Calendar.getInstance(ALGIERS).apply {
        clear(); set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), hour, minute, 0)
    }.timeInMillis
}

@Composable
fun MeetingEditScreen(id: String?, back: () -> Unit, onSaved: (String) -> Unit, vm: MeetingEditVm = hiltViewModel()) {
    LaunchedEffect(id) { vm.load(id) }
    val existing by vm.existing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf<Long?>(null) } // UTC midnight of the chosen day
    var hour by rememberSaveable { mutableStateOf(10) }
    var minute by rememberSaveable { mutableStateOf(0) }
    var before by rememberSaveable { mutableStateOf("60") }
    var after by rememberSaveable { mutableStateOf("180") }
    var late by rememberSaveable { mutableStateOf("15") }
    var open by rememberSaveable { mutableStateOf(true) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var filled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(existing) {
        val m = existing ?: return@LaunchedEffect
        if (filled) return@LaunchedEffect
        filled = true
        title = m.title; desc = m.description.orEmpty(); location = m.location; open = m.registrationEnabled
        val c = Calendar.getInstance(ALGIERS).apply { timeInMillis = m.startsAt }
        date = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)) }.timeInMillis
        hour = c.get(Calendar.HOUR_OF_DAY); minute = c.get(Calendar.MINUTE)
        before = ((m.startsAt - m.checkinOpensAt) / 60_000).toString()
        after = ((m.checkinClosesAt - m.startsAt) / 60_000).toString()
        late = ((m.lateAfter - m.startsAt) / 60_000).toString()
    }
    val titleErr = tried && title.trim().length < 3
    val locErr = tried && location.trim().length < 2
    val dateErr = tried && date == null
    val nums = listOf(before, after, late).map { it.toIntOrNull() }
    val numErr = tried && (nums.any { it == null || it < 0 || it > 24 * 60 } || (nums[2] ?: 0) > (nums[1] ?: 0) || (nums[1] ?: 0) == 0 && (nums[0] ?: 0) == 0)
    Scaffold(topBar = { AppTopBar(stringResource(if (id == null) R.string.st_new_meeting else R.string.st_edit_meeting), back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatsCard {
                OutlinedTextField(title, { title = it.take(200) }, label = { Text(stringResource(R.string.st_meeting_title)) }, singleLine = true, isError = titleErr,
                    supportingText = if (titleErr) ({ Text(stringResource(R.string.st_err_required)) }) else null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(location, { location = it.take(300) }, label = { Text(stringResource(R.string.st_meeting_location)) }, singleLine = true, isError = locErr,
                    supportingText = if (locErr) ({ Text(stringResource(R.string.st_err_required)) }) else null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it.take(2000) }, label = { Text(stringResource(R.string.st_meeting_desc)) }, minLines = 2, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                        Text(date?.let { formatDate(algiersMillis(it, 12, 0)) } ?: stringResource(R.string.st_meeting_date), color = if (dateErr) StatsColors.Danger else StatsColors.Text)
                    }
                    OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) { Text("%02d:%02d".format(hour, minute), color = StatsColors.Text) }
                }
            }
            StatsCard {
                SectionTitle(stringResource(R.string.st_checkin_window))
                listOf(
                    Triple(R.string.st_checkin_opens, before) { v: String -> before = v },
                    Triple(R.string.st_checkin_closes, after) { v: String -> after = v },
                    Triple(R.string.st_late_after, late) { v: String -> late = v },
                ).forEach { (label, value, set) ->
                    OutlinedTextField(value, { set(it.filter(Char::isDigit).take(4)) }, label = { Text(stringResource(label)) }, singleLine = true, isError = numErr,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                }
                if (numErr) Text(stringResource(R.string.st_err_checkin_window), color = StatsColors.Danger, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.st_registration_open_switch), Modifier.weight(1f), color = StatsColors.Text)
                    Switch(open, { open = it })
                }
            }
            error?.let { ErrorBox(it) }
            PrimaryButton(stringResource(R.string.st_save), {
                tried = true
                val d = date
                if (title.trim().length >= 3 && location.trim().length >= 2 && d != null && !(nums.any { it == null || it < 0 || it > 24 * 60 } || nums[2]!! > nums[1]!! || (nums[1] == 0 && nums[0] == 0))) {
                    val starts = algiersMillis(d, hour, minute)
                    vm.save(id, MeetingRequest(
                        title.trim(), desc.trim().ifBlank { null }, location.trim(), starts,
                        starts - nums[0]!! * 60_000L, starts + nums[1]!! * 60_000L, starts + nums[2]!! * 60_000L, open,
                    ), onSaved)
                }
            }, loading = busy)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = date ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { date = st.selectedDateMillis ?: date; pickDate = false }) { Text(stringResource(CoreR.string.confirm)) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(CoreR.string.cancel)) } },
        ) { DatePicker(st) }
    }
    if (pickTime) {
        val st = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = { hour = st.hour; minute = st.minute; pickTime = false }) { Text(stringResource(CoreR.string.confirm)) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(CoreR.string.cancel)) } },
        )
    }
}
