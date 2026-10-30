package dz.association.stats.data

import kotlinx.serialization.Serializable

// Contract mirrors backend/src/routes/stats.ts.

/** The association account as returned by /api/me (with the membership date). */
@Serializable data class MeUser(
    val id: String,
    val fullName: String,
    val email: String,
    val phone: String,
    val role: String,
    val emailVerified: Boolean = false,
    val phoneVerified: Boolean = false,
    val createdAt: Long? = null,
)
@Serializable data class MeResponse(val user: MeUser)

@Serializable data class AccessResponse(val level: String? = null)
@Serializable data class SyncResponse(val version: String, val level: String? = null)

@Serializable data class MeetingCounts(
    val registered: Int = 0, val present: Int = 0, val late: Int = 0, val absent: Int = 0, val cancelled: Int = 0, val total: Int = 0,
) {
    val attended get() = present + late
}

@Serializable data class MyRegistrationRef(val id: String, val status: String, val regNo: Int)

@Serializable data class Meeting(
    val id: String,
    val title: String,
    val description: String? = null,
    val location: String,
    val startsAt: Long,
    val checkinOpensAt: Long,
    val checkinClosesAt: Long,
    val lateAfter: Long,
    /** Set by the organisers; [registrationOpen] also accounts for the status and the time. */
    val registrationEnabled: Boolean = true,
    val registrationOpen: Boolean = false,
    val status: String = "open",
    val closedAt: Long? = null,
    val counts: MeetingCounts? = null,
    val myRegistration: MyRegistrationRef? = null,
) {
    /** Open for registration and not already joined by this member. */
    val canJoin get() = registrationOpen && (myRegistration == null || myRegistration.status == "cancelled")
}

@Serializable data class MeetingList(val items: List<Meeting>)
@Serializable data class MeetingResponse(val item: Meeting)
@Serializable data class IdResponse(val id: String)

@Serializable data class MeetingRef(val title: String, val startsAt: Long, val location: String, val status: String)

@Serializable data class Registration(
    val id: String,
    val meetingId: String,
    val regNo: Int,
    val firstName: String,
    val lastName: String,
    val gender: String? = null,
    val wilayaCode: Int,
    val wilayaAr: String? = null,
    val wilayaFr: String? = null,
    val communeId: Int,
    val communeAr: String? = null,
    val communeFr: String? = null,
    val birthDate: String,
    val maritalStatus: String,
    /** registered · present · late · absent · cancelled */
    val status: String,
    val checkedInAt: Long? = null,
    val checkinMethod: String? = null,
    val checkedInByName: String? = null,
    val source: String = "member",
    val hasAccount: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val meeting: MeetingRef? = null,
    /** Only in the member's own registration: the signed check-in code (no personal data). */
    val qr: String? = null,
)

@Serializable data class RegistrationResponse(val item: Registration)
@Serializable data class RegistrationList(val items: List<Registration>, val hasMore: Boolean = false, val total: Int = 0, val page: Int = 1)

/** The census form. [gender] is optional. */
@Serializable data class PersonInput(
    val firstName: String,
    val lastName: String,
    val gender: String? = null,
    val wilayaCode: Int,
    val communeId: Int,
    val birthDate: String,
    val maritalStatus: String,
)

@Serializable data class ProfileDraft(
    val firstName: String = "",
    val lastName: String = "",
    val gender: String? = null,
    val wilayaCode: Int? = null,
    val communeId: Int? = null,
    val birthDate: String? = null,
    val maritalStatus: String? = null,
)
@Serializable data class ProfileResponse(val profile: ProfileDraft)

@Serializable data class MeetingRequest(
    val title: String,
    val description: String? = null,
    val location: String,
    val startsAt: Long,
    val checkinOpensAt: Long? = null,
    val checkinClosesAt: Long? = null,
    val lateAfter: Long? = null,
    val registrationOpen: Boolean = true,
)
@Serializable data class MeetingPatch(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val startsAt: Long? = null,
    val checkinOpensAt: Long? = null,
    val checkinClosesAt: Long? = null,
    val lateAfter: Long? = null,
    val registrationOpen: Boolean? = null,
    val status: String? = null,
)

