package dz.association.stats.member

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dz.association.app.core.R as CoreR
import dz.association.app.core.util.AppLocale
import dz.association.app.core.util.formatDate
import dz.association.app.core.util.formatDateTime
import dz.association.app.core.util.loc
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.LogoImage
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.UiState
import dz.association.app.ui.components.roleLabel
import dz.association.stats.R
import dz.association.stats.data.Meeting
import dz.association.stats.data.Registration
import dz.association.stats.ui.CensusForm
import dz.association.stats.ui.ChoiceChips
import dz.association.stats.ui.EmptyNote
import dz.association.stats.ui.ErrorBox
import dz.association.stats.ui.Loading
import dz.association.stats.ui.QrImage
import dz.association.stats.ui.SectionTitle
import dz.association.stats.ui.StatsCard
import dz.association.stats.ui.StatsColors
import dz.association.stats.ui.StatusBadge
import dz.association.stats.ui.statusColor
import dz.association.stats.ui.statusLabel

private val Pad = PaddingValues(16.dp)

@Composable
private fun LiveHint() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Sync, null, tint = StatsColors.Primary, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.st_live_updates), style = MaterialTheme.typography.labelSmall, color = StatsColors.TextSecondary)
    }
}

@Composable
private fun MeetingInfo(m: Meeting) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Event, null, tint = StatsColors.TextSecondary, modifier = Modifier.size(16.dp))
        Text(formatDateTime(m.startsAt), style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Place, null, tint = StatsColors.TextSecondary, modifier = Modifier.size(16.dp))
        Text(m.location, style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary)
    }
}

// ───────────── Home ─────────────

