package dz.association.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.Icon
import dz.association.app.ui.theme.glass
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dz.association.app.R
import dz.association.app.ui.about.AboutScreen
import dz.association.app.ui.account.AccountScreen
import dz.association.app.ui.account.ForgotPasswordScreen
import dz.association.app.ui.account.LoginScreen
import dz.association.app.ui.account.RegisterScreen
import dz.association.app.ui.account.SettingsScreen
import dz.association.app.ui.account.VerifyPhoneScreen
import dz.association.app.ui.activities.ActivitiesScreen
import dz.association.app.ui.activities.ActivityDetailScreen
import dz.association.app.ui.activities.MyActivitiesScreen
import dz.association.app.ui.donations.DonationsScreen
import dz.association.app.ui.gallery.GalleryScreen
import dz.association.app.ui.gallery.PhotoScreen
import dz.association.app.ui.gallery.VideoScreen
import dz.association.app.ui.home.HomeScreen
import dz.association.app.ui.intro.IntroScreen
import dz.association.app.ui.news.NewsDetailScreen
import dz.association.app.ui.news.NewsListScreen
import dz.association.app.ui.notifications.NotificationsScreen
import dz.association.app.ui.polls.PollDetailScreen
import dz.association.app.ui.polls.PollsScreen
import dz.association.app.ui.volunteer.VolunteerScreen

private data class Tab(val route: String, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.ACTIVITIES, R.string.nav_activities, Icons.Outlined.Event, Icons.Filled.Event),
    Tab(Routes.POLLS, R.string.nav_polls, Icons.Outlined.HowToVote, Icons.Filled.HowToVote),
    Tab(Routes.VOLUNTEER, R.string.nav_volunteer, Icons.Outlined.VolunteerActivism, Icons.Filled.VolunteerActivism),
    Tab(Routes.ACCOUNT, R.string.nav_account, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
)

@Composable
fun AppNavHost(
    showIntro: Boolean,
    pendingDeepLink: String?,
    onDeepLinkHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val lowEnd = dz.association.app.ui.theme.LocalLowEnd.current
    val entry by navController.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val nav: (String) -> Unit = { navController.navigate(it) { launchSingleTop = true } }
    val back: () -> Unit = { navController.popBackStack() }

    LaunchedEffect(pendingDeepLink, current) {
        val target = Routes.fromDeepLink(pendingDeepLink)
        if (target != null && current != null && current != Routes.INTRO) {
            nav(target)
            onDeepLinkHandled()
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            if (current in Routes.TABS) {
                // Floating frosted-glass navigation bar.
                androidx.compose.foundation.layout.Box(
                    Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    NavigationBar(
                        Modifier.testTag("bottom_bar").glass(androidx.compose.foundation.shape.RoundedCornerShape(28.dp), elevation = 20.dp),
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        tonalElevation = 0.dp,
                        windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                    ) {
                        tabs.forEach { t ->
                            val selected = current == t.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(t.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(if (selected) t.selectedIcon else t.icon, contentDescription = null) },
                                label = { Text(stringResource(t.label), maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = androidx.compose.ui.graphics.Color.White,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.testTag("tab_${t.route}"),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (showIntro) Routes.INTRO else Routes.HOME,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { Motion.enter(this, lowEnd) },
            exitTransition = { Motion.exit(this, lowEnd) },
            popEnterTransition = { Motion.popEnter(this, lowEnd) },
            popExitTransition = { Motion.popExit(this, lowEnd) },
        ) {
            composable(Routes.INTRO, exitTransition = { fadeOut(tween(450)) }) {
                IntroScreen(onFinished = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.INTRO) { inclusive = true } }
                })
            }
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.ACTIVITIES) { ActivitiesScreen(nav) }
            composable(Routes.POLLS) { PollsScreen(nav) }
            composable(Routes.VOLUNTEER) { VolunteerScreen(nav) }
            composable(Routes.ACCOUNT) { AccountScreen(nav) }
            composable(Routes.NEWS) { NewsListScreen(nav, back) }
            composable(Routes.NEWS_DETAIL, listOf(navArgument("id") { type = NavType.StringType })) { NewsDetailScreen(back, nav) }
            composable(Routes.ACTIVITY_DETAIL, listOf(navArgument("id") { type = NavType.StringType })) { ActivityDetailScreen(back, nav) }
            composable(Routes.POLL_DETAIL, listOf(navArgument("id") { type = NavType.StringType })) { PollDetailScreen(back, nav) }
            composable(Routes.GALLERY) { GalleryScreen(nav, back) }
            composable(Routes.VIDEO, listOf(navArgument("url") { type = NavType.StringType })) { VideoScreen(it.arguments?.getString("url").orEmpty(), back) }
            composable(Routes.PHOTO, listOf(navArgument("url") { type = NavType.StringType })) { PhotoScreen(it.arguments?.getString("url").orEmpty(), back) }
            composable(Routes.DONATIONS) { DonationsScreen(back, nav) }
            composable(Routes.ABOUT) { AboutScreen(back) }
            composable(Routes.NOTIFICATIONS) { NotificationsScreen(back, nav) }
            composable(Routes.LOGIN) { LoginScreen(back, nav, onDone = { navController.popBackStack() }) }
            composable(Routes.REGISTER) {
                RegisterScreen(back, onRegistered = {
                    navController.navigate(Routes.VERIFY) { popUpTo(Routes.REGISTER) { inclusive = true } }
                })
            }
            composable(Routes.FORGOT) { ForgotPasswordScreen(back) }
            composable(Routes.VERIFY) { VerifyPhoneScreen(back) }
            composable(Routes.SETTINGS) { SettingsScreen(back, onLoggedOut = { navController.popBackStack(Routes.ACCOUNT, false) }) }
            composable(Routes.MY_ACTIVITIES) { MyActivitiesScreen(back, nav) }
        }
    }
}
