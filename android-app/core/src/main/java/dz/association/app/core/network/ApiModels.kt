package dz.association.app.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Contract mirrors backend/src/routes/*. See docs/API.md.

@Serializable data class ApiError(val error: String = "unknown", val details: kotlinx.serialization.json.JsonElement? = null)

@Serializable data class Tokens(
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val refreshTokenExpiresAt: Long,
)

@Serializable data class Preferences(
    val notifyNews: Boolean = true,
    val notifyActivities: Boolean = true,
    val notifyPolls: Boolean = true,
    val notifyVolunteer: Boolean = true,
    val showNamePublic: Boolean = false,
)

@Serializable data class User(
    val id: String,
    val fullName: String,
    val email: String,
    val phone: String,
    val role: String,
    val locale: String = "ar",
    val emailVerified: Boolean = false,
    val phoneVerified: Boolean = false,
    val phoneVerifiedAt: Long? = null,
    val telegramLinked: Boolean = false,
    val preferences: Preferences = Preferences(),
)

@Serializable data class AuthResponse(val user: User, val tokens: Tokens)
@Serializable data class RefreshResponse(val tokens: Tokens)
@Serializable data class UserResponse(val user: User)
@Serializable data class OkResponse(val ok: Boolean = true)

@Serializable data class RegisterRequest(val fullName: String, val email: String, val phone: String, val password: String, val confirmPassword: String, val locale: String)
@Serializable data class LoginRequest(val identifier: String, val password: String, val client: String = "android")
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable class EmptyRequest
@Serializable data class EmailSendResponse(val ok: Boolean = true, val alreadyVerified: Boolean = false)
@Serializable data class EmailCodeRequest(val code: String)
@Serializable data class ForgotRequest(val identifier: String)
@Serializable data class ResetRequest(val identifier: String, val code: String, val newPassword: String)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable data class DeleteAccountRequest(val password: String)
@Serializable data class UpdateMeRequest(val fullName: String? = null, val locale: String? = null, val preferences: Preferences? = null)
@Serializable data class DeviceRequest(val token: String, val locale: String)
@Serializable data class DeviceUnregisterRequest(val token: String)

// ── Telegram verification ──
@Serializable data class VerificationStart(val verificationId: String, val deepLink: String, val appLink: String, val expiresAt: Long)
@Serializable data class VerificationStatus(
    val verificationId: String,
    val status: String,
    val failureReason: String? = null,
    val attemptsLeft: Int = 0,
    val expiresAt: Long = 0,
    val verifiedAt: Long? = null,
)

// ── Home (aggregated) ──
@Serializable data class HomeNews(
    val id: String,
    @SerialName("title_ar") val titleAr: String,
    @SerialName("title_fr") val titleFr: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val category: String = "news",
    @SerialName("published_at") val publishedAt: Long? = null,
    @SerialName("excerpt_ar") val excerptAr: String? = null,
    @SerialName("excerpt_fr") val excerptFr: String? = null,
)
@Serializable data class HomeActivity(
    val id: String,
    @SerialName("title_ar") val titleAr: String,
    @SerialName("title_fr") val titleFr: String? = null,
    val location: String? = null,
    @SerialName("starts_at") val startsAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("is_campaign") val isCampaign: Int = 0,
    val featured: Int = 0,
)
@Serializable data class HomePoll(
    val id: String,
    @SerialName("title_ar") val titleAr: String,
    @SerialName("title_fr") val titleFr: String? = null,
    @SerialName("ends_at") val endsAt: Long,
)
@Serializable data class HomeMedia(
    val id: String,
    val kind: String,
    val url: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("title_ar") val titleAr: String? = null,
    @SerialName("title_fr") val titleFr: String? = null,
)
@Serializable data class HomeResponse(
    val settings: Map<String, String> = emptyMap(),
    val featuredNews: List<HomeNews> = emptyList(),
    val announcements: List<HomeNews> = emptyList(),
    val recentNews: List<HomeNews> = emptyList(),
    val activities: List<HomeActivity> = emptyList(),
    val activePolls: List<HomePoll> = emptyList(),
    val media: List<HomeMedia> = emptyList(),
    val donationAvailable: Boolean = false,
    val serverTime: Long = 0,
)

// ── News ──
@Serializable data class NewsItem(
    val id: String,
    val titleAr: String,
    val titleFr: String? = null,
    val category: String = "news",
    val imageUrl: String? = null,
    val featured: Boolean = false,
    val publishedAt: Long? = null,
    val updatedAt: Long = 0,
    val bodyAr: String? = null,
    val bodyFr: String? = null,
    val excerptAr: String? = null,
    val excerptFr: String? = null,
)
@Serializable data class NewsPage(val items: List<NewsItem>, val page: Int = 1, val hasMore: Boolean = false)
@Serializable data class NewsDetail(val item: NewsItem)

// ── Activities ──
@Serializable data class Activity(
    val id: String,
    val titleAr: String,
    val titleFr: String? = null,
    val descriptionAr: String = "",
    val descriptionFr: String? = null,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val startsAt: Long,
    val endsAt: Long,
    val capacity: Int? = null,
    val registeredCount: Int = 0,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val isCampaign: Boolean = false,
    val featured: Boolean = false,
    val status: String = "published",
    val phase: String = "upcoming",
    val registrationOpen: Boolean = false,
    val updatedAt: Long = 0,
    val myRegistration: String? = null,
)
@Serializable data class ActivityPage(val items: List<Activity>, val page: Int = 1, val hasMore: Boolean = false)
@Serializable data class ActivityMedia(
    val id: String,
    val kind: String,
    val url: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("title_ar") val titleAr: String? = null,
)
@Serializable data class ActivityDetail(val item: Activity, val myRegistration: String? = null, val media: List<ActivityMedia> = emptyList())
@Serializable data class ActivityList(val items: List<Activity>)

// ── Polls ──
@Serializable data class Poll(
    val id: String,
    val titleAr: String,
    val titleFr: String? = null,
    val descriptionAr: String? = null,
    val descriptionFr: String? = null,
    val startsAt: Long,
    val endsAt: Long,
    val eligibility: String = "verified",
    val resultVisibility: String = "after_close",
    val anonymous: Boolean = true,
    val status: String = "published",
    val state: String = "active",
    val hasVoted: Boolean = false,
)
@Serializable data class PollList(val items: List<Poll>)
@Serializable data class PollOption(val id: String, val labelAr: String, val labelFr: String? = null, val description: String? = null, val imageUrl: String? = null)
@Serializable data class PollResults(val totalVotes: Int, val byOption: Map<String, Int>)
@Serializable data class PollDetail(
    val poll: Poll,
    val options: List<PollOption>,
    val eligible: Boolean,
    val ineligibleReason: String? = null,
    val hasVoted: Boolean,
    val receipt: String? = null,
    val votedAt: Long? = null,
    val results: PollResults? = null,
)
@Serializable data class VoteRequest(val optionId: String)
@Serializable data class VoteResponse(val ok: Boolean, val receipt: String, val votedAt: Long)

// ── Volunteers ──
@Serializable data class VolunteerApplication(
    val id: String,
    val fullName: String,
    val contact: String,
    val skills: String,
    val availability: String,
    val preferredAreas: String? = null,
    val experience: String? = null,
    val status: String,
    val reviewNote: String? = null,
    val createdAt: Long = 0,
)
@Serializable data class VolunteerAssignment(
    val id: String,
    @SerialName("title_ar") val titleAr: String,
    @SerialName("title_fr") val titleFr: String? = null,
    @SerialName("starts_at") val startsAt: Long,
    val location: String? = null,
    @SerialName("role_note") val roleNote: String? = null,
)
@Serializable data class VolunteerMe(val application: VolunteerApplication? = null, val assignments: List<VolunteerAssignment> = emptyList())
@Serializable data class VolunteerRequest(
    val fullName: String,
    val contact: String,
    val skills: String,
    val availability: String,
    val preferredAreas: String? = null,
    val experience: String? = null,
)

// ── Media ──
@Serializable data class MediaItem(
    val id: String,
    val kind: String,
    val titleAr: String? = null,
    val titleFr: String? = null,
    val category: String = "general",
    val url: String,
    val thumbnailUrl: String? = null,
    val activityId: String? = null,
    val featured: Boolean = false,
    val createdAt: Long = 0,
)
@Serializable data class MediaPage(val items: List<MediaItem>, val categories: List<String> = emptyList(), val page: Int = 1, val hasMore: Boolean = false)

// ── Donations & settings ──
@Serializable data class DonationInfo(
    val ccpAccount: String? = null,
    val ccpKey: String? = null,
    val ccpHolder: String? = null,
    val bankName: String? = null,
    val bankAccount: String? = null,
    val bankHolder: String? = null,
    val instructionsAr: String? = null,
    val instructionsFr: String? = null,
    val contactPhone: String? = null,
    val contactEmail: String? = null,
)
@Serializable data class DonationCampaign(
    val id: String,
    @SerialName("title_ar") val titleAr: String,
    @SerialName("title_fr") val titleFr: String? = null,
    @SerialName("description_ar") val descriptionAr: String? = null,
    @SerialName("description_fr") val descriptionFr: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)
@Serializable data class DonationsResponse(val donation: DonationInfo, val campaigns: List<DonationCampaign> = emptyList())
@Serializable data class SettingsResponse(val settings: Map<String, String> = emptyMap())

@Serializable data class AppNotification(
    val id: String,
    val topic: String,
    @SerialName("title_ar") val titleAr: String,
    @SerialName("title_fr") val titleFr: String? = null,
    @SerialName("body_ar") val bodyAr: String,
    @SerialName("body_fr") val bodyFr: String? = null,
    @SerialName("deep_link") val deepLink: String? = null,
    @SerialName("created_at") val createdAt: Long,
)
@Serializable data class NotificationList(val items: List<AppNotification>)

// ───────────── Lectures & meetings ─────────────

@Serializable data class Lecture(
    val id: String,
    val kind: String = "lecture",
    val titleAr: String,
    val titleFr: String? = null,
    val descriptionAr: String? = null,
    val descriptionFr: String? = null,
    val speaker: String? = null,
    val location: String? = null,
    val onlineUrl: String? = null,
    val startsAt: Long,
    val endsAt: Long,
    val capacity: Int? = null,
    val registeredCount: Int = 0,
    val status: String = "published",
    val phase: String = "upcoming",
    val registrationOpen: Boolean = false,
    val myRegistration: String? = null,
)
@Serializable data class LecturePage(val items: List<Lecture>, val hasMore: Boolean = false)
@Serializable data class LectureDetail(val item: Lecture, val myRegistration: String? = null, val contactVerified: Boolean = false)

/** Registration form answers (saved per member to prefill the next form). */
@Serializable data class MemberProfile(
    val firstName: String = "",
    val lastName: String = "",
    val gender: String? = null,
    val birthYear: Int? = null,
    val wilaya: String? = null,
    val maritalStatus: String? = null,
    val childrenCount: Int? = null,
    val occupation: String? = null,
    val occupationDetail: String? = null,
)
@Serializable data class LectureProfileResponse(
    val profile: MemberProfile? = null,
    val phone: String,
    val phoneVerified: Boolean = false,
    val email: String = "",
    val emailVerified: Boolean = false,
    val contactVerified: Boolean = false,
)
@Serializable data class LectureRegisterRequest(val profile: MemberProfile, val note: String? = null)