@Composable
fun HomeScreen(onRegister: (String) -> Unit, onCard: (String) -> Unit, vm: HomeVm = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        UiState.Loading -> Loading()
        is UiState.Error -> Box(Modifier.padding(Pad)) { ErrorBox(s.error) { vm.reload() } }
        is UiState.Success -> {
            val d = s.data
            val open = d.meetings.filter { it.canJoin }
            val myUpcoming = d.mine.filter { it.meeting?.status == "open" && it.status != "cancelled" }
            LazyColumn(contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Box(
                        Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(StatsColors.PrimaryDeep, StatsColors.Surface)), RoundedCornerShape(26.dp)).padding(18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            LogoImage(Modifier.size(64.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.st_home_hello, d.me?.fullName?.substringBefore(' ') ?: ""), style = MaterialTheme.typography.titleLarge, color = StatsColors.Text)
                                d.me?.let { me ->
                                    Text(roleLabel(me.role), style = MaterialTheme.typography.bodySmall, color = StatsColors.Primary)
                                    me.createdAt?.let { Text(stringResource(R.string.st_member_since, formatDate(it)), style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary) }
                                    if (me.phoneVerified || me.emailVerified) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Outlined.Verified, null, tint = StatsColors.Primary, modifier = Modifier.size(14.dp))
                                        Text(stringResource(if (me.phoneVerified) CoreR.string.phone_verified else CoreR.string.email), style = MaterialTheme.typography.labelSmall, color = StatsColors.TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
                item { LiveHint() }
                item { SectionTitle(stringResource(R.string.st_open_for_registration), Icons.Outlined.Event) }
                if (open.isEmpty()) item { EmptyNote(stringResource(R.string.st_no_open_meetings)) }
                items(open, key = { "o" + it.id }) { m ->
                    StatsCard(onClick = { onRegister(m.id) }) {
                        Text(m.title, style = MaterialTheme.typography.titleMedium, color = StatsColors.Text)
                        MeetingInfo(m)
                        PrimaryButton(stringResource(R.string.st_register_now), { onRegister(m.id) })
                    }
                }
                item { SectionTitle(stringResource(R.string.st_my_meetings), Icons.Outlined.CheckCircle) }
                if (myUpcoming.isEmpty()) item { EmptyNote(stringResource(R.string.st_no_history)) }
                items(myUpcoming, key = { "m" + it.id }) { r -> RegistrationRow(r) { onCard(r.id) } }
                item { SectionTitle(stringResource(R.string.st_notifications), Icons.Outlined.NotificationsNone) }
                if (d.notifications.isEmpty()) item { EmptyNote(stringResource(R.string.st_no_notifications)) }
                items(d.notifications.take(5), key = { "n" + it.id }) { n ->
                    StatsCard {
                        Text(loc(n.titleAr, n.titleFr), style = MaterialTheme.typography.titleSmall, color = StatsColors.Text)
                        Text(loc(n.bodyAr, n.bodyFr), style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary)
                        Text(formatDateTime(n.createdAt), style = MaterialTheme.typography.labelSmall, color = StatsColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RegistrationRow(r: Registration, onClick: () -> Unit) {
    StatsCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.meeting?.title.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = StatsColors.Text)
            StatusBadge(r.status)
        }
        r.meeting?.let { Text(formatDateTime(it.startsAt), style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary) }
        Text("${stringResource(R.string.st_reg_no)}: #${r.regNo}", style = MaterialTheme.typography.labelMedium, color = StatsColors.Primary)
    }
}

// ───────────── Census registration ─────────────

@Composable
fun CensusScreen(meetingId: String?, onDone: (Registration) -> Unit, vm: CensusVm = hiltViewModel()) {
    LaunchedEffect(meetingId) { vm.load(meetingId) }
    val meetings by vm.meetings.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.st_census_title)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            loadError?.let { ErrorBox(it) { vm.load(meetingId) } }
            CensusForm(vm.form, vm.geo, meetings ?: emptyList())
            if (vm.form.showErrors && vm.form.errors(vm.geo).isNotEmpty()) {
                Text(stringResource(R.string.st_form_fix_errors), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { ErrorBox(it) }
            PrimaryButton(stringResource(R.string.st_register_btn), { vm.submit(onDone) }, enabled = !meetings.isNullOrEmpty(), loading = busy)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────── Participation card ─────────────

@Composable
fun CardScreen(id: String, back: () -> Unit, vm: CardVm = hiltViewModel()) {
    vm.id = id
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.st_card_title), back) }) { pad ->
        when (val s = state) {
            UiState.Loading -> Loading(Modifier.padding(pad))
            is UiState.Error -> Box(Modifier.padding(pad).padding(Pad)) { ErrorBox(s.error) { vm.reload() } }
            is UiState.Success -> {
                val r = s.data
                Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CardContent(r)
                    actionError?.let { ErrorBox(it) }
                    if (r.status == "registered" && r.meeting?.status == "open") {
                        OutlinedButton(onClick = { confirmCancel = true }, enabled = !busy) { Text(stringResource(R.string.st_cancel_registration)) }
                    }
                }
                if (confirmCancel) AlertDialog(
                    onDismissRequest = { confirmCancel = false },
                    text = { Text(stringResource(R.string.st_cancel_registration_confirm)) },
                    confirmButton = { TextButton(onClick = { confirmCancel = false; vm.cancel() }) { Text(stringResource(CoreR.string.confirm)) } },
                    dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(CoreR.string.cancel)) } },
                )
            }
        }
    }
}

/** The participation card itself (name, meeting, number, status and the QR code while waiting). */
@Composable
fun CardContent(r: Registration) {
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(StatsColors.PrimaryDeep, StatsColors.Surface)), RoundedCornerShape(28.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogoImage(Modifier.size(44.dp))
            Text(stringResource(R.string.st_card_title), style = MaterialTheme.typography.titleMedium, color = StatsColors.TextSecondary)
        }
        Text("${r.firstName} ${r.lastName}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = StatsColors.Text, textAlign = TextAlign.Center)
        Text(r.meeting?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = StatsColors.Primary, textAlign = TextAlign.Center)
        r.meeting?.let { Text(formatDateTime(it.startsAt) + " · " + it.location, style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary, textAlign = TextAlign.Center) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${stringResource(R.string.st_reg_no)} #${r.regNo}", style = MaterialTheme.typography.titleSmall, color = StatsColors.Text)
            StatusBadge(r.status)
        }
        AnimatedVisibility(r.status == "registered" && r.qr != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.qr?.let { QrImage(it, stringResource(R.string.st_card_title)) }
                Text(stringResource(R.string.st_card_show_qr), style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary, textAlign = TextAlign.Center)
            }
        }
        if (r.status == "present" || r.status == "late") {
            Box(Modifier.size(120.dp).background(statusColor(r.status).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CheckCircle, null, tint = statusColor(r.status), modifier = Modifier.size(72.dp))
            }
            Text(statusLabel(r.status), style = MaterialTheme.typography.titleLarge, color = statusColor(r.status))
            r.checkedInAt?.let { Text(stringResource(R.string.st_checked_in_at, formatDateTime(it)), color = StatsColors.TextSecondary) }
        }
    }
}

