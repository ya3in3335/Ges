package dz.association.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dz.association.app.core.R
import dz.association.app.core.network.AppError
import kotlinx.coroutines.delay

/** Whether the device currently has a validated internet connection (provided by the activity). */
val LocalOnline = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

/**
 * Small pill at the top of the screen, like messaging apps: shown while offline, then briefly
 * confirms when the connection is back.
 */
@Composable
fun ConnectionBanner(modifier: Modifier = Modifier) {
    val online by LocalOnline.current
    var wasOffline by remember { mutableStateOf(false) }
    var showBack by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (!online) { wasOffline = true; showBack = false }
        else if (wasOffline) { wasOffline = false; showBack = true; delay(2200); showBack = false }
    }
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(!online || showBack, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            val bg = if (online) Color(0xFF2E7D4F) else Color(0xFF3A3A3C)
            Row(
                Modifier.shadow(8.dp, RoundedCornerShape(50)).background(bg, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (online) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(if (online) R.string.connection_back else R.string.connection_offline),
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Re-runs [retry] by itself, as other apps do: as soon as the connection comes back, and for a
 * network error while online, after growing pauses (3 s, 6 s, 12 s … up to 30 s).
 */
@Composable
fun AutoRetry(error: AppError?, retry: () -> Unit) {
    val online by LocalOnline.current
    val current by rememberUpdatedState(retry)
    LaunchedEffect(error, online) {
        if (!online) { retryStreak = 0; return@LaunchedEffect }
        if (error != AppError.Network) return@LaunchedEffect
        // The error screen is recreated after each attempt, so the streak lives outside composition.
        delay((3000L shl retryStreak.coerceAtMost(4)).coerceAtMost(30_000L))
        retryStreak++
        current()
    }
}

@Volatile private var retryStreak = 0

/** Reloads cached content as soon as the connection returns. */
@Composable
fun RefreshWhenBackOnline(showingCache: Boolean, refresh: () -> Unit) {
    val online by LocalOnline.current
    val current by rememberUpdatedState(refresh)
    LaunchedEffect(online, showingCache) { if (online && showingCache) current() }
}
