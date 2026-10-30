package dz.association.stats.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wc
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dz.association.app.core.R as CoreR
import dz.association.app.core.util.AppLocale
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.UiState
import dz.association.stats.R
import dz.association.stats.data.Meeting
import dz.association.stats.data.Stats
import dz.association.stats.ui.BarChart
import dz.association.stats.ui.ChartItem
import dz.association.stats.ui.DonutChart
import dz.association.stats.ui.Dropdown
import dz.association.stats.ui.EmptyNote
import dz.association.stats.ui.ErrorBox
import dz.association.stats.ui.Loading
import dz.association.stats.ui.RateRing
import dz.association.stats.ui.SectionTitle
import dz.association.stats.ui.StatTile
import dz.association.stats.ui.StatsCard
import dz.association.stats.ui.StatsColors
import dz.association.stats.ui.genderLabel
import dz.association.stats.ui.maritalLabel
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(onStaff: () -> Unit, onAudit: () -> Unit, onUsers: () -> Unit, onLogout: () -> Unit, onMeeting: (String) -> Unit, vm: DashboardVm = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.meetingId.collectAsStateWithLifecycle()
    val manager = LocalLevel.current == "manager"
    var menu by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    if (confirmDisconnect) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDisconnect = false }, text = { Text(stringResource(R.string.st_disconnect_confirm)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDisconnect = false; onLogout() }) { Text(stringResource(R.string.st_disconnect)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(CoreR.string.cancel)) } },
    )
    Scaffold(topBar = {
        AppTopBar(stringResource(R.string.st_dashboard), actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Groups, stringResource(R.string.st_more)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.st_staff)) }, leadingIcon = { Icon(Icons.Outlined.Groups, null) }, onClick = { menu = false; onStaff() })
                if (manager) DropdownMenuItem(text = { Text(stringResource(R.string.st_users_db)) }, leadingIcon = { Icon(Icons.Outlined.Storage, null) }, onClick = { menu = false; onUsers() })
                if (manager) DropdownMenuItem(text = { Text(stringResource(R.string.st_audit)) }, leadingIcon = { Icon(Icons.Outlined.History, null) }, onClick = { menu = false; onAudit() })
                DropdownMenuItem(text = { Text(if (AppLocale.isFrench) "العربية" else "Français") }, onClick = { menu = false; AppLocale.set(if (AppLocale.isFrench) "ar" else "fr") })
                DropdownMenuItem(text = { Text(stringResource(R.string.st_disconnect)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) }, onClick = { menu = false; confirmDisconnect = true })
            }
        })
    }) { pad ->
        when (val s = state) {
            UiState.Loading -> Loading(Modifier.padding(pad))
            is UiState.Error -> Box(Modifier.padding(pad).padding(16.dp)) { ErrorBox(s.error) { vm.reload() } }
            is UiState.Success -> {
                val meetings = s.data.meetings
                LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        // "" stands for all meetings.
                        val all = stringResource(R.string.st_all_meetings)
                        Dropdown(
                            stringResource(R.string.st_meeting), listOf("") + meetings.map { it.id }, selected ?: "",
                            { id -> meetings.firstOrNull { it.id == id }?.let { m -> "${m.title} — ${formatDateTime(m.startsAt)}" } ?: all },
                            { vm.select(it.ifEmpty { null }) },
                        )
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Sync, null, tint = StatsColors.Primary, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.st_live_updates) + " · " + formatDateTime(s.data.generatedAt), style = MaterialTheme.typography.labelSmall, color = StatsColors.TextSecondary)
                        }
                    }
                    statsItems(s.data.stats)
                    if (selected == null && meetings.isNotEmpty()) {
                        item {
                            StatsCard {
                                SectionTitle(stringResource(R.string.st_by_meeting), Icons.Outlined.Event)
                                BarChart(meetings.take(12).map { ChartItem(it.title, it.counts?.total ?: 0, it.counts?.attended) }, secondaryLabel = stringResource(R.string.st_attended))
                            }
                        }
                        items(meetings.take(5), key = { it.id }) { m -> MeetingRow(m) { onMeeting(m.id) } }
                    }
                }
            }
        }
    }
}

