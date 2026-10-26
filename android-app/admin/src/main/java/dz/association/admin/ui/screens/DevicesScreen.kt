package dz.association.admin.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.admin.R
import dz.association.admin.data.ActivationCode
import dz.association.admin.data.ActivationCodeRequest
import dz.association.admin.data.AdminApi
import dz.association.admin.data.AdminRoles
import dz.association.admin.data.DeviceRow
import dz.association.admin.ui.Choice
import dz.association.admin.ui.ConfirmDialog
import dz.association.admin.ui.ErrorText
import dz.association.admin.ui.Field
import dz.association.admin.ui.ListVm
import dz.association.admin.ui.PagedList
import dz.association.admin.ui.Tag
import dz.association.app.core.auth.SessionManager
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.app.core.util.formatDateTime
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.SectionHeader
import dz.association.app.ui.components.roleLabel
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@HiltViewModel
class DevicesVm @Inject constructor(private val api: AdminApi, session: SessionManager) : ListVm<DeviceRow>() {
    val issued = MutableStateFlow<ActivationCode?>(null)
    val myId = session.user.value?.id
    init { reload() }
    override suspend fun fetch(page: Int): Result<Pair<List<DeviceRow>, Boolean>> =
        when (val r = apiCall { api.devices() }) { is Result.Ok -> Result.Ok(r.value.items to false); is Result.Err -> r }
    fun createCode(req: ActivationCodeRequest) = act(reloadAfter = false) {
        apiCall { api.createActivationCode(req) }.also { if (it is Result.Ok) issued.value = it.value }
    }
    fun revoke(d: DeviceRow) = act { apiCall { api.revokeDevice(d.id) } }
}

@Composable
fun DevicesScreen(back: () -> Unit, myRole: String, vm: DevicesVm = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    val issued by vm.issued.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    // Administrators can only create supervisors below their own role (SUPER_ADMIN: any).
    val roles = listOf("EDITOR", "ADMIN", "SUPER_ADMIN").filter { myRole == "SUPER_ADMIN" || (AdminRoles.atLeast(myRole, it) && it != myRole) }
    var role by rememberSaveable { mutableStateOf(roles.firstOrNull() ?: "EDITOR") }
    var revoking by remember { mutableStateOf<DeviceRow?>(null) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.dev_title), back) }) { pad ->
        Box(Modifier.padding(pad)) {
            PagedList(s, vm::reload, vm::loadMore, header = {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.dev_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AppCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.dev_new), style = MaterialTheme.typography.titleMedium)
                                Field(name, { name = it }, stringResource(R.string.dev_name))
                                Choice(stringResource(R.string.u_role), role, roles.map { it to roleLabel(it) }) { role = it }
                                PrimaryButton(stringResource(R.string.dev_generate), { vm.createCode(ActivationCodeRequest(name = name.trim(), role = role)); name = "" }, enabled = name.trim().length >= 2 && roles.isNotEmpty(), loading = busy)
                                TextButton(onClick = { vm.myId?.let { vm.createCode(ActivationCodeRequest(userId = it)) } }) { Text(stringResource(R.string.dev_for_me)) }
                            }
                        }
                        ErrorText(err)
                    }
                }
                item { SectionHeader(stringResource(R.string.dev_active)) }
            }) { list ->
                items(list, key = { it.id }) { d ->
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(d.fullName, style = MaterialTheme.typography.titleSmall)
                                Text(d.deviceName ?: "—", style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.dev_last_used, formatDateTime(d.lastUsedAt ?: d.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Tag(roleLabel(d.role))
                                    if (d.current) Tag(stringResource(R.string.dev_this_device), MaterialTheme.colorScheme.primaryContainer)
                                }
                            }
                            if (d.canRevoke) OutlinedButton(onClick = { revoking = d }) { Text(stringResource(R.string.dev_revoke), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    issued?.let { c -> CodeDialog(c) { vm.issued.value = null; vm.reload() } }
    revoking?.let { d -> ConfirmDialog(stringResource(R.string.dev_revoke_confirm, d.fullName), { vm.revoke(d) }, { revoking = null }) }
}

/** Shows the new code once, with copy/share. It cannot be displayed again later. */
@Composable
private fun CodeDialog(c: ActivationCode, close: () -> Unit) {
    val ctx = LocalContext.current
    val shareText = stringResource(R.string.dev_share_text, c.code)
    val copied = stringResource(dz.association.app.core.R.string.copied)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.dev_code_for, c.name)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    c.code.chunked(4).joinToString(" "),
                    style = MaterialTheme.typography.displaySmall.copy(textDirection = TextDirection.Ltr, letterSpacing = 4.sp),
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
                )
                Text(stringResource(R.string.dev_code_note, formatDateTime(c.expiresAt)), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", c.code))
                        Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(dz.association.app.core.R.string.copy)) }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText), null))
                    }) { Text(stringResource(dz.association.app.core.R.string.share)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(android.R.string.ok)) } },
    )
}
