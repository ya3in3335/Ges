package dz.association.stats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.core.R as CoreR
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.LogoImage
import dz.association.app.ui.components.NetworkDetail
import dz.association.app.ui.components.PasswordField
import dz.association.app.ui.components.PrimaryButton
import dz.association.stats.data.StatsAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Same client-side checks as the association's members' app (the server re-validates everything). */
object AuthValidation {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    fun normalizePhone(input: String): String? {
        val digits = input.filter { it.isDigit() || it == '+' }.removePrefix("+").removePrefix("00")
        val national = when {
            digits.startsWith("213") -> digits.removePrefix("213").removePrefix("0")
            digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
        return if (Regex("^[567]\\d{8}$").matches(national)) "+213$national" else null
    }
    fun emailValid(e: String) = EMAIL.matches(e.trim())
    fun passwordValid(p: String) = p.length in 8..128 && p.any { it.isLetter() } && p.any { it.isDigit() }
    fun nameValid(n: String) = n.trim().length in 3..100
}

@HiltViewModel
class StatsAuthViewModel @Inject constructor(private val account: StatsAccount) : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    val info = MutableStateFlow<Int?>(null)

    private fun run(block: suspend () -> Result<*>, onOk: () -> Unit) = viewModelScope.launch {
        busy.value = true; error.value = null
        when (val r = block()) { is Result.Ok -> onOk(); is Result.Err -> error.value = r.error }
        busy.value = false
    }

    fun login(id: String, pw: String, onOk: () -> Unit) = run({ account.login(id, pw) }, onOk)
    fun register(n: String, e: String, p: String, pw: String, c: String, onOk: () -> Unit) = run({ account.register(n, e, p, pw, c) }, onOk)
    fun forgot(id: String, onOk: () -> Unit) = run({ account.forgot(id) }) { info.value = CoreR.string.reset_sent; onOk() }
    fun reset(id: String, code: String, pw: String) = run({ account.reset(id, code, pw) }) { info.value = CoreR.string.reset_done }
}

// Phone numbers and e-mails are always written left-to-right, even inside the RTL layout.
private val ltr: androidx.compose.ui.text.TextStyle
    @Composable get() = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

/** The association's sign-in: the same identifier (e-mail or phone) and password as in its apps. */
@Composable
fun StatsLoginScreen(title: String, subtitle: String, onRegister: (() -> Unit)?, onForgot: () -> Unit, onDone: () -> Unit, vm: StatsAuthViewModel = hiltViewModel()) {
    var id by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LogoImage(Modifier.height(120.dp).padding(top = 12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = StatsColors.Text, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = StatsColors.TextSecondary, textAlign = TextAlign.Center)
            OutlinedTextField(id, { id = it }, label = { Text(stringResource(CoreR.string.identifier)) }, singleLine = true, textStyle = ltr,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("login_identifier"))
            PasswordField(pw, { pw = it }, stringResource(CoreR.string.password), Modifier.testTag("login_password"))
            error?.let { Text(statsError(it), color = MaterialTheme.colorScheme.error); NetworkDetail(it) }
            PrimaryButton(stringResource(CoreR.string.login), { vm.login(id, pw, onDone) }, enabled = id.isNotBlank() && pw.isNotBlank(), loading = busy)
            TextButton(onClick = onForgot) { Text(stringResource(CoreR.string.forgot_password)) }
            if (onRegister != null) TextButton(onClick = onRegister) { Text(stringResource(CoreR.string.no_account)) }
        }
    }
}

/** Creates an association account (the same registration as the members' app). */
@Composable
fun StatsRegisterScreen(back: () -> Unit, onRegistered: () -> Unit, vm: StatsAuthViewModel = hiltViewModel()) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val nameErr = tried && !AuthValidation.nameValid(name)
    val emailErr = tried && !AuthValidation.emailValid(email)
    val phoneErr = tried && AuthValidation.normalizePhone(phone) == null
    val pwErr = tried && !AuthValidation.passwordValid(pw)
    val confirmErr = tried && confirm != pw
    Scaffold(topBar = { AppTopBar(stringResource(CoreR.string.register), back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(CoreR.string.full_name)) }, singleLine = true, isError = nameErr,
                supportingText = if (nameErr) ({ Text(stringResource(CoreR.string.err_invalid_name)) }) else null, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text(stringResource(CoreR.string.email)) }, singleLine = true, isError = emailErr, textStyle = ltr,
                supportingText = if (emailErr) ({ Text(stringResource(CoreR.string.err_invalid_email)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(CoreR.string.phone)) }, singleLine = true, isError = phoneErr, textStyle = ltr,
                supportingText = { Text(stringResource(if (phoneErr) CoreR.string.err_invalid_phone else CoreR.string.phone_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            PasswordField(pw, { pw = it }, stringResource(CoreR.string.password), isError = pwErr, supporting = stringResource(if (pwErr) CoreR.string.err_password_weak else CoreR.string.password_rules))
            PasswordField(confirm, { confirm = it }, stringResource(CoreR.string.confirm_password), isError = confirmErr, supporting = if (confirmErr) stringResource(CoreR.string.err_password_mismatch) else null)
            error?.let { Text(statsError(it), color = MaterialTheme.colorScheme.error); NetworkDetail(it) }
            PrimaryButton(stringResource(CoreR.string.register), {
                tried = true
                if (AuthValidation.nameValid(name) && AuthValidation.emailValid(email) && AuthValidation.normalizePhone(phone) != null &&
                    AuthValidation.passwordValid(pw) && confirm == pw
                ) vm.register(name, email, phone, pw, confirm, onRegistered)
            }, loading = busy)
        }
    }
}

/** Password reset by code, as in the association's apps. */
@Composable
fun StatsForgotScreen(back: () -> Unit, vm: StatsAuthViewModel = hiltViewModel()) {
    var id by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(CoreR.string.reset_title), back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(CoreR.string.reset_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(id, { id = it }, label = { Text(stringResource(CoreR.string.identifier)) }, singleLine = true, textStyle = ltr, modifier = Modifier.fillMaxWidth())
            if (!sent) PrimaryButton(stringResource(CoreR.string.reset_send), { vm.forgot(id) { sent = true } }, enabled = id.isNotBlank(), loading = busy)
            else {
                OutlinedTextField(code, { if (it.length <= 6) code = it.filter(Char::isDigit) }, label = { Text(stringResource(CoreR.string.reset_code)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                PasswordField(pw, { pw = it }, stringResource(CoreR.string.reset_new_password), supporting = stringResource(CoreR.string.password_rules))
                PrimaryButton(stringResource(CoreR.string.confirm), { vm.reset(id, code, pw) }, enabled = code.length == 6 && AuthValidation.passwordValid(pw), loading = busy)
            }
            info?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(statsError(it), color = MaterialTheme.colorScheme.error); NetworkDetail(it) }
        }
    }
}
