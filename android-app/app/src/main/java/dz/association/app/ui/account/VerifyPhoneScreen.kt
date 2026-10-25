package dz.association.app.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.core.network.VerificationStart
import dz.association.app.data.AuthRepository
import dz.association.app.data.ParticipationRepository
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.errorMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI states of the Telegram verification flow. Security decisions are made by the backend only. */
sealed interface VerifyUi {
    data object Idle : VerifyUi
    data object Starting : VerifyUi
    data class Waiting(val session: VerificationStart, val failureReason: String? = null, val attemptsLeft: Int = 3) : VerifyUi
    data object Verified : VerifyUi
    data class Failed(val reason: String?) : VerifyUi
    data class Error(val error: AppError) : VerifyUi
}

/** Maps a server status response to a UI state (unit-tested). */
fun mapVerificationStatus(current: VerifyUi.Waiting, status: String, reason: String?, attemptsLeft: Int): VerifyUi = when (status) {
    "verified" -> VerifyUi.Verified
    "failed", "expired", "cancelled" -> VerifyUi.Failed(if (status == "failed") reason else status)
    else -> current.copy(failureReason = reason, attemptsLeft = attemptsLeft)
}

@HiltViewModel
class VerifyPhoneViewModel @Inject constructor(private val repo: ParticipationRepository, private val auth: AuthRepository) : ViewModel() {
    val state = MutableStateFlow<VerifyUi>(VerifyUi.Idle)
    val user = auth.user

    init {
        viewModelScope.launch { auth.refreshMe() }
    }

    fun start(onLink: (VerificationStart) -> Unit) = viewModelScope.launch {
        state.value = VerifyUi.Starting
        state.value = when (val r = repo.startTelegramVerification()) {
            is Result.Ok -> VerifyUi.Waiting(r.value).also { onLink(r.value) }
            is Result.Err -> if ((r.error as? AppError.Api)?.code == "already_verified") { auth.refreshMe(); VerifyUi.Verified } else VerifyUi.Error(r.error)
        }
    }

    /** Polls the server while the screen is visible (every 3 s, only until the session expires). */
    suspend fun pollWhileWaiting() {
        while (true) {
            val w = state.value as? VerifyUi.Waiting ?: return
            if (System.currentTimeMillis() > w.session.expiresAt + 5_000) { state.value = VerifyUi.Failed("expired"); return }
            when (val r = repo.verificationStatus(w.session.verificationId)) {
                is Result.Ok -> {
                    val next = mapVerificationStatus(w, r.value.status, r.value.failureReason, r.value.attemptsLeft)
                    if (state.value is VerifyUi.Waiting) state.value = next
                    if (next is VerifyUi.Verified) { auth.refreshMe(); return }
                }
                is Result.Err -> Unit // transient; keep polling
            }
            delay(3_000)
        }
    }
}

fun openTelegram(ctx: Context, s: VerificationStart, noAppMessage: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.appLink)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, noAppMessage, Toast.LENGTH_LONG).show()
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.deepLink)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
fun failureText(reason: String?): String = stringResource(
    when (reason) {
        "phone_mismatch" -> R.string.verify_mismatch
        "expired", "cancelled" -> R.string.verify_expired
        "foreign_contact", "forwarded_contact" -> R.string.verify_foreign
        "telegram_already_linked" -> R.string.verify_linked_elsewhere
        else -> R.string.verify_failed
    },
)

@Composable
fun VerifyPhoneScreen(back: () -> Unit, vm: VerifyPhoneViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val noApp = stringResource(R.string.verify_no_telegram)

    // Resumes polling whenever the user comes back from Telegram.
    LaunchedEffect(state is VerifyUi.Waiting) {
        if (state is VerifyUi.Waiting) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.pollWhileWaiting() }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.verify_phone_telegram), back) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (user?.phoneVerified == true || state is VerifyUi.Verified) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.verify_success), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.testTag("verify_success"))
                PrimaryButton(stringResource(R.string.verify_continue), back)
                return@Column
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
                Icon(Icons.Outlined.Send, null, Modifier.padding(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.verify_intro), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            user?.let { Text(it.phone, style = ltr.merge(MaterialTheme.typography.headlineSmall)) }
            Text(stringResource(R.string.verify_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

            when (val s = state) {
                VerifyUi.Idle, is VerifyUi.Error, is VerifyUi.Failed -> {
                    if (s is VerifyUi.Error) Text(errorMessage(s.error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    if (s is VerifyUi.Failed) Text(failureText(s.reason), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    PrimaryButton(stringResource(R.string.verify_phone_telegram), { vm.start { openTelegram(ctx, it, noApp) } }, Modifier.testTag("verify_start"))
                }
                VerifyUi.Starting -> CircularProgressIndicator()
                is VerifyUi.Waiting -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.verify_waiting), style = MaterialTheme.typography.titleSmall)
                    s.failureReason?.let {
                        Text(failureText(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.verify_attempts_left, s.attemptsLeft), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { openTelegram(ctx, s.session, noApp) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.verify_open_again)) }
                }
                VerifyUi.Verified -> Unit
            }
        }
    }
}
