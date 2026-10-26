package dz.association.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dz.association.admin.R
import dz.association.app.core.network.AppError
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.errorMessage
import java.util.Calendar
import java.util.TimeZone

/** Translated message for admin-specific error codes, falling back to the shared mapping. */
@Composable
fun adminError(e: AppError): String {
    val code = (e as? AppError.Api)?.code
    val id = when (code) {
        "insufficient_role" -> R.string.e_insufficient_role
        "poll_locked_after_votes" -> R.string.e_poll_locked
        "cannot_extend_after_votes" -> R.string.e_cannot_extend
        "cannot_assign_role" -> R.string.e_cannot_assign_role
        "cannot_modify_self" -> R.string.e_cannot_modify_self
        "cannot_modify_higher_role" -> R.string.e_cannot_modify_higher
        "last_super_admin" -> R.string.e_last_super_admin
        "r2_not_configured" -> R.string.m_r2_off
        "unsupported_file_type" -> R.string.e_unsupported_file
        "file_too_large", "payload_too_large" -> R.string.e_file_too_large
        "invalid_ccp" -> R.string.e_invalid_ccp
        "invalid_rib" -> R.string.e_invalid_rib
        "volunteer_not_approved" -> R.string.e_volunteer_not_approved
        "ends_before_start" -> R.string.e_ends_before_start
        else -> null
    }
    return if (id != null) stringResource(id) else if (code?.startsWith("invalid_url") == true) stringResource(R.string.e_invalid_url) else errorMessage(e)
}

@Composable
fun ErrorText(e: AppError?) { if (e != null) Text(adminError(e), color = MaterialTheme.colorScheme.error) }

val LtrStyle @Composable get() = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    lines: Int = 1,
    ltr: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
) = OutlinedTextField(
    value = value, onValueChange = onChange, label = { Text(label) },
    singleLine = lines == 1, minLines = lines, isError = isError,
    textStyle = if (ltr) LtrStyle else androidx.compose.material3.LocalTextStyle.current,
    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Dropdown for a fixed set of values with translated labels. */
@Composable
fun <T> Choice(label: String, value: T, options: List<Pair<T, String>>, enabled: Boolean = true, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second ?: "", onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onChange(v); open = false }) }
        }
    }
}

private val ALGIERS = TimeZone.getTimeZone("Africa/Algiers")

/** Date + time picker in Algiers time; value is epoch milliseconds (0 = not set). */
@Composable
fun DateTimeField(label: String, value: Long, enabled: Boolean = true, onChange: (Long) -> Unit) {
    var step by remember { mutableIntStateOf(0) } // 0 closed, 1 date, 2 time
    var pickedDateUtc by remember { mutableStateOf(0L) }
    OutlinedButton(onClick = { step = 1 }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$label: " + if (value > 0) formatDateTime(value) else stringResource(R.string.a_pick))
    }
    if (step == 1) {
        val state = rememberDatePickerState(initialSelectedDateMillis = if (value > 0) value else System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = { TextButton(onClick = { pickedDateUtc = state.selectedDateMillis ?: return@TextButton; step = 2 }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(android.R.string.cancel)) } },
        ) { DatePicker(state) }
    }
    if (step == 2) {
        val cal = Calendar.getInstance(ALGIERS).apply { if (value > 0) timeInMillis = value }
        val state = rememberTimePickerState(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), is24Hour = true)
        AlertDialog(
            onDismissRequest = { step = 0 },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val d = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedDateUtc }
                    val out = Calendar.getInstance(ALGIERS).apply {
                        clear()
                        set(d.get(Calendar.YEAR), d.get(Calendar.MONTH), d.get(Calendar.DAY_OF_MONTH), state.hour, state.minute, 0)
                    }
                    onChange(out.timeInMillis); step = 0
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    text = { Text(text) },
    confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(stringResource(android.R.string.ok)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
)

/** Full-screen editor dialog with a scrollable form body. */
@Composable
fun EditorDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { AppTopBar(title, onDismiss) }) { pad ->
                Column(
                    Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
                )
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) =
    dz.association.app.ui.components.Pill(text, container = color, content = MaterialTheme.colorScheme.onSurface)

@Composable
fun statusLabel(s: String) = stringResource(
    when (s) {
        "published" -> R.string.st_published; "archived" -> R.string.st_archived; "cancelled" -> R.string.st_cancelled
        "active" -> R.string.st_active; "suspended" -> R.string.st_suspended; "hidden" -> R.string.st_hidden
        "pending" -> dz.association.app.core.R.string.volunteer_pending; "approved" -> dz.association.app.core.R.string.volunteer_approved
        "rejected" -> dz.association.app.core.R.string.volunteer_rejected
        else -> R.string.st_draft
    },
)

@Composable
fun CenterBox(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

fun String.blankToNull(): String? = trim().ifEmpty { null }
fun Long.orNow(): Long = if (this > 0) this else System.currentTimeMillis()
