package dz.association.app.ui.lectures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.R
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Lecture
import dz.association.app.core.network.LectureDetail
import dz.association.app.core.network.LectureProfileResponse
import dz.association.app.core.network.MemberProfile
import dz.association.app.core.network.Result
import dz.association.app.core.util.formatDateTime
import dz.association.app.core.util.loc
import dz.association.app.core.util.locOrNull
import dz.association.app.data.AuthRepository
import dz.association.app.data.LecturesRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.EmptyState
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.LectureLabels
import dz.association.app.ui.components.Pill
import dz.association.app.ui.components.PrimaryButton
import dz.association.app.ui.components.UiState
import dz.association.app.ui.components.errorMessage
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

// ───────────────────────── Schedule ─────────────────────────

@HiltViewModel
class LecturesViewModel @Inject constructor(private val repo: LecturesRepository) : ViewModel() {
    val past = MutableStateFlow(false)
    val state = MutableStateFlow<UiState<List<Lecture>>>(UiState.Loading)
    init { load() }
    fun select(p: Boolean) { past.value = p; load() }
    fun load() = viewModelScope.launch {
        state.value = UiState.Loading
        state.value = when (val r = repo.list(past.value, 1)) { is Result.Ok -> UiState.Success(r.value.items); is Result.Err -> UiState.Error(r.error) }
    }
}