/** Tiles and charts for a set of statistics (whole census or one meeting). */
fun androidx.compose.foundation.lazy.LazyListScope.statsItems(st: Stats) {
    val t = st.totals
    item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.st_total_registered), "${t.registrations}", Modifier.weight(1f), StatsColors.Info)
                StatTile(stringResource(R.string.st_attended), "${t.attended}", Modifier.weight(1f), StatsColors.Primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.st_absent_count), "${t.absent}", Modifier.weight(1f), StatsColors.Danger)
                StatTile(stringResource(R.string.st_late_count), "${t.late}", Modifier.weight(1f), StatsColors.Warning)
                StatTile(stringResource(R.string.st_pending_count), "${t.registered}", Modifier.weight(1f), StatsColors.TextSecondary)
            }
        }
    }
    item {
        StatsCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RateRing(st.attendanceRate, stringResource(R.string.st_attendance_rate))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(stringResource(R.string.st_gender_dist), Icons.Outlined.Wc)
                    val g = st.gender
                    if (g.male + g.female + g.unknown == 0) EmptyNote(stringResource(R.string.st_no_data))
                    else DonutChart(
                        listOfNotNull(
                            ChartItem(genderLabel("male"), g.male, color = StatsColors.Info),
                            ChartItem(genderLabel("female"), g.female, color = androidx.compose.ui.graphics.Color(0xFFE78AC3)),
                            if (g.unknown > 0) ChartItem(genderLabel(null), g.unknown, color = StatsColors.TextSecondary) else null,
                        ),
                        size = 96.dp,
                    )
                }
            }
        }
    }
    item {
        StatsCard {
            SectionTitle(stringResource(R.string.st_by_wilaya), Icons.Outlined.Map)
            if (st.byWilaya.isEmpty()) EmptyNote(stringResource(R.string.st_no_data))
            BarChart(st.byWilaya.take(15).map { ChartItem("${it.code.toString().padStart(2, '0')} ${if (AppLocale.isFrench) it.fr ?: it.ar.orEmpty() else it.ar.orEmpty()}", it.count, it.attended) }, secondaryLabel = stringResource(R.string.st_attended))
        }
    }
    item {
        StatsCard {
            SectionTitle(stringResource(R.string.st_by_commune), Icons.Outlined.Place)
            if (st.byCommune.isEmpty()) EmptyNote(stringResource(R.string.st_no_data))
            BarChart(st.byCommune.take(12).map { ChartItem((if (AppLocale.isFrench) it.fr ?: it.ar else it.ar).orEmpty(), it.count, it.attended) }, secondaryLabel = stringResource(R.string.st_attended))
        }
    }
    item {
        StatsCard {
            SectionTitle(stringResource(R.string.st_age_groups), Icons.Outlined.Cake)
            if (st.ageGroups.all { it.count == 0 }) EmptyNote(stringResource(R.string.st_no_data))
            else BarChart(st.ageGroups.map { ChartItem(ltrIsolate(it.key), it.count) })
        }
    }
    item {
        StatsCard {
            SectionTitle(stringResource(R.string.st_marital_dist), Icons.Outlined.FamilyRestroom)
            if (st.maritalStatus.all { it.count == 0 }) EmptyNote(stringResource(R.string.st_no_data))
            else DonutChart(st.maritalStatus.map { ChartItem(maritalLabel(it.key), it.count) }, size = 120.dp)
        }
    }
}

/** Keeps "61+" and "18-25" in reading order inside Arabic text. */
fun ltrIsolate(s: String) = "\u2066$s\u2069"

@Composable
fun MeetingRow(m: Meeting, onClick: () -> Unit) {
    StatsCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = StatsColors.Text)
            MeetingStatusPill(m.status)
        }
        Text("${formatDateTime(m.startsAt)} · ${m.location}", style = MaterialTheme.typography.bodySmall, color = StatsColors.TextSecondary)
        m.counts?.let { c ->
            dz.association.stats.ui.StackedBar(listOf(c.present to StatsColors.Primary, c.late to StatsColors.Warning, c.absent to StatsColors.Danger, c.registered to StatsColors.Info))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                dz.association.stats.ui.LegendDot(StatsColors.Info, "${stringResource(R.string.st_total_registered)} ${c.total}")
                dz.association.stats.ui.LegendDot(StatsColors.Primary, "${stringResource(R.string.st_attended)} ${c.attended}")
                if (c.absent > 0) dz.association.stats.ui.LegendDot(StatsColors.Danger, "${stringResource(R.string.st_absent_count)} ${c.absent}")
            }
        }
    }
}

