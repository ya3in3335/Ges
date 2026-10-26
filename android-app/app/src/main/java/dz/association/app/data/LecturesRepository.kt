package dz.association.app.data

import dz.association.app.core.network.ApiService
import dz.association.app.core.network.LectureRegisterRequest
import dz.association.app.core.network.MemberProfile
import dz.association.app.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

/** Lecture schedule and registrations (always confirmed by the server, never queued offline). */
@Singleton
class LecturesRepository @Inject constructor(private val api: ApiService) {
    suspend fun list(past: Boolean, page: Int) = apiCall { api.lectures(if (past) "past" else "upcoming", page) }
    suspend fun detail(id: String) = apiCall { api.lecture(id) }
    suspend fun profile() = apiCall { api.lectureProfile() }
    suspend fun register(id: String, profile: MemberProfile, note: String?) = apiCall { api.registerLecture(id, LectureRegisterRequest(profile, note)) }
    suspend fun cancel(id: String) = apiCall { api.cancelLecture(id) }
}
