package dz.association.stats.admin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PrimaryButton
import dz.association.stats.R
import dz.association.stats.data.Registration
import dz.association.stats.ui.CensusForm
import dz.association.stats.ui.Dropdown
import dz.association.stats.ui.EmptyNote
import dz.association.stats.ui.ErrorBox
import dz.association.stats.ui.SectionTitle
import dz.association.stats.ui.StatsCard
import dz.association.stats.ui.StatsColors
import dz.association.stats.ui.StatusBadge
import dz.association.stats.ui.statsError
import dz.association.stats.ui.statusColor
import dz.association.stats.ui.statusLabel

// ───────────── Registering a person ─────────────

@Composable
fun StaffRegisterScreen(vm: StaffRegisterVm = hiltViewModel()) {
    val meetings by vm.meetings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.st_census_title)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AnimatedVisibility(done != null) {
                done?.let { r ->
                    Surface(shape = RoundedCornerShape(18.dp), color = StatsColors.Primary.copy(alpha = 0.14f), border = BorderStroke(1.dp, StatsColors.Primary)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = StatsColors.Primary)
                            Column {
                                Text("${r.firstName} ${r.lastName}", color = StatsColors.Text, style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.st_census_success, r.regNo), color = StatsColors.Primary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            CensusForm(vm.form, vm.geo, meetings ?: emptyList())
            if (vm.form.showErrors && vm.form.errors(vm.geo).isNotEmpty()) Text(stringResource(R.string.st_form_fix_errors), color = MaterialTheme.colorScheme.error)
            error?.let { ErrorBox(it) }
            PrimaryButton(stringResource(R.string.st_register_btn), { vm.submit() }, enabled = !meetings.isNullOrEmpty(), loading = busy)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────── Attendance ─────────────

@Composable
fun CheckinScreen(vm: CheckinVm = hiltViewModel()) {
    val meetings by vm.meetings.collectAsStateWithLifecycle()
    val meetingId by vm.meetingId.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var number by rememberSaveable { mutableStateOf("") }
    val prompt = stringResource(R.string.st_scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        when {
            r.contents != null -> vm.byQr(r.contents)
            r.originalIntent?.hasExtra(Intents.Scan.MISSING_CAMERA_PERMISSION) == true -> vm.result.value = CheckinResult.Failed(
                dz.association.app.core.network.AppError.Api(0, "camera_permission"),
            )
        }
    }
    val meeting = meetings.firstOrNull { it.id == meetingId }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.st_checkin)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Dropdown(stringResource(R.string.st_pick_meeting), meetings, meeting, { "${it.title} — ${formatDateTime(it.startsAt)}" }, { vm.select(it.id) })
            if (meeting == null) { EmptyNote(stringResource(R.string.st_no_open_meetings)); return@Column }
            meeting.counts?.let { c ->
                Text("${stringResource(R.string.st_attended)}: ${c.attended} / ${c.total}", color = StatsColors.Primary, style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = {
                    scanner.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt).setBeepEnabled(true)
                            .setOrientationLocked(false).setCaptureActivity(PortraitCaptureActivity::class.java),
                    )
                },
                enabled = !busy, shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StatsColors.Primary, contentColor = StatsColors.Background),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(28.dp))
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.st_scan_qr), style = MaterialTheme.typography.titleMedium)
            }
            result?.let { CheckinResultCard(it) }
            StatsCard {
                SectionTitle(stringResource(R.string.st_by_number))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(number, { number = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.st_reg_no)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f))
                    Button(onClick = { number.toIntOrNull()?.let { vm.byNumber(it); number = "" } }, enabled = !busy && number.isNotEmpty()) { Text(stringResource(R.string.st_checkin)) }
                }
            }
            StatsCard {
                SectionTitle(stringResource(R.string.st_by_name), Icons.Outlined.Search)
                OutlinedTextField(query, { vm.search(it) }, placeholder = { Text(stringResource(R.string.st_search_hint)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                if (pending.isEmpty()) EmptyNote(stringResource(R.string.st_no_data))
                pending.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.firstName} ${r.lastName}", color = StatsColors.Text, style = MaterialTheme.typography.bodyLarge)
                            Text("#${r.regNo} · ${r.communeAr.orEmpty()}", color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.byId(r.id) }, enabled = !busy) { Text(stringResource(R.string.st_checkin)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckinResultCard(result: CheckinResult) {
    val (color, title, body) = when (result) {
        is CheckinResult.Ok -> Triple(statusColor(result.reg.status), stringResource(R.string.st_checkin_ok, "${result.reg.firstName} ${result.reg.lastName}"),
            "${statusLabel(result.reg.status)} · #${result.reg.regNo} · ${result.reg.checkedInAt?.let(::formatDateTime).orEmpty()}")
        is CheckinResult.Already -> Triple(StatsColors.Warning, stringResource(R.string.st_already_checked), "")
        is CheckinResult.Failed -> Triple(
            StatsColors.Danger,
            if ((result.error as? dz.association.app.core.network.AppError.Api)?.code == "camera_permission") stringResource(R.string.st_camera_needed) else statsError(result.error),
            "",
        )
    }
    Surface(shape = RoundedCornerShape(20.dp), color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (result is CheckinResult.Ok) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null, tint = color, modifier = Modifier.size(34.dp))
            Column {
                Text(title, color = StatsColors.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (body.isNotBlank()) Text(body, color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun RegistrationLine(r: Registration, onClick: () -> Unit) {
    StatsCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${r.firstName} ${r.lastName}", color = StatsColors.Text, style = MaterialTheme.typography.titleSmall)
                Text("#${r.regNo} · ${r.wilayaAr.orEmpty()} / ${r.communeAr.orEmpty()}", color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                r.meeting?.let { Text(it.title, color = StatsColors.TextSecondary, style = MaterialTheme.typography.labelSmall) }
            }
            StatusBadge(r.status)
        }
    }
}
