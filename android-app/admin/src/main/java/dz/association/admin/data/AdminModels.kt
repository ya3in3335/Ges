package dz.association.admin.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors backend/src/routes/admin.ts, polls.ts, volunteers.ts, media.ts, notifications.ts.

@Serializable data class AdminUserRow(
    val id: String,
    @SerialName("full_name") val fullName: String,
    val email: String,
    @SerialName("phone_e164") val phone: String,
    val role: String,
    val status: String,
    @SerialName("phone_verified_at") val phoneVerifiedAt: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("telegram_linked") val telegramLinked: Int = 0,
)
@Serializable data class UsersPage(val items: List<AdminUserRow>, val total: Int = 0, val page: Int = 1)
@Serializable data class UpdateUserRequest(val role: String? = null, val status: String? = null)

@Serializable data class CountRow(val status: String? = null, val event: String? = null, val day: String? = null, val n: Int = 0)
@Serializable data class TelegramStats(
    val byStatus: List<CountRow> = emptyList(),
    val byEvent: List<CountRow> = emptyList(),
    val verifiedPerDay: List<CountRow> = emptyList(),
    val channelOutbox: List<CountRow> = emptyList(),
    val telegramConfigured: Boolean = false,
    val channelConfigured: Boolean = false,
)

@Serializable data class AdminNews(
    val id: String,
    val titleAr: String,
    val titleFr: String? = null,
    val bodyAr: String = "",
    val bodyFr: String? = null,
    val category: String = "news",
    val imageUrl: String? = null,
    val featured: Boolean = false,
    val status: String = "draft",
    val publishedAt: Long? = null,
)
@Serializable data class AdminNewsPage(val items: List<AdminNews>, val hasMore: Boolean = false)
@Serializable data class NewsInput(
    val titleAr: String,
    val titleFr: String? = null,
    val bodyAr: String,
    val bodyFr: String? = null,
    val category: String = "news",
    val imageUrl: String? = null,
    val featured: Boolean = false,
    val status: String = "draft",
    val notify: Boolean = false,
)

@Serializable data class AdminActivity(
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
    val status: String = "draft",
    val registrationOpen: Boolean = true,
    val phase: String = "upcoming",
)
@Serializable data class AdminActivityPage(val items: List<AdminActivity>, val hasMore: Boolean = false)
@Serializable data class ActivityInput(
    val titleAr: String,
    val titleFr: String? = null,
    val descriptionAr: String,
    val descriptionFr: String? = null,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val startsAt: Long,
    val endsAt: Long,
    val capacity: Int? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val isCampaign: Boolean = false,
    val featured: Boolean = false,
    val status: String = "draft",
    val registrationOpen: Boolean = true,
    val notify: Boolean = false,
)
@Serializable data class Participant(
    @SerialName("user_id") val userId: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("phone_e164") val phone: String,
    val status: String,
)
@Serializable data class Participants(val items: List<Participant>)
@Serializable data class StatusBody(val status: String)

@Serializable data class AdminPoll(
    val id: String,
    val titleAr: String,
    val titleFr: String? = null,
    val descriptionAr: String? = null,
    val startsAt: Long,
    val endsAt: Long,
    val eligibility: String = "verified",
    val resultVisibility: String = "after_close",
    val anonymous: Boolean = true,
    val status: String = "draft",
    val state: String = "upcoming",
    val votes: Int = 0,
    val members: Int = 0,
)
@Serializable data class AdminPollList(val items: List<AdminPoll>)
@Serializable data class OptionInput(val labelAr: String)
@Serializable data class PollInput(
    val titleAr: String? = null,
    val titleFr: String? = null,
    val descriptionAr: String? = null,
    val startsAt: Long? = null,
    val endsAt: Long? = null,
    val eligibility: String? = null,
    val resultVisibility: String? = null,
    val anonymous: Boolean? = null,
    val status: String? = null,
    val options: List<OptionInput>? = null,
)
@Serializable data class ResultOption(val id: String, val labelAr: String, val votes: Int)
@Serializable data class PollResultsAdmin(val options: List<ResultOption>, val totalVotes: Int, val eligibleVoters: Int)
@Serializable data class MembersRequest(val identifiers: List<String>)
@Serializable data class MembersResult(val added: Int, val notFound: List<String> = emptyList())
@Serializable data class IdResponse(val id: String)

@Serializable data class AdminVolunteer(
    val id: String,
    @SerialName("full_name") val fullName: String,
    val contact: String,
    val skills: String,
    val availability: String,
    @SerialName("preferred_areas") val preferredAreas: String? = null,
    val experience: String? = null,
    val status: String,
    @SerialName("review_note") val reviewNote: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("phone_verified_at") val phoneVerifiedAt: Long? = null,
)
@Serializable data class VolunteerPage(val items: List<AdminVolunteer>, val hasMore: Boolean = false)
@Serializable data class ReviewRequest(val status: String, val note: String? = null)
@Serializable data class AssignRequest(val activityId: String)

@Serializable data class AdminMedia(
    val id: String,
    val kind: String,
    val titleAr: String? = null,
    val category: String = "general",
    val url: String,
    val thumbnailUrl: String? = null,
    val featured: Boolean = false,
    val status: String = "published",
)
@Serializable data class AdminMediaPage(val items: List<AdminMedia>, val hasMore: Boolean = false, val r2Enabled: Boolean = false)
@Serializable data class ExternalMediaInput(
    val kind: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val titleAr: String? = null,
    val category: String = "general",
    val featured: Boolean = false,
)
@Serializable data class MediaUpdate(val status: String? = null, val featured: Boolean? = null)

@Serializable data class DonationInput(
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

@Serializable data class NotificationRow(
    val id: String,
    val topic: String,
    @SerialName("title_ar") val titleAr: String,
    @SerialName("target_user_id") val targetUserId: String? = null,
    val delivery: String,
    @SerialName("delivery_error") val deliveryError: String? = null,
    @SerialName("created_at") val createdAt: Long,
)
@Serializable data class NotificationLog(val items: List<NotificationRow>, val fcmConfigured: Boolean = false)
@Serializable data class SendNotification(
    val topic: String,
    val titleAr: String,
    val titleFr: String? = null,
    val bodyAr: String,
    val bodyFr: String? = null,
)
@Serializable data class SendResult(val id: String, val delivery: String = "queued")

@Serializable data class AuditRow(
    val id: Long,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("actor_role") val actorRole: String? = null,
    val action: String? = null,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    val event: String? = null,
    val detail: String? = null,
    @SerialName("created_at") val createdAt: Long,
)
@Serializable data class AuditPage(val items: List<AuditRow>, val hasMore: Boolean = false)

// ── One-time device activation ──
@Serializable data class ActivateRequest(val code: String, val deviceName: String? = null)
@Serializable data class ActivationCodeRequest(val name: String? = null, val role: String? = null, val userId: String? = null)
@Serializable data class ActivationCode(val code: String, val userId: String, val name: String, val role: String, val expiresAt: Long)
@Serializable data class DeviceRow(
    val id: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("last_used_at") val lastUsedAt: Long? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("full_name") val fullName: String,
    val role: String,
)
@Serializable data class DeviceList(val items: List<DeviceRow>)

// ── Activation approved from Telegram ──
@Serializable data class DeviceRequestBody(val name: String, val deviceName: String? = null)
@Serializable data class DeviceRequestCreated(val requestId: String, val secret: String, val expiresAt: Long)
@Serializable data class DeviceRequestStatus(
    val status: String,
    val user: dz.association.app.core.network.User? = null,
    val tokens: dz.association.app.core.network.Tokens? = null,
)
