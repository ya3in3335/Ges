package dz.association.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.data.AuthRepository
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.errorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface EmailUi {
    data object Sending : EmailUi
    data object CodeSent : EmailUi
    /** Verified (now or earlier), or the server has no e-mail provider: the flow simply continues. */
    data object Done : EmailUi
    data class Failed(val error: AppError) : EmailUi
}

@HiltViewModel
class VerifyEmailViewModel @Inject constructor(private val repo: AuthRepository) : ViewModel() {
    val state = MutableStateFlow<EmailUi>(EmailUi.Sending)
    val busy = MutableStateFlow(false)
    val codeError = MutableStateFlow<AppError?>(null)
    val cooldown = MutableStateFlow(0)
    val email: String? get() = repo.user.value?.email
    private var timer: Job? = null

    init { send() }

    fun send() = viewModelScope.launch {
        if (cooldown.value > 0) return@launch
        state.value = EmailUi.Sending
        codeError.value = null
        state.value = when (val r = repo.sendEmailCode()) {
            is Result.Ok -> if (r.value) EmailUi.Done else EmailUi.CodeSent.also { startCooldown() }
            is Result.Err -> if ((r.error as? AppError.Api)?.code == "email_not_configured") EmailUi.Done else EmailUi.Failed(r.error)
        }
    }

    fun verify(code: String) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        codeError.value = null
        when (val r = repo.verifyEmail(code)) {
            is Result.Ok -> state.value = EmailUi.Done
            is Result.Err -> codeError.value = r.error
        }
        busy.value = false
    }

    private fun startCooldown() {
        timer?.cancel()
        timer = viewModelScope.launch { for (s in 60 downTo 1) { cooldown.value = s; delay(1000) }; cooldown.value = 0 }
    }
}

@Composable
fun VerifyEmailScreen(back: () -> Unit, onDone: () -> Unit, vm: VerifyEmailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val codeError by vm.codeError.collectAsStateWithLifecycle()
    val cooldown by vm.cooldown.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) { if (state == EmailUi.Done) onDone() }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.verify_email_title), back) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.MarkEmailRead, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            when (val s = state) {
                EmailUi.Sending, EmailUi.Done -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.verify_email_sending), textAlign = TextAlign.Center)
                }
                is EmailUi.Failed -> {
                    Text(errorMessage(s.error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    PrimaryButton(stringResource(R.string.retry), { vm.send() }, enabled = cooldown == 0)
                }
                EmailUi.CodeSent -> {
                    Text(stringResource(R.string.verify_email_sent), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    vm.email?.let { Text(it, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr), fontWeight = FontWeight.Bold) }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v ->
                            code = v.filter(Char::isDigit).take(6)
                            if (code.length == 6) vm.verify(code)
                        },
                        singleLine = true,
                        placeholder = { Text("••••••", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        textStyle = TextStyle(fontSize = 28.sp, letterSpacing = 10.sp, textAlign = TextAlign.Center, textDirection = TextDirection.Ltr),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = codeError != null,
                        modifier = Modifier.fillMaxWidth().testTag("email_code"),
                    )
                    codeError?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                    PrimaryButton(stringResource(R.string.confirm), { vm.verify(code) }, enabled = code.length == 6, loading = busy)
                    TextButton(onClick = { code = ""; vm.send() }, enabled = cooldown == 0) {
                        Text(if (cooldown > 0) stringResource(R.string.verify_email_resend_in, cooldown) else stringResource(R.string.verify_email_resend))
                    }
                    Text(stringResource(R.string.verify_email_spam_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
