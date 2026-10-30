package dz.association.stats.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.core.network.AppError
import dz.association.app.core.network.AppNotification
import dz.association.app.core.network.Result
import dz.association.app.core.network.apiCall
import dz.association.stats.data.Geo
import dz.association.stats.data.Meeting
import dz.association.stats.data.MeUser
import dz.association.stats.data.Registration
import dz.association.stats.data.StatsAccount
import dz.association.stats.data.StatsApi
import dz.association.stats.data.SyncPoller
import dz.association.stats.ui.CensusFormState
import dz.association.stats.ui.LiveVm
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Verifies the stored session with the server on start (renewing it when needed). */
@HiltViewModel
class SessionVm @Inject constructor(val account: StatsAccount) : ViewModel() {
    init {
        if (account.isSignedIn) viewModelScope.launch {
            // A refused session clears itself (and the app shows the sign-in screen); offline keeps the cached one.
            if ((account.refreshMe() as? Result.Err)?.error == AppError.Unauthorized) account.forget()
        }
    }
    fun logout() = viewModelScope.launch { account.logout() }
}

data class HomeData(
    val me: MeUser?,
    val meetings: List<Meeting>,
    val mine: List<Registration>,
    val notifications: List<AppNotification>,
)

@HiltViewModel
class HomeVm @Inject constructor(private val api: StatsApi, poller: SyncPoller) : LiveVm<HomeData>(poller) {
    init { reload() }

    override suspend fun fetch(): Result<HomeData> = apiCall {
        coroutineScope {
            val me = async { runCatching { api.me().user }.getOrNull() }
            val meetings = async { api.openMeetings().items }
            val mine = async { api.myRegistrations().items }
            val notes = async { runCatching { api.notifications().items }.getOrDefault(emptyList()) }
            HomeData(me.await(), meetings.await(), mine.await(), notes.await())
        }
    }
}

@HiltViewModel
class CensusVm @Inject constructor(private val api: StatsApi, geo: Geo, private val poller: SyncPoller) : ViewModel() {
    val geo = geo.data
    val form = CensusFormState()
    val meetings = MutableStateFlow<List<Meeting>?>(null)
    val loadError = MutableStateFlow<AppError?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)

    fun load(preselect: String?) = viewModelScope.launch {
        loadError.value = null
        when (val r = apiCall { api.openMeetings().items }) {
            // Only meetings still open and not already joined by this member.
            is Result.Ok -> {
                val open = r.value.filter { it.canJoin }
                meetings.value = open
                if (form.meetingId == null) form.meetingId = preselect?.takeIf { id -> open.any { it.id == id } } ?: open.singleOrNull()?.id
            }
            is Result.Err -> loadError.value = r.error
        }
        (apiCall { api.profile().profile } as? Result.Ok)?.let { form.prefill(it.value) }
    }

    fun submit(onDone: (Registration) -> Unit) {
        form.showErrors = true
        val input = form.toInput(geo) ?: return
        val meeting = form.meetingId ?: return
        viewModelScope.launch {
            busy.value = true; error.value = null
            when (val r = apiCall { api.register(meeting, input).item }) {
                is Result.Ok -> { poller.poke(); onDone(r.value) }
                is Result.Err -> error.value = r.error
            }
            busy.value = false
        }
    }
}

@HiltViewModel
class CardVm @Inject constructor(private val api: StatsApi, private val poller: SyncPoller) : LiveVm<Registration>(poller) {
    var id: String = ""
        set(value) { if (field != value) { field = value; reload() } }
    val busy = MutableStateFlow(false)
    val actionError = MutableStateFlow<AppError?>(null)

    override suspend fun fetch(): Result<Registration> = apiCall { api.myRegistration(id).item }

    fun cancel() = viewModelScope.launch {
        busy.value = true; actionError.value = null
        when (val r = apiCall { api.cancelMine(id).item }) {
            is Result.Ok -> { poller.poke(); reload(silent = true) }
            is Result.Err -> actionError.value = r.error
        }
        busy.value = false
    }
}

@HiltViewModel
class HistoryVm @Inject constructor(private val api: StatsApi, poller: SyncPoller) : LiveVm<List<Registration>>(poller) {
    init { reload() }
    override suspend fun fetch(): Result<List<Registration>> = apiCall { api.myRegistrations().items }
}

@HiltViewModel
class ProfileVm @Inject constructor(private val api: StatsApi, geo: Geo) : ViewModel() {
    val geo = geo.data
    val form = CensusFormState(requireMeeting = false)
    val me = MutableStateFlow<MeUser?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    val saved = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            (apiCall { api.me().user } as? Result.Ok)?.let { me.value = it.value }
            (apiCall { api.profile().profile } as? Result.Ok)?.let { form.prefill(it.value) }
        }
    }

    fun save() {
        form.showErrors = true
        val input = form.toInput(geo) ?: return
        viewModelScope.launch {
            busy.value = true; error.value = null; saved.value = false
            when (val r = apiCall { api.saveProfile(input) }) {
                is Result.Ok -> saved.value = true
                is Result.Err -> error.value = r.error
            }
            busy.value = false
        }
    }
}
