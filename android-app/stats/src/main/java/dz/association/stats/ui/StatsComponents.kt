package dz.association.stats.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dz.association.app.core.network.AppError
import dz.association.app.ui.components.errorMessage
import dz.association.stats.R

// ───────────── Containers ─────────────

@Composable
fun StatsCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape, color = StatsColors.Surface, border = BorderStroke(1.dp, StatsColors.Line),
    ) { Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
fun SectionTitle(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) {
            Box(Modifier.size(30.dp).background(StatsColors.PrimaryDeep, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = StatsColors.Primary, modifier = Modifier.size(18.dp))
            }
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = StatsColors.Text)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = StatsColors.Primary, icon: ImageVector? = null) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = StatsColors.Surface, border = BorderStroke(1.dp, StatsColors.Line)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = StatsColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = StatsColors.Text)
        }
    }
}

// ───────────── Status ─────────────

fun statusColor(status: String): Color = when (status) {
    "present" -> StatsColors.Primary
    "late" -> StatsColors.Warning
    "absent" -> StatsColors.Danger
    "cancelled" -> StatsColors.TextSecondary
    else -> StatsColors.Info
}

@Composable
fun statusLabel(status: String): String = stringResource(
    when (status) {
        "present" -> R.string.st_status_present
        "late" -> R.string.st_status_late
        "absent" -> R.string.st_status_absent
        "cancelled" -> R.string.st_status_cancelled
        else -> R.string.st_status_registered
    },
)

@Composable
fun StatusBadge(status: String) {
    val c = statusColor(status)
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.16f), border = BorderStroke(1.dp, c.copy(alpha = 0.5f))) {
        Text(statusLabel(status), color = c, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun maritalLabel(key: String?): String = when (key) {
    "single" -> stringResource(R.string.st_marital_single)
    "married" -> stringResource(R.string.st_marital_married)
    "divorced" -> stringResource(R.string.st_marital_divorced)
    "widowed" -> stringResource(R.string.st_marital_widowed)
    else -> "—"
}

@Composable
fun genderLabel(key: String?): String = when (key) {
    "male" -> stringResource(R.string.st_gender_male)
    "female" -> stringResource(R.string.st_gender_female)
    else -> stringResource(R.string.st_gender_unknown)
}

@Composable
fun meetingStatusLabel(status: String) = stringResource(
    when (status) { "closed" -> R.string.st_meeting_status_closed; "cancelled" -> R.string.st_meeting_status_cancelled; else -> R.string.st_meeting_status_open },
)

// ───────────── Errors ─────────────

/** Census-specific error codes first, then the association's shared messages. */
@Composable
fun statsError(e: AppError): String = when ((e as? AppError.Api)?.code) {
    "already_registered" -> stringResource(R.string.st_err_already_registered)
    "registration_closed" -> stringResource(R.string.st_err_registration_closed)
    "invalid_commune" -> stringResource(R.string.st_err_invalid_commune)
    "invalid_birth_date" -> stringResource(R.string.st_err_invalid_birth_date)
    "not_stats_staff" -> stringResource(R.string.st_err_not_staff)
    "not_stats_manager" -> stringResource(R.string.st_err_not_manager)
    "checkin_not_open" -> stringResource(R.string.st_err_checkin_not_open)
    "checkin_closed" -> stringResource(R.string.st_err_checkin_closed)
    "wrong_meeting" -> stringResource(R.string.st_err_wrong_meeting)
    "invalid_qr" -> stringResource(R.string.st_err_invalid_qr)
    "registration_cancelled" -> stringResource(R.string.st_err_registration_cancelled)
    "meeting_not_open", "meeting_closed" -> stringResource(R.string.st_err_meeting_not_open)
    "registration_not_found", "not_found" -> stringResource(R.string.st_err_not_found)
    "cannot_cancel" -> stringResource(R.string.st_err_cannot_cancel)
    "user_not_found" -> stringResource(R.string.st_err_user_not_found)
    "invalid_checkin_window", "invalid_late_after" -> stringResource(R.string.st_err_checkin_window)
    "insufficient_role" -> stringResource(R.string.st_err_insufficient_role)
    "already_checked_in" -> stringResource(R.string.st_already_checked)
    "approval_not_configured" -> stringResource(R.string.st_err_approval_not_configured)
    "cannot_remove_self" -> stringResource(R.string.st_err_cannot_remove_self)
    else -> errorMessage(e)
}

@Composable
fun ErrorBox(error: AppError, onRetry: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(16.dp), color = StatsColors.Danger.copy(alpha = 0.12f), border = BorderStroke(1.dp, StatsColors.Danger.copy(alpha = 0.4f))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = StatsColors.Danger)
            Text(statsError(error), color = StatsColors.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(dz.association.app.core.R.string.retry)) }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = StatsColors.Primary) }
}

@Composable
fun EmptyNote(text: String) {
    Text(text, color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

// ───────────── Inputs ─────────────

/** A read-only dropdown (Material 3 exposed menu). Long lists can be filtered by typing when [searchable]. */
@Composable
fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    placeholder: String? = null,
    searchable: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val shown = if (searchable && filter.isNotBlank()) options.filter { optionLabel(it).contains(filter.trim(), ignoreCase = true) } else options
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) { expanded = it; if (!it) filter = "" } }, modifier = modifier) {
        OutlinedTextField(
            value = if (expanded && searchable) filter else selected?.let(optionLabel) ?: "",
            onValueChange = { if (searchable) filter = it },
            readOnly = !searchable,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().menuAnchor(if (searchable) MenuAnchorType.PrimaryEditable else MenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false; filter = "" }) {
            shown.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { onSelect(o); expanded = false; filter = "" })
            }
        }
    }
}

/** Single choice shown as chips (marital status, gender, filters). */
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StatsColors.PrimaryDeep, selectedLabelColor = StatsColors.Text,
                    containerColor = StatsColors.Surface, labelColor = StatsColors.TextSecondary,
                ),
            )
        }
    }
}
