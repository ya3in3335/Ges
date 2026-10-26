package dz.association.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import dz.association.app.core.BuildConfig
import dz.association.app.core.R
import dz.association.app.core.network.AppError
import dz.association.app.core.util.absoluteUrl
import dz.association.app.ui.theme.pressTilt
import dz.association.app.ui.theme.glass
import dz.association.app.ui.theme.LocalGlass

/** Shared UI state for screens that load data. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T, val fromCache: Boolean = false) : UiState<T>
    data class Error(val error: AppError) : UiState<Nothing>
}

@Composable
fun errorMessage(e: AppError): String = when (e) {
    AppError.Network -> stringResource(R.string.error_network)
    AppError.Unauthorized -> stringResource(R.string.error_session_expired)
    is AppError.RateLimited -> stringResource(R.string.error_rate_limited)
    is AppError.Api -> when (e.code) {
        "invalid_credentials" -> stringResource(R.string.err_invalid_credentials)
        "account_locked" -> stringResource(R.string.err_account_locked)
        "email_taken", "account_exists" -> stringResource(R.string.err_email_taken)
        "phone_taken" -> stringResource(R.string.err_phone_taken)
        "invalid_phone" -> stringResource(R.string.err_invalid_phone)
        "password_mismatch" -> stringResource(R.string.err_password_mismatch)
        "password_too_short", "password_too_weak", "password_too_common", "password_too_long" -> stringResource(R.string.err_password_weak)
        "invalid_code" -> stringResource(R.string.err_invalid_code)
        "email_delivery_failed" -> stringResource(R.string.err_email_delivery)
        "lecture_full" -> stringResource(R.string.err_lecture_full)
        "contact_not_verified" -> stringResource(R.string.err_contact_not_verified)
        "already_voted" -> stringResource(R.string.err_already_voted)
        "poll_not_active" -> stringResource(R.string.err_poll_not_active)
        "activity_full" -> stringResource(R.string.err_activity_full)
        "registration_closed" -> stringResource(R.string.activity_closed)
        "phone_not_verified" -> stringResource(R.string.poll_need_verify)
        "not_on_member_list" -> stringResource(R.string.poll_not_member)
        "telegram_not_configured" -> stringResource(R.string.verify_not_configured)
        "validation_failed" -> stringResource(R.string.err_validation)
        else -> stringResource(R.string.error_generic)
    }
    AppError.Unknown -> stringResource(R.string.error_generic)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                // AutoMirrored: points the right way in RTL (Arabic) and LTR (French).
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = if (LocalGlass.current) Color.Transparent else MaterialTheme.colorScheme.surface),
    )
}

@Composable
fun OfflineBanner(visible: Boolean) {
    AnimatedVisibility(visible, enter = expandVertically(), exit = shrinkVertically()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.offline_banner), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.tertiary))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        if (onSeeAll != null) TextButton(onClick = onSeeAll) {
            Text(stringResource(R.string.see_all), style = MaterialTheme.typography.labelLarge)
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
    }
}

@Composable
fun StateMessage(icon: ImageVector, message: String, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun ErrorState(error: AppError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        StateMessage(if (error == AppError.Network) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline, errorMessage(error), stringResource(R.string.retry), onRetry)
        NetworkDetail(error)
    }
    AutoRetry(error, onRetry)
}

/** Small technical code under network errors (e.g. "DNS", "TIMEOUT") to help support. */
@Composable
fun NetworkDetail(error: AppError?) {
    val code = dz.association.app.core.network.NetworkDiagnostics.lastFailure
    if (error == AppError.Network && code != null) {
        Text("($code)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun EmptyState(message: String = stringResource(R.string.empty_generic), modifier: Modifier = Modifier) =
    StateMessage(Icons.Outlined.Inbox, message, modifier = modifier)

/** Shimmering placeholder block used for skeleton screens. */
@Composable
fun Skeleton(modifier: Modifier, shape: RoundedCornerShape = RoundedCornerShape(12.dp)) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a * 0.12f)))
}

@Composable
fun ListSkeleton(rows: Int = 5) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Skeleton(Modifier.size(88.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(Modifier.fillMaxWidth(0.9f).height(18.dp))
                    Skeleton(Modifier.fillMaxWidth(0.6f).height(14.dp))
                    Skeleton(Modifier.fillMaxWidth(0.4f).height(14.dp))
                }
            }
        }
    }
}

@Composable
fun FullScreenLoading() = ListSkeleton()

/** Remote image with skeleton while loading and a neutral fallback. Only https URLs are loaded. */
@Composable
fun RemoteImage(url: String?, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val resolved = absoluteUrl(url, BuildConfig.API_BASE_URL)
    if (resolved == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
        return
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(resolved).crossfade(true).build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        loading = { Skeleton(Modifier.fillMaxSize(), RoundedCornerShape(0.dp)) },
        error = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) },
    )
}

/**
 * Card used across the apps. Members' app (glass mode): frosted glass with a light-catching edge,
 * depth shadow and a 3D tilt under the finger. Supervisors' app: flat card with a hairline border.
 */
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    if (LocalGlass.current) {
        val m = modifier.then(if (onClick != null) Modifier.pressTilt() else Modifier).glass(shape)
        val colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        if (onClick != null) Card(onClick = onClick, modifier = m, shape = shape, colors = colors) { content() }
        else Card(modifier = m, shape = shape, colors = colors) { content() }
        return
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border) { content() }
    else Card(modifier = modifier, shape = shape, colors = colors, border = border) { content() }
}

@Composable
fun Pill(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, height: Dp = 52.dp) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().height(height), shape = MaterialTheme.shapes.medium) {
        if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}


/**
 * The official logo with a transparent background and its original colours and proportions.
 * In dark theme a soft warm glow is painted behind it (no plate or frame) so the maroon
 * lettering stays readable.
 */
@Composable
fun LogoImage(modifier: Modifier = Modifier, contentDescription: String? = null) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(R.drawable.logo), contentDescription,
        contentScale = ContentScale.Fit,
        modifier = if (!dark) modifier else modifier.drawBehind {
            drawRect(
                // Fully transparent at radius = half the shorter side: no visible edge or box.
                androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to Color(0xFFFFE9D8).copy(alpha = 0.42f), 0.55f to Color(0xFFFFE9D8).copy(alpha = 0.16f), 1f to Color.Transparent,
                    center = center, radius = size.minDimension * 0.5f,
                ),
            )
        },
    )
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, isError: Boolean = false, supporting: String? = null) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(R.string.show_password))
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun roleLabel(role: String) = stringResource(
    when (role) { "SUPER_ADMIN" -> R.string.role_SUPER_ADMIN; "ADMIN" -> R.string.role_ADMIN; "EDITOR" -> R.string.role_EDITOR; "VOLUNTEER" -> R.string.role_VOLUNTEER; else -> R.string.role_USER },
)
