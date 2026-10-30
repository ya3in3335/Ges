package dz.association.stats.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.stats.data.AuditItem
import dz.association.stats.data.CheckinRequest
import dz.association.stats.data.Dashboard
import dz.association.stats.data.Geo
import dz.association.stats.data.Meeting
import dz.association.stats.data.MeetingPatch
import dz.association.stats.data.MeetingRequest
import dz.association.stats.data.Registration
import dz.association.stats.data.RegistrationPatch
import dz.association.stats.data.StaffList
import dz.association.stats.data.StatsAccount
import dz.association.stats.data.StatsApi
import dz.association.stats.data.SyncPoller
import dz.association.stats.ui.CensusFormState
import dz.association.stats.ui.LiveVm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Who is signed in and what the server lets them do ("manager", "officer" or nothing). */
@HiltViewModel
class AccessVm @Inject constructor(val account: StatsAccount, poller: SyncPoller) : ViewModel() {
    /** null = checking; "" = no census access. */
    val level = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<AppError?>(null)

    init {
        viewModelScope.launch { account.user.collect { if (it != null) check() else level.value = null } }
        // Access can be withdrawn at any time: re-checked whenever data changes.
        viewModelScope.launch { poller.changes.collect { if (account.isSignedIn) check(quiet = true) } }
    }

    fun check(quiet: Boolean = false) = viewModelScope.launch {
        if (!quiet) { level.value = null; error.value = null }
        when (val r = account.accessLevel()) {
            is Result.Ok -> level.value = r.value ?: ""
            is Result.Err -> when (r.error) {
                AppError.Unauthorized -> account.forget()
                else -> if (!quiet) error.value = r.error
            }
        }
    }

    fun logout() = viewModelScope.launch { account.logout() }
}

@HiltViewModel
class DashboardVm @Inject constructor(private val api: StatsApi, poller: SyncPoller) : LiveVm<Dashboard>(poller) {
    val meetingId = MutableStateFlow<String?>(null)
    init { reload() }
    fun select(id: String?) { meetingId.value = id; reload() }
    override suspend fun fetch(): Result<Dashboard> = apiCall { api.dashboard(meetingId.value) }
}

@HiltViewModel
class MeetingsVm @Inject constructor(private val api: StatsApi, poller: SyncPoller) : LiveVm<List<Meeting>>(poller) {
    init { reload() }
    override suspend fun fetch(): Result<List<Meeting>> = apiCall { api.meetings().items }
}

@HiltViewModel
class MeetingVm @Inject constructor(private val api: StatsApi, private val poller: SyncPoller) : LiveVm<Meeting>(poller) {
    var id: String = ""
        set(value) { if (field != value) { field = value; reload() } }
    val busy = MutableStateFlow(false)
    val actionError = MutableStateFlow<AppError?>(null)
    val report = MutableStateFlow<dz.association.stats.data.Stats?>(null)

    override suspend fun fetch(): Result<Meeting> = apiCall { api.meeting(id).item }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true; actionError.value = null
        when (val r = apiCall { block() }) {
            is Result.Ok -> { poller.poke(); reload(silent = true) }
            is Result.Err -> actionError.value = r.error
        }
        busy.value = false
    }

    fun setRegistration(open: Boolean) = act { api.updateMeeting(id, MeetingPatch(registrationOpen = open)) }
    fun cancelMeeting() = act { api.updateMeeting(id, MeetingPatch(status = "cancelled")) }
    fun close() = act { report.value = api.closeMeeting(id).report }
}

@HiltViewModel
class MeetingEditVm @Inject constructor(private val api: StatsApi, private val poller: SyncPoller) : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    val existing = MutableStateFlow<Meeting?>(null)

    fun load(id: String?) = viewModelScope.launch {
        if (id == null || existing.value?.id == id) return@launch
        (apiCall { api.meeting(id).item } as? Result.Ok)?.let { existing.value = it.value }
    }

    fun save(id: String?, req: MeetingRequest, onDone: (String) -> Unit) = viewModelScope.launch {
        busy.value = true; error.value = null
        val r = if (id == null) apiCall { api.createMeeting(req).id }
        else apiCall {
            api.updateMeeting(id, MeetingPatch(req.title, req.description ?: "", req.location, req.startsAt, req.checkinOpensAt, req.checkinClosesAt, req.lateAfter, req.registrationOpen)).item.id
        }
        when (r) {
            is Result.Ok -> { poller.poke(); onDone(r.value) }
            is Result.Err -> error.value = r.error
        }
        busy.value = false
    }
}

