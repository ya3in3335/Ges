package dz.association.admin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.admin.data.AdminRoles
import dz.association.admin.data.AdminSession
import dz.association.admin.ui.screens.ActivitiesScreen
import dz.association.admin.ui.screens.AuditScreen
import dz.association.admin.ui.screens.DonationsScreen
import dz.association.admin.ui.screens.HomeScreen
import dz.association.admin.ui.screens.ActivationScreen
import dz.association.admin.ui.screens.DevicesScreen
import dz.association.admin.ui.screens.MediaScreen
import dz.association.admin.ui.screens.NewsScreen
import dz.association.admin.ui.screens.NotificationsScreen
import dz.association.admin.ui.screens.PollsScreen
import dz.association.admin.ui.screens.SettingsScreen
import dz.association.admin.ui.screens.TelegramScreen
import dz.association.admin.ui.screens.UsersScreen
import dz.association.admin.ui.screens.VolunteersScreen
import javax.inject.Inject

@HiltViewModel
class RootVm @Inject constructor(session: AdminSession) : ViewModel() {
    val user = session.user
}

/** Module routes and the minimum role that may open them (the server enforces the same rules). */
enum class Module(val route: String, val minRole: String) {
    USERS("users", "ADMIN"), DEVICES("devices", "ADMIN"), TELEGRAM("telegram", "ADMIN"), NEWS("news", "EDITOR"), ACTIVITIES("activities", "EDITOR"),
    POLLS("polls", "ADMIN"), VOLUNTEERS("volunteers", "ADMIN"), MEDIA("media", "EDITOR"), DONATIONS("donations", "ADMIN"),
    NOTIFICATIONS("notifications", "EDITOR"), SETTINGS("settings", "SUPER_ADMIN"), AUDIT("audit", "ADMIN"),
}

@Composable
fun AdminNav(vm: RootVm = hiltViewModel()) {
    val user by vm.user.collectAsStateWithLifecycle()
    if (user == null || !AdminRoles.canUseApp(user?.role)) {
        ActivationScreen()
        return
    }
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val role = user!!.role
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(role, open = { nav.navigate(it.route) { launchSingleTop = true } }) }
        Module.entries.forEach { m ->
            composable(m.route) {
                if (!AdminRoles.atLeast(role, m.minRole)) { back(); return@composable }
                when (m) {
                    Module.USERS -> UsersScreen(back, role)
                    Module.DEVICES -> DevicesScreen(back, role)
                    Module.TELEGRAM -> TelegramScreen(back)
                    Module.NEWS -> NewsScreen(back, role)
                    Module.ACTIVITIES -> ActivitiesScreen(back)
                    Module.POLLS -> PollsScreen(back)
                    Module.VOLUNTEERS -> VolunteersScreen(back)
                    Module.MEDIA -> MediaScreen(back)
                    Module.DONATIONS -> DonationsScreen(back)
                    Module.NOTIFICATIONS -> NotificationsScreen(back, role)
                    Module.SETTINGS -> SettingsScreen(back)
                    Module.AUDIT -> AuditScreen(back)
                }
            }
        }
    }
}