@Composable
fun MeetingStatusPill(status: String) {
    val c = when (status) { "closed" -> StatsColors.TextSecondary; "cancelled" -> StatsColors.Danger; else -> StatsColors.Primary }
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50), color = c.copy(alpha = 0.15f)) {
        Text(dz.association.stats.ui.meetingStatusLabel(status), color = c, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** The final (or current) report of one meeting, with a PDF export. */
@Composable
fun ReportScreen(meetingId: String, back: () -> Unit, vm: DashboardVm = hiltViewModel()) {
    androidx.compose.runtime.LaunchedEffect(meetingId) { if (vm.meetingId.value != meetingId) vm.select(meetingId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val labels = reportLabels()
    Scaffold(topBar = {
        AppTopBar(stringResource(R.string.st_final_report), back, actions = {
            val d = (state as? UiState.Success)?.data
            if (d != null && d.stats.meetingId == meetingId) IconButton(onClick = {
                scope.launch {
                    val m = d.meetings.firstOrNull { it.id == meetingId }
                    val table = reportTable(m, d.stats, labels)
                    Exporter.share(context, Exporter.pdf(context, table), "report-${m?.startsAt ?: 0}.pdf", "application/pdf")
                }
            }) { Icon(Icons.Outlined.PictureAsPdf, stringResource(R.string.st_export_pdf)) }
        })
    }) { pad ->
        when (val s = state) {
            UiState.Loading -> Loading(Modifier.padding(pad))
            is UiState.Error -> Box(Modifier.padding(pad).padding(16.dp)) { ErrorBox(s.error) { vm.reload() } }
            is UiState.Success -> if (s.data.stats.meetingId != meetingId) Loading(Modifier.padding(pad)) else {
                val m = s.data.meetings.firstOrNull { it.id == meetingId }
                LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    m?.let { item { MeetingRow(it) {} } }
                    statsItems(s.data.stats)
                }
            }
        }
    }
}

data class ReportLabels(val title: String, val count: String, val percent: String, val totals: List<String>, val rate: String, val wilaya: String, val commune: String, val ages: String, val marital: List<String>, val maritalTitle: String, val gender: List<String>, val genderTitle: String, val generated: String)

@Composable
fun reportLabels() = ReportLabels(
    title = stringResource(R.string.st_report_title), count = stringResource(R.string.st_count), percent = stringResource(R.string.st_percent),
    totals = listOf(R.string.st_total_registered, R.string.st_attended, R.string.st_late_count, R.string.st_absent_count, R.string.st_pending_count, R.string.st_status_cancelled).map { stringResource(it) },
    rate = stringResource(R.string.st_attendance_rate), wilaya = stringResource(R.string.st_by_wilaya), commune = stringResource(R.string.st_by_commune),
    ages = stringResource(R.string.st_age_groups), marital = listOf("single", "married", "divorced", "widowed").map { maritalLabel(it) },
    maritalTitle = stringResource(R.string.st_marital_dist), gender = listOf(genderLabel("male"), genderLabel("female"), genderLabel(null)),
    genderTitle = stringResource(R.string.st_gender_dist), generated = stringResource(R.string.st_generated_at, formatDateTime(System.currentTimeMillis())),
)

/** The report as a two-column table (section / value), so the same PDF writer serves both exports. */
fun reportTable(m: Meeting?, st: Stats, l: ReportLabels): Table {
    val pct = { n: Int, of: Int -> if (of == 0) "—" else "${(n * 100.0 / of).let { Math.round(it) }}%" }
    val t = st.totals
    val rows = mutableListOf<List<String>>()
    listOf(t.registrations, t.attended, t.late, t.absent, t.registered, t.cancelled).forEachIndexed { i, n -> rows += listOf(l.totals[i], "$n", if (i == 0) "" else pct(n, t.registrations)) }
    rows += listOf(l.rate, st.attendanceRate?.let { "${Math.round(it * 100)}%" } ?: "—", "")
    rows += listOf("— ${l.genderTitle} —", "", "")
    listOf(st.gender.male, st.gender.female, st.gender.unknown).forEachIndexed { i, n -> rows += listOf(l.gender[i], "$n", pct(n, t.registrations)) }
    rows += listOf("— ${l.ages} —", "", "")
    st.ageGroups.forEach { rows += listOf(ltrIsolate(it.key), "${it.count}", pct(it.count, t.registrations)) }
    rows += listOf("— ${l.maritalTitle} —", "", "")
    st.maritalStatus.forEachIndexed { i, k -> rows += listOf(l.marital.getOrElse(i) { k.key }, "${k.count}", pct(k.count, t.registrations)) }
    rows += listOf("— ${l.wilaya} —", "", "")
    st.byWilaya.forEach { rows += listOf("${it.code.toString().padStart(2, '0')} ${if (AppLocale.isFrench) it.fr ?: it.ar.orEmpty() else it.ar.orEmpty()}", "${it.count}", pct(it.count, t.registrations)) }
    rows += listOf("— ${l.commune} —", "", "")
    st.byCommune.forEach { rows += listOf((if (AppLocale.isFrench) it.fr ?: it.ar else it.ar).orEmpty(), "${it.count}", pct(it.count, t.registrations)) }
    val subtitle = listOfNotNull(m?.let { "${it.title} — ${formatDateTime(it.startsAt)} — ${it.location}" }, l.generated).joinToString("\n")
    return Table(l.title, subtitle, listOf("", l.count, l.percent), rows, rtl = !AppLocale.isFrench)
}