sealed interface CheckinResult {
    data class Ok(val reg: Registration) : CheckinResult
    data class Already(val reg: Registration?) : CheckinResult
    data class Failed(val error: AppError) : CheckinResult
}

@HiltViewModel
class CheckinVm @Inject constructor(private val api: StatsApi, private val poller: SyncPoller) : ViewModel() {
    val meetings = MutableStateFlow<List<Meeting>>(emptyList())
    val meetingId = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val result = MutableStateFlow<CheckinResult?>(null)
    val pending = MutableStateFlow<List<Registration>>(emptyList())
    val query = MutableStateFlow("")

    init {
        loadMeetings()
        viewModelScope.launch { poller.changes.collect { loadMeetings(); search() } }
    }

    fun loadMeetings() = viewModelScope.launch {
        (apiCall { api.meetings().items } as? Result.Ok)?.let { r ->
            val open = r.value.filter { it.status == "open" }
            meetings.value = open
            if (meetingId.value == null || open.none { it.id == meetingId.value }) {
                val now = System.currentTimeMillis()
                // The meeting whose confirmation period is running now, else the nearest one.
                meetingId.value = (open.firstOrNull { now in it.checkinOpensAt..it.checkinClosesAt } ?: open.minByOrNull { kotlin.math.abs(it.startsAt - now) })?.id
                search()
            }
        }
    }

    fun select(id: String) { meetingId.value = id; result.value = null; search() }

    fun search(q: String = query.value) = viewModelScope.launch {
        query.value = q
        val m = meetingId.value ?: return@launch
        (apiCall { api.registrations(meetingId = m, query = q.trim().ifBlank { null }, status = "registered", pageSize = 30).items } as? Result.Ok)?.let { pending.value = it.value }
    }

    private fun confirm(req: (String) -> CheckinRequest) {
        val m = meetingId.value ?: return
        viewModelScope.launch {
            busy.value = true
            result.value = when (val r = apiCall { api.checkin(req(m)).item }) {
                is Result.Ok -> { poller.poke(); search(); CheckinResult.Ok(r.value) }
                is Result.Err -> if ((r.error as? AppError.Api)?.code == "already_checked_in") CheckinResult.Already(null) else CheckinResult.Failed(r.error)
            }
            busy.value = false
        }
    }

    fun byQr(code: String) = confirm { CheckinRequest(it, code = code) }
    fun byNumber(no: Int) = confirm { CheckinRequest(it, regNo = no) }
    fun byId(id: String) = confirm { CheckinRequest(it, registrationId = id) }
}

data class RecordFilters(
    val meetingId: String? = null,
    val query: String = "",
    val wilaya: Int? = null,
    val commune: Int? = null,
    val status: String? = null,
)

@HiltViewModel
class RecordsVm @Inject constructor(private val api: StatsApi, geo: Geo, poller: SyncPoller) : ViewModel() {
    val geo = geo.data
    val filters = MutableStateFlow(RecordFilters())
    val items = MutableStateFlow<List<Registration>>(emptyList())
    val total = MutableStateFlow(0)
    val hasMore = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    val meetings = MutableStateFlow<List<Meeting>>(emptyList())
    private var page = 1

    init {
        viewModelScope.launch { (apiCall { api.meetings().items } as? Result.Ok)?.let { meetings.value = it.value } }
        viewModelScope.launch { poller.changes.collect { load(reset = true, quiet = true) } }
    }

    fun update(f: RecordFilters) { if (f != filters.value) { filters.value = f; load(reset = true) } }

