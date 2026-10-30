package dz.association.stats.admin

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.core.auth.SessionManager
import dz.association.app.core.auth.TokenStore
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.app.ui.components.LogoImage
import dz.association.app.ui.components.PrimaryButton
import dz.association.stats.R
import dz.association.stats.data.ActivationRequest
import dz.association.stats.data.StatsApi
import dz.association.stats.ui.ErrorBox
import dz.association.stats.ui.StatsColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The census admin app has no e-mail / password: it sends an activation request, an approver accepts it
 * in the Telegram bot (choosing officer or manager), and the device receives a long-lived session that
 * can be revoked at any time. Only this app holds the request's secret.
 */
@Singleton
class StatsActivation @Inject constructor(private val api: StatsApi, private val session: SessionManager, private val store: TokenStore) {
    private val device get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)

    /** A pending request survives restarts (kept encrypted with the session). */
    val pending: Boolean get() = store.read(K_ID) != null

    suspend fun request(name: String): Result<Unit> {
        val r = apiCall { api.requestActivation(ActivationRequest(name.trim(), device)) }
        if (r is Result.Ok) store.write(mapOf(K_ID to r.value.requestId, K_SECRET to r.value.secret))
        return when (r) { is Result.Ok -> Result.Ok(Unit); is Result.Err -> r }
    }

    /** "pending", "approved" (the device is now signed in), "rejected", "expired", "claimed" or "none". */
    suspend fun check(): Result<String> {
        val id = store.read(K_ID) ?: return Result.Ok("none")
        val secret = store.read(K_SECRET) ?: return Result.Ok("none")
        val r = apiCall { api.pollActivation(id, secret) }
        if (r is Result.Err) {
            if ((r.error as? AppError.Api)?.status == 404) cancel()
            return r
        }
        val st = (r as Result.Ok).value
        val user = st.user
        val tokens = st.tokens
        if (st.status == "approved" && user != null && tokens != null) {
            cancel()
            session.saveTokens(tokens)
            session.saveUser(user)
        } else if (st.status != "pending") cancel()
        return Result.Ok(st.status)
    }

    fun cancel() = store.write(mapOf(K_ID to null, K_SECRET to null))

    private companion object {
        const val K_ID = "stats_activation_id"
        const val K_SECRET = "stats_activation_secret"
    }
}

@HiltViewModel
class ActivationVm @Inject constructor(private val activation: StatsActivation) : ViewModel() {
    val waiting = MutableStateFlow(activation.pending)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    /** R.string id of the last outcome (rejected / expired). */
    val outcome = MutableStateFlow<Int?>(null)

    init { if (waiting.value) poll() }

    fun send(name: String) = viewModelScope.launch {
        busy.value = true; error.value = null; outcome.value = null
        when (val r = activation.request(name)) {
            is Result.Ok -> { waiting.value = true; poll() }
            is Result.Err -> error.value = r.error
        }
        busy.value = false
    }

    private fun poll() = viewModelScope.launch {
        while (isActive && waiting.value) {
            when (val r = activation.check()) {
                is Result.Ok -> when (r.value) {
                    "pending" -> Unit
                    "approved" -> waiting.value = false // the session appears and the app opens
                    "rejected" -> { waiting.value = false; outcome.value = R.string.st_request_rejected }
                    else -> { waiting.value = false; outcome.value = R.string.st_request_expired }
                }
                is Result.Err -> Unit // offline: keep waiting
            }
            delay(4_000)
        }
    }

    fun cancel() { activation.cancel(); waiting.value = false }
}

@Composable
fun ActivationScreen(vm: ActivationVm = hiltViewModel()) {
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogoImage(Modifier.height(110.dp))
        Text(stringResource(R.string.st_activation_title), style = MaterialTheme.typography.headlineSmall, color = StatsColors.Text, textAlign = TextAlign.Center)
        if (waiting) {
            CircularProgressIndicator(color = StatsColors.Primary)
            Text(stringResource(R.string.st_waiting_approval), color = StatsColors.TextSecondary, textAlign = TextAlign.Center)
            OutlinedButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.st_cancel_request)) }
        } else {
            Text(stringResource(R.string.st_activation_body), color = StatsColors.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            outcome?.let { Text(stringResource(it), color = StatsColors.Danger, textAlign = TextAlign.Center) }
            OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.st_your_name)) }, singleLine = true,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            error?.let { ErrorBox(it) }
            PrimaryButton(stringResource(R.string.st_send_request), { vm.send(name) }, enabled = name.trim().length >= 2, loading = busy)
        }
    }
}
