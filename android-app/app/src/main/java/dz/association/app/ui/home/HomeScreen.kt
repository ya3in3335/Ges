package dz.association.app.ui.home

import android.content.Intent
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.HomeActivity
import dz.association.app.core.network.HomeMedia
import dz.association.app.core.network.HomeNews
import dz.association.app.core.network.HomeResponse
import dz.association.app.core.network.Result
import dz.association.app.core.network.User
import dz.association.app.core.util.formatDate
import dz.association.app.core.util.formatShortDate
import dz.association.app.core.util.loc
import dz.association.app.data.AuthRepository
import dz.association.app.data.ContentRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.LogoImage
import dz.association.app.ui.theme.Brand
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.OfflineBanner
import dz.association.app.ui.components.Pill
import dz.association.app.ui.components.RemoteImage
import dz.association.app.ui.components.SectionHeader
import dz.association.app.ui.components.Skeleton
import dz.association.app.ui.components.UiState
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val repo: ContentRepository, auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<HomeResponse>>(UiState.Loading)
    val state: StateFlow<UiState<HomeResponse>> = _state
    val refreshing = MutableStateFlow(false)
    val user: StateFlow<User?> = auth.user

    init { load() }

    fun load(pull: Boolean = false) = viewModelScope.launch {
        if (pull) refreshing.value = true else if (_state.value !is UiState.Success) _state.value = UiState.Loading
        _state.value = when (val r = repo.home()) {
            is Result.Ok -> UiState.Success(r.value.data, r.value.fromCache)
            is Result.Err -> if (_state.value is UiState.Success) _state.value else UiState.Error(r.error)
        }
        refreshing.value = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: (String) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.load(pull = true) }, modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            UiState.Loading -> HomeSkeleton()
            is UiState.Error -> Column(Modifier.statusBarsPadding()) { HomeHeader(user, emptyMap(), nav); ErrorState(s.error, { vm.load() }) }
            is UiState.Success -> HomeContent(s.data, s.fromCache, user, nav)
        }
    }
}

@Composable
private fun greeting(): String {
    val h = Calendar.getInstance(TimeZone.getTimeZone("Africa/Algiers")).get(Calendar.HOUR_OF_DAY)
    return stringResource(if (h in 4..13) R.string.greeting_morning else R.string.greeting_evening)
}

/** Branded header: soft warm gradient, transparent logo, greeting and notifications. */
@Composable
private fun HomeHeader(user: User?, settings: Map<String, String>, nav: (String) -> Unit) {
    val warm = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF2A1519) else Color(0xFFFFEEE3)
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(warm, MaterialTheme.colorScheme.background)))
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoImage(
                    Modifier.height(72.dp).width(110.dp),
                    contentDescription = settings["association_name_ar"]?.let { loc(it, settings["association_name_fr"]) } ?: stringResource(R.string.app_name),
                )
                Spacer(Modifier.weight(1f))
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 1.dp) {
                    IconButton(onClick = { nav(Routes.NOTIFICATIONS) }) { Icon(Icons.Outlined.Notifications, stringResource(R.string.menu_notifications)) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                user?.fullName?.substringBefore(' ') ?: (settings["association_name_ar"]?.let { loc(it, settings["association_name_fr"]) } ?: stringResource(R.string.app_name)),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Four main shortcuts as round icon tiles. */
@Composable
private fun QuickLinks(nav: (String) -> Unit) {
    val links = listOf(
        Triple(R.string.menu_news, Icons.Outlined.Newspaper, Routes.NEWS),
        Triple(R.string.menu_gallery, Icons.Outlined.Collections, Routes.GALLERY),
        Triple(R.string.menu_donations, Icons.Outlined.Savings, Routes.DONATIONS),
        Triple(R.string.menu_about, Icons.Outlined.Info, Routes.ABOUT),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        links.forEach { (label, icon, route) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { nav(route) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f), modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun HomeContent(d: HomeResponse, fromCache: Boolean, user: User?, nav: (String) -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { HomeHeader(user, d.settings, nav) }
        item { OfflineBanner(fromCache) }
        item { QuickLinks(nav) }

        if (d.featuredNews.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_featured)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.featuredNews, key = { it.id }) { n -> FeaturedCard(n) { nav(Routes.news(n.id)) } }
                }
            }
        }
        if (d.activities.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_upcoming), onSeeAll = { nav(Routes.ACTIVITIES) }) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.activities, key = { it.id }) { a -> ActivityCard(a) { nav(Routes.activity(a.id)) } }
                }
            }
        }
        if (d.activePolls.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_polls), onSeeAll = { nav(Routes.POLLS) }) }
            items(d.activePolls, key = { "p" + it.id }) { p ->
                AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), onClick = { nav(Routes.poll(p.id)) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Outlined.HowToVote)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(loc(p.titleAr, p.titleFr), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.poll_ends, formatDate(p.endsAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        if (d.announcements.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_announcements), onSeeAll = { nav(Routes.NEWS) }) }
            items(d.announcements, key = { "a" + it.id }) { n -> NewsRow(n) { nav(Routes.news(n.id)) } }
        }
        item {
            CallToAction(Icons.Outlined.VolunteerActivism, stringResource(R.string.home_volunteer_title), stringResource(R.string.home_volunteer_body), listOf(Brand.Maroon, Brand.MaroonDeep)) { nav(Routes.VOLUNTEER) }
        }
        if (d.recentNews.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_news), onSeeAll = { nav(Routes.NEWS) }) }
            items(d.recentNews, key = { "n" + it.id }) { n -> NewsRow(n) { nav(Routes.news(n.id)) } }
        }
        if (d.media.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_gallery), onSeeAll = { nav(Routes.GALLERY) }) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(d.media, key = { it.id }) { m -> MediaThumb(m) { nav(if (m.kind == "video") Routes.video(m.url) else Routes.photo(m.url)) } }
                }
            }
        }
        item {
            CallToAction(Icons.Outlined.Savings, stringResource(R.string.home_donate_title), stringResource(R.string.home_donate_body), listOf(Color(0xFFE9762B), Color(0xFFC4501F))) { nav(Routes.DONATIONS) }
        }
        val phone = d.settings["contact_phone"]
        val email = d.settings["contact_email"]
        if (phone != null || email != null) {
            item { SectionHeader(stringResource(R.string.home_contact)) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (phone != null) AssistChip(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }, label = { Text(phone) }, leadingIcon = { Icon(Icons.Outlined.Phone, null) })
                    if (email != null) AssistChip(onClick = { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))) }, label = { Text(email, maxLines = 1) }, leadingIcon = { Icon(Icons.Outlined.Email, null) })
                }
            }
        }
    }
}

