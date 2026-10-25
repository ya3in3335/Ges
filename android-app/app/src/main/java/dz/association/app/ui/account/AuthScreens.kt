package dz.association.app.ui.account

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.core.push.PushManager
import dz.association.app.data.AuthRepository
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.errorMessage
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Client-side checks for instant feedback; the server re-validates everything. */
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
class AuthViewModel @Inject constructor(private val repo: AuthRepository, private val push: PushManager) : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    val info = MutableStateFlow<Int?>(null)

    private fun run(block: suspend () -> Result<*>, onOk: () -> Unit) = viewModelScope.launch {
        busy.value = true; error.value = null
        when (val r = block()) { is Result.Ok -> onOk(); is Result.Err -> error.value = r.error }
        busy.value = false
    }

    fun login(id: String, pw: String, onOk: () -> Unit) = run({ repo.login(id, pw) }) { push.scheduleSync(); onOk() }
    fun register(n: String, e: String, p: String, pw: String, c: String, onOk: () -> Unit) = run({ repo.register(n, e, p, pw, c) }) { push.scheduleSync(); onOk() }
    fun forgot(id: String, onOk: () -> Unit) = run({ repo.forgot(id) }) { info.value = R.string.reset_sent; onOk() }
    fun reset(id: String, code: String, pw: String) = run({ repo.reset(id, code, pw) }) { info.value = R.string.reset_done }
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

// Phone numbers and e-mails are always written left-to-right, even inside the RTL layout.
internal val ltr: androidx.compose.ui.text.TextStyle
    @Composable get() = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

@Composable
fun LoginScreen(back: () -> Unit, nav: (String) -> Unit, onDone: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    var id by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.login), back) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.logo), null, Modifier.height(110.dp))
            OutlinedTextField(id, { id = it }, label = { Text(stringResource(R.string.identifier)) }, singleLine = true, textStyle = ltr,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("login_identifier"))
            PasswordField(pw, { pw = it }, stringResource(R.string.password), Modifier.testTag("login_password"))
            error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            PrimaryButton(stringResource(R.string.login), { vm.login(id, pw, onDone) }, enabled = id.isNotBlank() && pw.isNotBlank(), loading = busy)
            TextButton(onClick = { nav(Routes.FORGOT) }) { Text(stringResource(R.string.forgot_password)) }
            TextButton(onClick = { nav(Routes.REGISTER) }) { Text(stringResource(R.string.no_account)) }
        }
    }
}

@Composable
fun RegisterScreen(back: () -> Unit, onRegistered: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
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

    Scaffold(topBar = { AppTopBar(stringResource(R.string.register), back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.full_name)) }, singleLine = true, isError = nameErr,
                supportingText = if (nameErr) ({ Text(stringResource(R.string.err_invalid_name)) }) else null, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.email)) }, singleLine = true, isError = emailErr, textStyle = ltr,
                supportingText = if (emailErr) ({ Text(stringResource(R.string.err_invalid_email)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.phone)) }, singleLine = true, isError = phoneErr, textStyle = ltr,
                placeholder = { Text(stringResource(R.string.phone_hint)) },
                supportingText = { Text(stringResource(if (phoneErr) R.string.err_invalid_phone else R.string.phone_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            PasswordField(pw, { pw = it }, stringResource(R.string.password), isError = pwErr, supporting = stringResource(if (pwErr) R.string.err_password_weak else R.string.password_rules))
            PasswordField(confirm, { confirm = it }, stringResource(R.string.confirm_password), isError = confirmErr, supporting = if (confirmErr) stringResource(R.string.err_password_mismatch) else null)
            error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            PrimaryButton(stringResource(R.string.register), {
                tried = true
                if (AuthValidation.nameValid(name) && AuthValidation.emailValid(email) && AuthValidation.normalizePhone(phone) != null &&
                    AuthValidation.passwordValid(pw) && confirm == pw
                ) vm.register(name, email, phone, pw, confirm, onRegistered)
            }, loading = busy)
        }
    }
}

@Composable
fun ForgotPasswordScreen(back: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    var id by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.reset_title), back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.reset_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(id, { id = it }, label = { Text(stringResource(R.string.identifier)) }, singleLine = true, textStyle = ltr, modifier = Modifier.fillMaxWidth())
            if (!sent) PrimaryButton(stringResource(R.string.reset_send), { vm.forgot(id) { sent = true } }, enabled = id.isNotBlank(), loading = busy)
            else {
                OutlinedTextField(code, { if (it.length <= 6) code = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.reset_code)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                PasswordField(pw, { pw = it }, stringResource(R.string.reset_new_password), supporting = stringResource(R.string.password_rules))
                PrimaryButton(stringResource(R.string.confirm), { vm.reset(id, code, pw) }, enabled = code.length == 6 && AuthValidation.passwordValid(pw), loading = busy)
            }
            info?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
        }
    }
}
