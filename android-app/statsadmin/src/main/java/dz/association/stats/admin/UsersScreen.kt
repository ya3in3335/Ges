package dz.association.stats.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dz.association.app.core.R as CoreR
import dz.association.app.core.util.formatDate
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.roleLabel
import dz.association.stats.R
import dz.association.stats.data.UserRow
import dz.association.stats.ui.ChoiceChips
import dz.association.stats.ui.EmptyNote
import dz.association.stats.ui.ErrorBox
import dz.association.stats.ui.Loading
import dz.association.stats.ui.StatsCard
import dz.association.stats.ui.StatsColors
import dz.association.stats.ui.genderLabel
import dz.association.stats.ui.maritalLabel
import kotlinx.coroutines.delay

private val ROLES = listOf("", "USER", "VOLUNTEER", "EDITOR", "ADMIN", "SUPER_ADMIN")

/** The association's accounts (managers only; the server checks and logs the access). */
@Composable
fun UsersScreen(vm: UsersVm = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val role by vm.role.collectAsStateWithLifecycle()
    val staff by vm.staff.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(vm.query.value) }
    LaunchedEffect(text) { if (text != vm.query.value) { delay(350); vm.query.value = text; vm.load(reset = true) } }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.st_users_db)) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(text, { text = it.take(80) }, placeholder = { Text("${stringResource(CoreR.string.full_name)} / ${stringResource(CoreR.string.email)} / ${stringResource(CoreR.string.phone)}") },
                    singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            }
            item {
                val all = stringResource(R.string.st_all)
                ChoiceChips(ROLES, role ?: "", { if (it.isEmpty()) all else roleLabel(it) }, { vm.role.value = it.ifEmpty { null }; vm.load(reset = true) })
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.st_show_staff_accounts), Modifier.weight(1f), color = StatsColors.TextSecondary)
                    Switch(staff, { vm.staff.value = it; vm.load(reset = true) })
                }
            }
            item { Text(stringResource(R.string.st_results_count, total), color = StatsColors.TextSecondary, style = MaterialTheme.typography.labelMedium) }
            error?.let { e -> item { ErrorBox(e) { vm.load(reset = true) } } }
            if (loading && items.isEmpty()) item { Loading(Modifier.size(120.dp)) }
            if (!loading && items.isEmpty() && error == null) item { EmptyNote(stringResource(R.string.st_no_data)) }
            items(items, key = { it.id }) { UserCard(it) }
            if (hasMore) item { OutlinedButton(onClick = { vm.load(reset = false) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_load_more)) } }
        }
    }
}

@Composable
private fun UserCard(u: UserRow) {
    var open by rememberSaveable(u.id) { mutableStateOf(false) }
    val ltr = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)
    StatsCard(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(u.fullName, color = StatsColors.Text, style = MaterialTheme.typography.titleSmall)
                Text(roleLabel(u.role) + if (u.status != "active") " · ${u.status}" else "", color = StatsColors.Primary, style = MaterialTheme.typography.labelMedium)
            }
            if (u.phoneVerified || u.emailVerified) Icon(Icons.Outlined.Verified, stringResource(R.string.st_verified), tint = StatsColors.Primary, modifier = Modifier.size(18.dp))
        }
        u.email?.let { Text(it, color = StatsColors.TextSecondary, style = ltr) }
        u.phone?.let { Text(it, color = StatsColors.TextSecondary, style = ltr) }
        Text(stringResource(R.string.st_registrations_count, u.registrations, u.attended), color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        if (open) {
            Text(stringResource(R.string.st_joined, formatDate(u.createdAt)), color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(CoreR.string.email)}: ${stringResource(if (u.emailVerified) R.string.st_verified else R.string.st_not_verified)} · " +
                "${stringResource(CoreR.string.phone)}: ${stringResource(if (u.phoneVerified) R.string.st_verified else R.string.st_not_verified)}",
                color = StatsColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            val place = listOfNotNull(u.wilayaAr, u.communeAr).joinToString(" / ")
            if (place.isNotBlank()) Text("${stringResource(R.string.st_sec_residence)}: $place", color = StatsColors.Text, style = MaterialTheme.typography.bodySmall)
            u.birthDate?.let { Text("${stringResource(R.string.st_sec_birth)}: $it", color = StatsColors.Text, style = MaterialTheme.typography.bodySmall) }
            u.maritalStatus?.let { Text("${stringResource(R.string.st_sec_marital)}: ${maritalLabel(it)}", color = StatsColors.Text, style = MaterialTheme.typography.bodySmall) }
            u.gender?.let { Text(genderLabel(it), color = StatsColors.Text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