@Composable
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f), modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun FeaturedCard(n: HomeNews, onClick: () -> Unit) {
    AppCard(Modifier.width(300.dp), onClick = onClick) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            RemoteImage(n.imageUrl, null, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Pill(stringResource(R.string.campaign), MaterialTheme.colorScheme.tertiary, Color.White)
                Spacer(Modifier.height(8.dp))
                Text(loc(n.titleAr, n.titleFr), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun NewsRow(n: HomeNews, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (n.imageUrl != null) {
                RemoteImage(n.imageUrl, null, Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)))
            } else {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), modifier = Modifier.size(76.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (n.category == "announcement") Icons.Outlined.Campaign else Icons.Outlined.Newspaper, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(loc(n.titleAr, n.titleFr), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val ex = loc(n.excerptAr.orEmpty(), n.excerptFr)
                if (ex.isNotBlank()) Text(ex, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                n.publishedAt?.let { Text(formatDate(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
            }
        }
    }
}

/** Activity card with a calendar-style date badge. */
@Composable
private fun ActivityCard(a: HomeActivity, onClick: () -> Unit) {
    AppCard(Modifier.width(250.dp), onClick = onClick) {
        Column {
            Box {
                if (a.imageUrl != null) RemoteImage(a.imageUrl, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)))))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 2.dp, modifier = Modifier.padding(10.dp)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val parts = formatShortDate(a.startsAt).split(' ')
                        Text(parts.firstOrNull().orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(parts.drop(1).joinToString(" "), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(loc(a.titleAr, a.titleFr), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                a.location?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumb(m: HomeMedia, onClick: () -> Unit) {
    AppCard(Modifier.size(132.dp), onClick = onClick) {
        Box(Modifier.fillMaxSize()) {
            RemoteImage(m.thumbnailUrl ?: m.url.takeIf { m.kind == "photo" }, m.titleAr, Modifier.fillMaxSize())
            if (m.kind == "video") Icon(Icons.Outlined.PlayCircle, null, Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
        }
    }
}

@Composable
private fun CallToAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, colors: List<Color>, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(colors)).clickable(onClick = onClick).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            }
            Spacer(Modifier.width(12.dp))
            Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.18f), modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) }
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Skeleton(Modifier.width(110.dp).height(72.dp))
        Skeleton(Modifier.fillMaxWidth(0.5f).height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(4) { Skeleton(Modifier.size(56.dp), RoundedCornerShape(18.dp)) } }
        Skeleton(Modifier.fillMaxWidth().height(170.dp))
        repeat(3) { Skeleton(Modifier.fillMaxWidth().height(90.dp)) }
    }
}
