package dz.association.stats.member

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dz.association.stats.R
import dz.association.stats.ui.StatsColors
import dz.association.stats.ui.StatsForgotScreen
import dz.association.stats.ui.StatsLoginScreen
import dz.association.stats.ui.StatsRegisterScreen

private object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT = "forgot"
    const val HOME = "home"
    const val CENSUS = "census?meeting={meeting}"
    const val CARD = "card/{id}"
    const val HISTORY = "history"
    const val PROFILE = "profile"
    fun census(meeting: String? = null) = if (meeting == null) "census" else "census?meeting=$meeting"
    fun card(id: String) = "card/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)
private val TABS = listOf(
    Tab(Routes.HOME, R.string.st_home, Icons.Outlined.Home),
    Tab(Routes.CENSUS, R.string.st_census_title, Icons.Outlined.HowToReg),
    Tab(Routes.HISTORY, R.string.st_history, Icons.Outlined.History),
    Tab(Routes.PROFILE, R.string.st_profile, Icons.Outlined.AccountCircle),
)

/**
 * Two separate graphs: without a valid session only the sign-in screens exist, so no protected
 * screen can be opened. When the session ends (sign-out or refused renewal) the app returns here.
 */
@Composable
fun MemberNav(session: SessionVm = hiltViewModel()) {
    val user by session.account.user.collectAsStateWithLifecycle()
    if (user == null) AuthGraph() else MainGraph(onLogout = { session.logout() })
}

@Composable
private fun AuthGraph() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            StatsLoginScreen(
                title = stringResource(R.string.st_census_title), subtitle = stringResource(R.string.st_login_subtitle_member),
                onRegister = { nav.navigate(Routes.REGISTER) }, onForgot = { nav.navigate(Routes.FORGOT) }, onDone = {},
            )
        }
        composable(Routes.REGISTER) { StatsRegisterScreen(back = { nav.popBackStack() }, onRegistered = {}) }
        composable(Routes.FORGOT) { StatsForgotScreen(back = { nav.popBackStack() }) }
    }
}

@Composable
private fun MainGraph(onLogout: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    Scaffold(
        bottomBar = {
            if (TABS.any { it.route == current }) NavigationBar(containerColor = StatsColors.Surface) {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(if (tab.route == Routes.CENSUS) Routes.census() else tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(stringResource(tab.label), maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = StatsColors.Primary, selectedTextColor = StatsColors.Primary, indicatorColor = StatsColors.PrimaryDeep,
                            unselectedIconColor = StatsColors.TextSecondary, unselectedTextColor = StatsColors.TextSecondary,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(pad)) {
            composable(Routes.HOME) {
                HomeScreen(onRegister = { nav.navigate(Routes.census(it)) }, onCard = { nav.navigate(Routes.card(it)) })
            }
            composable(Routes.CENSUS, arguments = listOf(navArgument("meeting") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                CensusScreen(it.arguments?.getString("meeting"), onDone = { reg ->
                    nav.navigate(Routes.card(reg.id)) { popUpTo(Routes.HOME) }
                })
            }
            composable(Routes.CARD, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                CardScreen(it.arguments!!.getString("id")!!, back = { nav.popBackStack() })
            }
            composable(Routes.HISTORY) { HistoryScreen(onCard = { nav.navigate(Routes.card(it)) }) }
            composable(Routes.PROFILE) { ProfileScreen(onLogout = onLogout) }
        }
    }
}