// ───────────── History ─────────────

@Composable
fun HistoryScreen(onCard: (String) -> Unit, vm: HistoryVm = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.st_history)) }) { pad ->
        when (val s = state) {
            UiState.Loading -> Loading(Modifier.padding(pad))
            is UiState.Error -> Box(Modifier.padding(pad).padding(Pad)) { ErrorBox(s.error) { vm.reload() } }
            is UiState.Success -> LazyColumn(Modifier.padding(pad), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.data.isEmpty()) item { EmptyNote(stringResource(R.string.st_no_history)) }
                items(s.data, key = { it.id }) { r ->
                    StatsCard(onClick = { onCard(r.id) }) {
                        Text(r.meeting?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = StatsColors.Text)
                        r.meeting?.let { Text("${stringResource(R.string.st_date)}: ${formatDateTime(it.startsAt)}", style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary) }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column {
                                Text(stringResource(R.string.st_registration_status), style = MaterialTheme.typography.labelSmall, color = StatsColors.TextSecondary)
                                Text(statusLabel(if (r.status == "cancelled") "cancelled" else "registered"), style = MaterialTheme.typography.bodyMedium, color = StatsColors.Text)
                            }
                            Column {
                                Text(stringResource(R.string.st_attendance_status), style = MaterialTheme.typography.labelSmall, color = StatsColors.TextSecondary)
                                if (r.status in listOf("present", "late", "absent")) StatusBadge(r.status) else Text("—", color = StatsColors.TextSecondary)
                            }
                        }
                        r.checkedInAt?.let { Text("${stringResource(R.string.st_confirmed_at)}: ${formatDateTime(it)}", style = MaterialTheme.typography.bodySmall, color = StatsColors.Primary) }
                    }
                }
            }
        }
    }
}

// ───────────── Profile ─────────────

@Composable
fun ProfileScreen(onLogout: () -> Unit, vm: ProfileVm = hiltViewModel()) {
    val me by vm.me.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.st_profile)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatsCard {
                SectionTitle(stringResource(R.string.st_account_section))
                me?.let {
                    Text(it.fullName, style = MaterialTheme.typography.titleMedium, color = StatsColors.Text)
                    Text(it.email, style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary)
                    Text(it.phone, style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary)
                    Text(roleLabel(it.role), style = MaterialTheme.typography.labelMedium, color = StatsColors.Primary)
                }
                Text(stringResource(CoreR.string.language), style = MaterialTheme.typography.labelLarge, color = StatsColors.TextSecondary)
                ChoiceChips(listOf("ar", "fr"), AppLocale.current(), { stringResource(if (it == "ar") CoreR.string.lang_ar else CoreR.string.lang_fr) }, { AppLocale.set(it) })
            }
            Text(stringResource(R.string.st_profile_hint), style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary)
            CensusForm(vm.form, vm.geo, meetings = null)
            error?.let { ErrorBox(it) }
            if (saved) Text(stringResource(R.string.st_saved), color = StatsColors.Primary)
            PrimaryButton(stringResource(R.string.st_save), { vm.save() }, loading = busy)
            OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(CoreR.string.logout)) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        text = { Text(stringResource(R.string.st_logout_confirm)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text(stringResource(CoreR.string.logout)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(CoreR.string.cancel)) } },
    )
}