    fun load(reset: Boolean, quiet: Boolean = false) = viewModelScope.launch {
        if (reset) page = 1 else page++
        if (!quiet) loading.value = true
        error.value = null
        val f = filters.value
        when (val r = apiCall { api.registrations(f.meetingId, f.query.trim().ifBlank { null }, f.wilaya, f.commune, f.status, page = if (quiet) 1 else page, pageSize = if (quiet) (page * 50).coerceAtMost(200) else 50) }) {
            is Result.Ok -> {
                items.value = if (reset || quiet) r.value.items else items.value + r.value.items
                total.value = r.value.total
                hasMore.value = r.value.hasMore
            }
            is Result.Err -> if (!quiet) error.value = r.error
        }
        loading.value = false
    }

    /** The complete filtered list for an export (managers only; the server checks). */
    suspend fun exportRows(): Result<List<Registration>> {
        val f = filters.value
        return apiCall { api.registrations(f.meetingId, f.query.trim().ifBlank { null }, f.wilaya, f.commune, f.status, all = 1).items }
    }
}

@HiltViewModel
class RecordVm @Inject constructor(private val api: StatsApi, geo: Geo, private val poller: SyncPoller) : LiveVm<Registration>(poller) {
    val geo = geo.data
    var id: String = ""
        set(value) { if (field != value) { field = value; reload() } }
    val form = CensusFormState(requireMeeting = false)
    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val actionError = MutableStateFlow<AppError?>(null)

    override suspend fun fetch(): Result<Registration> = apiCall { api.registration(id).item }

    fun startEdit(r: Registration) {
        form.showErrors = false
        form.firstName = r.firstName; form.lastName = r.lastName; form.gender = r.gender
        form.wilaya = r.wilayaCode; form.commune = r.communeId; form.marital = r.maritalStatus
        r.birthDate.split('-').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }?.let { (y, m, d) -> form.year = y; form.month = m; form.day = d }
        status.value = r.status
    }

    fun save(onDone: () -> Unit) {
        form.showErrors = true
        val p = form.toInput(geo) ?: return
        viewModelScope.launch {
            busy.value = true; actionError.value = null
            when (val r = apiCall { api.updateRegistration(id, RegistrationPatch(p.firstName, p.lastName, p.gender, p.wilayaCode, p.communeId, p.birthDate, p.maritalStatus, status.value)).item }) {
                is Result.Ok -> { set(r.value); poller.poke(); onDone() }
                is Result.Err -> actionError.value = r.error
            }
            busy.value = false
        }
    }
}

@HiltViewModel
class StaffVm @Inject constructor(private val api: StatsApi, poller: SyncPoller) : LiveVm<StaffList>(poller) {
    val actionError = MutableStateFlow<AppError?>(null)
    init { reload() }
    override suspend fun fetch(): Result<StaffList> = apiCall { api.staff() }

    fun revoke(userId: String) = viewModelScope.launch {
        when (val r = apiCall { api.revokeStaff(userId) }) {
            is Result.Ok -> reload(silent = true)
            is Result.Err -> actionError.value = r.error
        }
    }
}

@HiltViewModel
class AuditVm @Inject constructor(private val api: StatsApi, poller: SyncPoller) : LiveVm<List<AuditItem>>(poller) {
    init { reload() }
    override suspend fun fetch(): Result<List<AuditItem>> = apiCall { api.audit().items }
}

@HiltViewModel
class UsersVm @Inject constructor(private val api: StatsApi) : ViewModel() {
    val items = MutableStateFlow<List<dz.association.stats.data.UserRow>>(emptyList())
    val total = MutableStateFlow(0)
    val hasMore = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    val query = MutableStateFlow("")
    val role = MutableStateFlow<String?>(null)
    val staff = MutableStateFlow(false)
    private var page = 1

    init { load(reset = true) }

    fun load(reset: Boolean) = viewModelScope.launch {
        if (reset) page = 1 else page++
        loading.value = true; error.value = null
        when (val r = apiCall { api.users(query.value.trim().ifBlank { null }, role.value, if (staff.value) 1 else null, page) }) {
            is Result.Ok -> { items.value = if (reset) r.value.items else items.value + r.value.items; total.value = r.value.total; hasMore.value = r.value.hasMore }
            is Result.Err -> error.value = r.error
        }
        loading.value = false
    }
}
