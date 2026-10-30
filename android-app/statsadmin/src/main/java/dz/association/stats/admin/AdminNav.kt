package dz.association.stats.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dz.association.app.core.R as CoreR
import dz.association.app.ui.components.LogoImage
import dz.association.app.ui.components.PrimaryButton
import dz.association.stats.R
import dz.association.stats.ui.ErrorBox
import dz.association.stats.ui.Loading
import dz.association.stats.ui.StatsColors

/** "manager" or "officer": hides what the server would refuse anyway. */
val LocalLevel = staticCompositionLocalOf { "officer" }

object Routes {
    const val DASHBOARD = "dashboard"
    const val MEETINGS = "meetings"
    const val MEETING = "meeting/{id}"
    const val MEETING_EDIT = "meeting-edit?id={id}"
    const val REPORT = "report/{id}"
    const val CHECKIN = "checkin"
    const val RECORDS = "records?meeting={meeting}"
    const val RECORD = "record/{id}"
    const val STAFF = "staff"
    const val AUDIT = "audit"
    const val USERS = "users"
    fun meeting(id: String) = "meeting/$id"
    fun meetingEdit(id: String? = null) = if (id == null) "meeting-edit" else "meeting-edit?id=$id"
    fun report(id: String) = "report/$id"
    fun records(meeting: String? = null) = if (meeting == null) "records" else "records?meeting=$meeting"
    fun record(id: String) = "record/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector, val managerOnly: Boolean = false)
// Administrators do not register themselves: the census form lives in the members' app only.
private val TABS = listOf(
    Tab(Routes.DASHBOARD, R.string.st_dashboard, Icons.Outlined.Dashboard),
    Tab(Routes.MEETINGS, R.string.st_meetings, Icons.Outlined.Event),
    Tab(Routes.CHECKIN, R.string.st_checkin, Icons.Outlined.QrCodeScanner),
    Tab(Routes.RECORDS, R.string.st_records, Icons.AutoMirrored.Outlined.ListAlt),
    Tab(Routes.USERS, R.string.st_users_tab, Icons.Outlined.Storage, managerOnly = true),
)

/**
 * Device activation approved in the Telegram bot, then the server decides: managers and officers get
 * the app, anyone else sees "no access". Nothing is shown before both steps succeed.
 */
@Composable
fun AdminNav(access: AccessVm = hiltViewModel()) {
    val user by access.account.user.collectAsStateWithLifecycle()
    val level by access.level.collectAsStateWithLifecycle()
    val error by access.error.collectAsStateWithLifecycle()
    when {
        user == null -> ActivationScreen()
        level == null && error != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            ErrorBox(error!!) { access.check() }
            OutlinedButton(onClick = { access.logout() }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(CoreR.string.logout)) }
        }
        level == null -> Loading()
        level == "" -> NoAccess(onRetry = { access.check() }, onLogout = { access.logout() })
        else -> CompositionLocalProvider(LocalLevel provides level!!) { MainGraph(onLogout = { access.logout() }) }
    }
}

@Composable
private fun NoAccess(onRetry: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        LogoImage(Modifier.height(100.dp))
        Text(stringResource(R.string.st_no_access_title), style = MaterialTheme.typography.headlineSmall, color = StatsColors.Text, textAlign = TextAlign.Center)
        Text(stringResource(R.string.st_no_access_body), style = MaterialTheme.typography.bodyMedium, color = StatsColors.TextSecondary, textAlign = TextAlign.Center)
        PrimaryButton(stringResource(R.string.st_check_again), onRetry)
        OutlinedButton(onClick = onLogout) { Text(stringResource(CoreR.string.logout)) }
    }
}

@Composable
private fun MainGraph(onLogout: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val manager = LocalLevel.current == "manager"
    val tabs = TABS.filter { manager || !it.managerOnly }
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == current }) NavigationBar(containerColor = StatsColors.Surface) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(if (tab.route == Routes.RECORDS) Routes.records() else tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(stringResource(tab.label), maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = StatsColors.Primary, selectedTextColor = StatsColors.Primary, indicatorColor = StatsColors.PrimaryDeep,
                            unselectedIconColor = StatsColors.TextSecondary, unselectedTextColor = StatsColors.TextSecondary,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(pad)) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onStaff = { nav.navigate(Routes.STAFF) }, onAudit = { nav.navigate(Routes.AUDIT) }, onLogout = onLogout,
                    onMeeting = { nav.navigate(Routes.meeting(it)) },
                )
            }
            composable(Routes.MEETINGS) { MeetingsScreen(onOpen = { nav.navigate(Routes.meeting(it)) }, onNew = { nav.navigate(Routes.meetingEdit()) }) }
            composable(Routes.MEETING, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val id = it.arguments!!.getString("id")!!
                MeetingScreen(
                    id, back = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.meetingEdit(id)) },
                    onRegistrants = { nav.navigate(Routes.records(id)) }, onReport = { nav.navigate(Routes.report(id)) },
                )
            }
            composable(Routes.MEETING_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                MeetingEditScreen(it.arguments?.getString("id"), back = { nav.popBackStack() }, onSaved = { id ->
                    nav.popBackStack()
                    if (it.arguments?.getString("id") == null) nav.navigate(Routes.meeting(id))
                })
            }
            composable(Routes.REPORT, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ReportScreen(it.arguments!!.getString("id")!!, back = { nav.popBackStack() })
            }
            composable(Routes.CHECKIN) { CheckinScreen() }
            composable(Routes.RECORDS, arguments = listOf(navArgument("meeting") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                RecordsScreen(it.arguments?.getString("meeting"), onOpen = { id -> nav.navigate(Routes.record(id)) })
            }
            composable(Routes.RECORD, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                RecordScreen(it.arguments!!.getString("id")!!, back = { nav.popBackStack() })
            }
            composable(Routes.STAFF) { StaffScreen(back = { nav.popBackStack() }) }
            composable(Routes.AUDIT) { AuditScreen(back = { nav.popBackStack() }) }
            composable(Routes.USERS) { UsersScreen() }
        }
    }
}
