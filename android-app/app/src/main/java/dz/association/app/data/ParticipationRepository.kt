package dz.association.app.data

import dz.association.app.core.network.ApiService
import dz.association.app.core.network.VolunteerRequest
import dz.association.app.core.network.VoteRequest
import dz.association.app.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Actions that always require server confirmation (never cached or queued offline):
 * voting, activity registration, volunteer applications, phone verification.
 */
@Singleton
class ParticipationRepository @Inject constructor(private val api: ApiService) {
    suspend fun polls(state: String) = apiCall { api.polls(state).items }
    suspend fun poll(id: String) = apiCall { api.poll(id) }
    suspend fun vote(pollId: String, optionId: String) = apiCall { api.vote(pollId, VoteRequest(optionId)) }

    suspend fun registerActivity(id: String) = apiCall { api.registerActivity(id) }
    suspend fun cancelActivity(id: String) = apiCall { api.cancelActivity(id) }
    suspend fun myActivities() = apiCall { api.myActivities().items }

    suspend fun myVolunteer() = apiCall { api.myVolunteer() }
    suspend fun applyVolunteer(r: VolunteerRequest) = apiCall { api.applyVolunteer(r) }
    suspend fun withdrawVolunteer() = apiCall { api.withdrawVolunteer() }

    suspend fun startTelegramVerification() = apiCall { api.startTelegramVerification() }
    suspend fun verificationStatus(id: String) = apiCall { api.telegramVerification(id) }

    suspend fun notifications() = apiCall { api.myNotifications().items }
}