@Composable
fun LecturesScreen(back: () -> Unit, nav: (String) -> Unit, vm: LecturesViewModel = hiltViewModel()) {
    val past by vm.past.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.lectures_title), back) }) { pad ->
        Column(Modifier.padding(pad)) {
            PrimaryTabRow(selectedTabIndex = if (past) 1 else 0) {
                Tab(!past, { vm.select(false) }, text = { Text(stringResource(R.string.lec_upcoming)) })
                Tab(past, { vm.select(true) }, text = { Text(stringResource(R.string.lec_past)) })
            }
            when (val s = state) {
                UiState.Loading -> FullScreenLoading()
                is UiState.Error -> ErrorState(s.error, { vm.load() })
                is UiState.Success -> if (s.data.isEmpty()) EmptyState() else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(s.data, key = { it.id }) { l -> LectureCard(l) { nav(Routes.lecture(l.id)) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LectureCard(l: Lecture, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(LectureLabels.kind(l.kind))
                when {
                    l.status == "cancelled" -> Pill(stringResource(R.string.lec_cancelled), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    l.myRegistration == "registered" -> Pill(stringResource(R.string.lec_registered), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    l.myRegistration == "attended" -> Pill(stringResource(R.string.lec_attended), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(loc(l.titleAr, l.titleFr), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            InfoLine(Icons.Outlined.Event, formatDateTime(l.startsAt))
            l.speaker?.let { InfoLine(Icons.Outlined.Person, it) }
            l.location?.let { InfoLine(Icons.Outlined.Place, it) }
            l.capacity?.let { Text(stringResource(R.string.lec_seats, l.registeredCount, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ───────────────────────── Detail ─────────────────────────

@HiltViewModel
class LectureDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: LecturesRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    val id: String = checkNotNull(handle["id"])
    val state = MutableStateFlow<UiState<LectureDetail>>(UiState.Loading)
    val busy = MutableStateFlow(false)
    val actionError = MutableStateFlow<AppError?>(null)
    val signedIn get() = auth.isSignedIn
    val user = auth.user

    fun load() = viewModelScope.launch {
        if (state.value !is UiState.Success) state.value = UiState.Loading
        state.value = when (val r = repo.detail(id)) { is Result.Ok -> UiState.Success(r.value); is Result.Err -> UiState.Error(r.error) }
    }

    fun cancel() = viewModelScope.launch {
        busy.value = true; actionError.value = null
        when (val r = repo.cancel(id)) { is Result.Ok -> load(); is Result.Err -> actionError.value = r.error }
        busy.value = false
    }
}

@Composable
fun LectureDetailScreen(back: () -> Unit, nav: (String) -> Unit, vm: LectureDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val err by vm.actionError.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    // Reloaded each time the screen is shown again (after verifying, registering…).
    LaunchedEffect(Unit) { vm.load() }
    val uri = LocalUriHandler.current
    Scaffold(topBar = { AppTopBar(stringResource(R.string.lectures_title), back) }) { pad ->
        when (val s = state) {
            UiState.Loading -> Column(Modifier.padding(pad)) { FullScreenLoading() }
            is UiState.Error -> Column(Modifier.padding(pad)) { ErrorState(s.error, { vm.load() }) }
            is UiState.Success -> {
                val l = s.data.item
                Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill(LectureLabels.kind(l.kind))
                    Text(loc(l.titleAr, l.titleFr), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    InfoLine(Icons.Outlined.Event, formatDateTime(l.startsAt) + " → " + formatDateTime(l.endsAt).substringAfter("– "))
                    l.speaker?.let { InfoLine(Icons.Outlined.Person, stringResource(R.string.lec_speaker, it)) }
                    l.location?.let { InfoLine(Icons.Outlined.Place, it) }
                    l.onlineUrl?.let { url -> TextButton(onClick = { runCatching { uri.openUri(url) } }) { Icon(Icons.Outlined.Videocam, null); Spacer(Modifier.width(6.dp)); Text(url, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), maxLines = 1) } }
                    Text(
                        l.capacity?.let { stringResource(R.string.lec_seats, l.registeredCount, it) } ?: stringResource(R.string.lec_registered_count, l.registeredCount),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    locOrNull(l.descriptionAr, l.descriptionFr)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    Spacer(Modifier.height(4.dp))
                    err?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
                    val my = s.data.myRegistration
                    when {
                        l.status == "cancelled" -> Pill(stringResource(R.string.lec_cancelled), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        my == "attended" -> Done(stringResource(R.string.lec_attended))
                        my == "registered" -> {
                            Done(stringResource(R.string.lec_registered))
                            if (l.registrationOpen) {
                                OutlinedButton(onClick = { nav(Routes.lectureForm(l.id)) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lec_edit_answers)) }
                                TextButton(onClick = { vm.cancel() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lec_cancel), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                        !l.registrationOpen -> Pill(stringResource(R.string.lec_closed), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        !vm.signedIn -> {
                            Text(stringResource(R.string.poll_need_login), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PrimaryButton(stringResource(R.string.login), { nav(Routes.LOGIN) })
                        }
                        !s.data.contactVerified -> {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Lock, null); Spacer(Modifier.width(10.dp))
                                    Text(stringResource(R.string.lec_need_verify), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (user?.emailVerified != true) OutlinedButton(onClick = { nav(Routes.verifyEmail()) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.lec_verify_email)) }
                            if (user?.phoneVerified != true) OutlinedButton(onClick = { nav(Routes.VERIFY) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.lec_verify_phone)) }
                        }
                        else -> PrimaryButton(stringResource(R.string.lec_register), { nav(Routes.lectureForm(l.id)) }, Modifier.testTag("lecture_register"))
                    }
                }
            }
        }
    }
}

@Composable
private fun Done(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(Modifier.width(10.dp))
            Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleSmall)
        }
    }
}

// ───────────────────────── Registration form ─────────────────────────

@HiltViewModel
class LectureFormViewModel @Inject constructor(handle: SavedStateHandle, private val repo: LecturesRepository) : ViewModel() {
    val id: String = checkNotNull(handle["id"])
    val state = MutableStateFlow<UiState<LectureProfileResponse>>(UiState.Loading)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    init { load() }
    fun load() = viewModelScope.launch {
        state.value = UiState.Loading
        state.value = when (val r = repo.profile()) { is Result.Ok -> UiState.Success(r.value); is Result.Err -> UiState.Error(r.error) }
    }
    fun submit(p: MemberProfile, note: String?, onDone: () -> Unit) = viewModelScope.launch {
        busy.value = true; error.value = null
        when (val r = repo.register(id, p, note)) { is Result.Ok -> onDone(); is Result.Err -> error.value = r.error }
        busy.value = false
    }
}

/** Fields that must be filled; returns the names of the missing ones (empty when the form is complete). */
fun missingFields(p: MemberProfile, nowYear: Int = Calendar.getInstance().get(Calendar.YEAR)): Set<String> = buildSet {
    val nameOk = { s: String -> s.trim().length in 2..60 && s.all { it.isLetter() || it in " '.-" || Character.getType(it) == Character.NON_SPACING_MARK.toInt() } }
    if (!nameOk(p.firstName)) add("firstName")
    if (!nameOk(p.lastName)) add("lastName")
    if (p.gender == null) add("gender")
    if (p.birthYear == null || p.birthYear !in 1920..(nowYear - 5)) add("birthYear")
    if (p.wilaya.isNullOrBlank()) add("wilaya")
    if (p.maritalStatus == null) add("maritalStatus")
    if (p.maritalStatus != null && p.maritalStatus != "single" && p.childrenCount == null) add("childrenCount")
    if (p.occupation == null) add("occupation")
    if (LectureLabels.needsDetail(p.occupation) && p.occupationDetail.isNullOrBlank()) add("occupationDetail")
}

@Composable
fun LectureFormScreen(back: () -> Unit, onDone: () -> Unit, vm: LectureFormViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.lec_form_title), back) }) { pad ->
        when (val s = state) {
            UiState.Loading -> Column(Modifier.padding(pad)) { FullScreenLoading() }
            is UiState.Error -> Column(Modifier.padding(pad)) { ErrorState(s.error, { vm.load() }) }
            is UiState.Success -> LectureForm(s.data, vm, Modifier.padding(pad), onDone)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LectureForm(data: LectureProfileResponse, vm: LectureFormViewModel, modifier: Modifier, onDone: () -> Unit) {
    val saved = data.profile ?: MemberProfile()
    var first by rememberSaveable { mutableStateOf(saved.firstName) }
    var last by rememberSaveable { mutableStateOf(saved.lastName) }
    var gender by rememberSaveable { mutableStateOf(saved.gender) }
    var year by rememberSaveable { mutableStateOf(saved.birthYear?.toString().orEmpty()) }
    var wilaya by rememberSaveable { mutableStateOf(saved.wilaya) }
    var marital by rememberSaveable { mutableStateOf(saved.maritalStatus) }
    var children by rememberSaveable { mutableStateOf(saved.childrenCount?.toString().orEmpty()) }
    var occupation by rememberSaveable { mutableStateOf(saved.occupation) }
    var detail by rememberSaveable { mutableStateOf(saved.occupationDetail.orEmpty()) }
    var note by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val profile = MemberProfile(
        firstName = first.trim(), lastName = last.trim(), gender = gender, birthYear = year.toIntOrNull(), wilaya = wilaya,
        maritalStatus = marital, childrenCount = if (marital == null || marital == "single") null else children.toIntOrNull(),
        occupation = occupation, occupationDetail = detail.trim().takeIf { LectureLabels.needsDetail(occupation) && it.isNotEmpty() },
    )
    val missing = if (tried) missingFields(profile) else emptySet()

    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(first, { first = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.lec_first_name)) }, singleLine = true, isError = "firstName" in missing)
            OutlinedTextField(last, { last = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.lec_last_name)) }, singleLine = true, isError = "lastName" in missing)
        }
        OutlinedTextField(
            data.phone, {}, Modifier.fillMaxWidth(), readOnly = true, enabled = false, label = { Text(stringResource(R.string.lec_phone_auto)) },
            leadingIcon = { Icon(Icons.Outlined.Lock, null) }, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
        )

        Question(stringResource(R.string.lec_gender), "gender" in missing) {
            LectureLabels.genders.forEach { g -> FilterChip(gender == g, { gender = g }, { Text(LectureLabels.gender(g)) }) }
        }
        OutlinedTextField(
            year, { year = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lec_birth_year)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = "birthYear" in missing,
        )
        WilayaPicker(wilaya, "wilaya" in missing) { wilaya = it }

        Question(stringResource(R.string.lec_marital), "maritalStatus" in missing) {
            LectureLabels.marital.forEach { m -> FilterChip(marital == m, { marital = m }, { Text(LectureLabels.marital(m)) }) }
        }
        if (marital != null && marital != "single") {
            OutlinedTextField(
                children, { children = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lec_children)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = "childrenCount" in missing,
            )
        }

        Question(stringResource(R.string.lec_occupation), "occupation" in missing) {
            LectureLabels.occupations.forEach { o -> FilterChip(occupation == o, { occupation = o }, { Text(LectureLabels.occupation(o)) }) }
        }
        if (LectureLabels.needsDetail(occupation)) {
            OutlinedTextField(detail, { detail = it.take(120) }, Modifier.fillMaxWidth(), label = { Text(LectureLabels.detailLabel(occupation)) }, singleLine = true, isError = "occupationDetail" in missing)
        }

        OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lec_note)) }, minLines = 2)
        Text(stringResource(R.string.lec_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (missing.isNotEmpty()) {
            val labels = mapOf(
                "firstName" to stringResource(R.string.lec_first_name), "lastName" to stringResource(R.string.lec_last_name),
                "gender" to stringResource(R.string.lec_gender), "birthYear" to stringResource(R.string.lec_birth_year),
                "wilaya" to stringResource(R.string.lec_wilaya), "maritalStatus" to stringResource(R.string.lec_marital),
                "childrenCount" to stringResource(R.string.lec_children), "occupation" to stringResource(R.string.lec_occupation),
                "occupationDetail" to LectureLabels.detailLabel(occupation),
            )
            Text(stringResource(R.string.lec_fill_these, missing.mapNotNull { labels[it] }.joinToString("، ")), color = MaterialTheme.colorScheme.error)
        }
        error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
        PrimaryButton(stringResource(R.string.lec_submit), {
            tried = true
            if (missingFields(profile).isEmpty()) vm.submit(profile, note.trim().ifEmpty { null }, onDone)
        }, Modifier.testTag("lecture_submit"), loading = busy)
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Question(title: String, error: Boolean, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WilayaPicker(value: String?, error: Boolean, onPick: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(
            value.orEmpty(), {}, readOnly = true, isError = error, label = { Text(stringResource(R.string.lec_wilaya)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(open, { open = false }) {
            LectureLabels.wilayas.forEach { w -> DropdownMenuItem(text = { Text(w) }, onClick = { onPick(w); open = false }) }
        }
    }
}