@Serializable data class CheckinRequest(val meetingId: String, val code: String? = null, val registrationId: String? = null, val regNo: Int? = null)

@Serializable data class RegistrationPatch(
    val firstName: String? = null,
    val lastName: String? = null,
    val gender: String? = null,
    val wilayaCode: Int? = null,
    val communeId: Int? = null,
    val birthDate: String? = null,
    val maritalStatus: String? = null,
    val status: String? = null,
)

// ── Dashboard ──
@Serializable data class Totals(
    val registrations: Int = 0, val registered: Int = 0, val present: Int = 0, val late: Int = 0, val absent: Int = 0, val cancelled: Int = 0, val attended: Int = 0,
)
@Serializable data class GenderSplit(val male: Int = 0, val female: Int = 0, val unknown: Int = 0)
@Serializable data class WilayaCount(val code: Int, val ar: String? = null, val fr: String? = null, val count: Int, val attended: Int = 0)
@Serializable data class CommuneCount(val id: Int, val ar: String? = null, val fr: String? = null, val wilayaCode: Int? = null, val count: Int, val attended: Int = 0)
@Serializable data class KeyCount(val key: String, val count: Int)
@Serializable data class Stats(
    val meetingId: String? = null,
    val totals: Totals = Totals(),
    val attendanceRate: Double? = null,
    val gender: GenderSplit = GenderSplit(),
    val byWilaya: List<WilayaCount> = emptyList(),
    val byCommune: List<CommuneCount> = emptyList(),
    val ageGroups: List<KeyCount> = emptyList(),
    val maritalStatus: List<KeyCount> = emptyList(),
)
@Serializable data class Dashboard(val stats: Stats, val meetings: List<Meeting> = emptyList(), val generatedAt: Long = 0)
@Serializable data class CloseResponse(val report: Stats)

// ── Staff & audit ──
@Serializable data class StaffMember(val userId: String, val fullName: String, val email: String, val role: String, val level: String, val grantedAt: Long)
@Serializable data class StaffList(val items: List<StaffMember>, val canManage: Boolean = false)
@Serializable data class AuditItem(val id: Long, val action: String, val entityType: String, val entityId: String? = null, val detail: String? = null, val at: Long, val actor: String? = null)
@Serializable data class AuditList(val items: List<AuditItem>)

// ── Users database (managers) ──
@Serializable data class UserRow(
    val id: String,
    val fullName: String,
    val email: String? = null,
    val phone: String? = null,
    val role: String,
    val status: String = "active",
    val emailVerified: Boolean = false,
    val phoneVerified: Boolean = false,
    val staffOnly: Boolean = false,
    val createdAt: Long = 0,
    val registrations: Int = 0,
    val attended: Int = 0,
    val gender: String? = null,
    val birthDate: String? = null,
    val maritalStatus: String? = null,
    val wilayaCode: Int? = null,
    val wilayaAr: String? = null,
    val communeAr: String? = null,
    /** member · supervisor · census */
    val kind: String = "member",
    val statsLevel: String? = null,
    val lastSeen: Long? = null,
)
@Serializable data class UserList(val items: List<UserRow>, val hasMore: Boolean = false, val total: Int = 0, val page: Int = 1)

// ── Activation from the Telegram bot ──
@Serializable data class ActivationRequest(val name: String, val deviceName: String? = null, val app: String = "stats")
@Serializable data class ActivationCreated(val requestId: String, val secret: String, val expiresAt: Long)
@Serializable data class ActivationStatus(
    val status: String,
    val user: dz.association.app.core.network.User? = null,
    val tokens: dz.association.app.core.network.Tokens? = null,
)
