package dz.association.app.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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

@Composable
private fun HomeHeader(user: User?, settings: Map<String, String>, nav: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoImage(
                    Modifier.height(64.dp).width(98.dp),
                    contentDescription = settings["association_name_ar"]?.let { loc(it, settings["association_name_fr"]) } ?: stringResource(R.string.app_name),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { nav(Routes.NOTIFICATIONS) }) { Icon(Icons.Outlined.Notifications, stringResource(R.string.menu_notifications)) }
            }
            Spacer(Modifier.height(10.dp))
            val name = settings["association_name_ar"]?.let { loc(it, settings["association_name_fr"]) }
            if (name != null) Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                if (user != null) stringResource(R.string.greeting_name, user.fullName.substringBefore(' ')) else greeting(),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

@Composable
private fun QuickLinks(nav: (String) -> Unit) {
    val links = listOf(
        Triple(R.string.menu_news, Icons.Outlined.Newspaper, Routes.NEWS),
        Triple(R.string.menu_gallery, Icons.Outlined.Collections, Routes.GALLERY),
        Triple(R.string.menu_donations, Icons.Outlined.Savings, Routes.DONATIONS),
        Triple(R.string.menu_about, Icons.Outlined.Info, Routes.ABOUT),
    )
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(links) { (label, icon, route) ->
            AssistChip(onClick = { nav(route) }, label = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
        }
    }
}

@Composable
internal fun HomeContent(d: HomeResponse, fromCache: Boolean, user: User?, nav: (String) -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
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
        if (d.announcements.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_announcements), onSeeAll = { nav(Routes.NEWS) }) }
            items(d.announcements, key = { "a" + it.id }) { n -> NewsRow(n) { nav(Routes.news(n.id)) } }
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
                AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = { nav(Routes.poll(p.id)) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(loc(p.titleAr, p.titleFr), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.poll_ends, formatDate(p.endsAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            CallToAction(stringResource(R.string.home_volunteer_title), stringResource(R.string.home_volunteer_body), Brand.Maroon) { nav(Routes.VOLUNTEER) }
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
            CallToAction(stringResource(R.string.home_donate_title), stringResource(R.string.home_donate_body), Color(0xFFB4561B)) { nav(Routes.DONATIONS) }
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
private fun FeaturedCard(n: HomeNews, onClick: () -> Unit) {
    AppCard(Modifier.width(300.dp), onClick = onClick) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            RemoteImage(n.imageUrl, null, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                Pill(stringResource(R.string.campaign))
                Spacer(Modifier.height(6.dp))
                Text(loc(n.titleAr, n.titleFr), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun NewsRow(n: HomeNews, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(n.imageUrl, null, Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(loc(n.titleAr, n.titleFr), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val ex = loc(n.excerptAr.orEmpty(), n.excerptFr)
                if (ex.isNotBlank()) Text(ex, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                n.publishedAt?.let { Text(formatDate(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            }
        }
    }
}

@Composable
private fun ActivityCard(a: HomeActivity, onClick: () -> Unit) {
    AppCard(Modifier.width(240.dp), onClick = onClick) {
        Column {
            Box {
                RemoteImage(a.imageUrl, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Box(Modifier.padding(10.dp)) { Pill(formatShortDate(a.startsAt), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) }
            }
            Column(Modifier.padding(12.dp)) {
                Text(loc(a.titleAr, a.titleFr), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                a.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
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
private fun CallToAction(title: String, body: String, color: Color, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), onClick = onClick) {
        Box(Modifier.background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.78f)))).padding(20.dp).fillMaxWidth()) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Skeleton(Modifier.width(100.dp).height(64.dp))
        Skeleton(Modifier.fillMaxWidth(0.6f).height(28.dp))
        Skeleton(Modifier.fillMaxWidth().height(180.dp))
        repeat(3) { Skeleton(Modifier.fillMaxWidth().height(96.dp)) }
    }
}

