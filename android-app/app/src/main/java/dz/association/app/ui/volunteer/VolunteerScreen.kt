package dz.association.app.ui.volunteer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.core.network.VolunteerMe
import dz.association.app.core.network.VolunteerRequest
import dz.association.app.core.util.formatDateTime
import dz.association.app.core.util.loc
import dz.association.app.data.AuthRepository
import dz.association.app.data.ParticipationRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.Pill
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.StateMessage
import dz.association.app.ui.components.UiState
import dz.association.app.ui.components.errorMessage
import dz.association.app.ui.navigation.Routes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolunteerActivism
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VolunteerViewModel @Inject constructor(private val repo: ParticipationRepository, private val auth: AuthRepository) : ViewModel() {
    val state = MutableStateFlow<UiState<VolunteerMe>>(UiState.Loading)
    val submitting = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    val user = auth.user
    init { load() }

    fun load() = viewModelScope.launch {
        if (!auth.isSignedIn) { state.value = UiState.Success(VolunteerMe()); return@launch }
        state.value = UiState.Loading
        state.value = when (val r = repo.myVolunteer()) { is Result.Ok -> UiState.Success(r.value); is Result.Err -> UiState.Error(r.error) }
    }

    fun submit(req: VolunteerRequest) = viewModelScope.launch {
        submitting.value = true; error.value = null
        when (val r = repo.applyVolunteer(req)) { is Result.Ok -> load(); is Result.Err -> error.value = r.error }
        submitting.value = false
    }

    fun withdraw() = viewModelScope.launch { repo.withdrawVolunteer(); load() }
}

@Composable
fun VolunteerScreen(nav: (String) -> Unit, vm: VolunteerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    var reapply by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.volunteer_title)) }) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> when {
                    user == null -> StateMessage(Icons.Outlined.VolunteerActivism, stringResource(R.string.volunteer_login), stringResource(R.string.login), { nav(Routes.LOGIN) })
                    s.data.application != null && !reapply -> ApplicationStatus(s.data, onReapply = { reapply = true }, onWithdraw = vm::withdraw)
                    else -> VolunteerForm(vm, user!!.fullName, user!!.phone) { reapply = false }
                }
            }
        }
    }
}

@Composable
private fun ApplicationStatus(d: VolunteerMe, onReapply: () -> Unit, onWithdraw: () -> Unit) {
    val a = d.application!!
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.volunteer_status), style = MaterialTheme.typography.titleMedium)
                val (label, color) = when (a.status) {
                    "approved" -> R.string.volunteer_approved to MaterialTheme.colorScheme.primaryContainer
                    "rejected" -> R.string.volunteer_rejected to MaterialTheme.colorScheme.errorContainer
                    else -> R.string.volunteer_pending to MaterialTheme.colorScheme.secondaryContainer
                }
                Pill(stringResource(label), color)
                a.reviewNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text("${stringResource(R.string.volunteer_skills)}: ${a.skills}", style = MaterialTheme.typography.bodySmall)
                Text("${stringResource(R.string.volunteer_availability)}: ${a.availability}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (d.assignments.isNotEmpty()) {
            Text(stringResource(R.string.volunteer_assignments), style = MaterialTheme.typography.titleMedium)
            d.assignments.forEach { x ->
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(loc(x.titleAr, x.titleFr), style = MaterialTheme.typography.titleSmall)
                        Text(formatDateTime(x.startsAt), style = MaterialTheme.typography.bodySmall)
                        x.roleNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
        if (a.status == "rejected") PrimaryButton(stringResource(R.string.volunteer_reapply), onReapply)
        else OutlinedButton(onClick = onWithdraw, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.volunteer_withdraw)) }
    }
}

@Composable
private fun VolunteerForm(vm: VolunteerViewModel, defaultName: String, defaultContact: String, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var contact by rememberSaveable { mutableStateOf(defaultContact) }
    var skills by rememberSaveable { mutableStateOf("") }
    var availability by rememberSaveable { mutableStateOf("") }
    var areas by rememberSaveable { mutableStateOf("") }
    var experience by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val required = stringResource(R.string.field_required)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.volunteer_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @Composable fun field(value: String, set: (String) -> Unit, label: Int, mandatory: Boolean = true, lines: Int = 1) = OutlinedTextField(
            value = value, onValueChange = set, label = { Text(stringResource(label)) }, minLines = lines,
            isError = tried && mandatory && value.isBlank(),
            supportingText = if (tried && mandatory && value.isBlank()) ({ Text(required) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        field(name, { name = it }, R.string.volunteer_full_name)
        field(contact, { contact = it }, R.string.volunteer_contact)
        field(skills, { skills = it }, R.string.volunteer_skills, lines = 2)
        field(availability, { availability = it }, R.string.volunteer_availability)
        field(areas, { areas = it }, R.string.volunteer_areas, mandatory = false)
        field(experience, { experience = it }, R.string.volunteer_experience, mandatory = false, lines = 3)
        error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
        PrimaryButton(stringResource(R.string.volunteer_submit), {
            tried = true
            if (listOf(name, contact, skills, availability).all { it.isNotBlank() }) {
                vm.submit(VolunteerRequest(name.trim(), contact.trim(), skills.trim(), availability.trim(), areas.trim().ifBlank { null }, experience.trim().ifBlank { null }))
                onDone()
            }
        }, loading = submitting)
    }
}
