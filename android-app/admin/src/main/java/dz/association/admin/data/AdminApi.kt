package dz.association.admin.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dz.association.app.core.network.DonationsResponse
import dz.association.app.core.network.OkResponse
import dz.association.app.core.network.SettingsResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Singleton

/** Administration endpoints. Present only in the supervisors' app. */
interface AdminApi {
    @GET("api/admin/overview") suspend fun overview(): Map<String, Int>
    @GET("api/admin/telegram-stats") suspend fun telegramStats(): TelegramStats

    @GET("api/admin/users") suspend fun users(
        @Query("page") page: Int,
        @Query("q") q: String?,
        @Query("role") role: String?,
        @Query("verified") verified: String?,
        @Query("pageSize") pageSize: Int = 25,
    ): UsersPage
    @PATCH("api/admin/users/{id}") suspend fun updateUser(@Path("id") id: String, @Body b: UpdateUserRequest): OkResponse
    @POST("api/admin/users/{id}/reset-verification") suspend fun resetVerification(@Path("id") id: String, @Body b: Map<String, String> = emptyMap()): OkResponse

    @GET("api/admin/news") suspend fun news(@Query("page") page: Int, @Query("status") status: String?): AdminNewsPage
    @POST("api/admin/news") suspend fun createNews(@Body b: NewsInput): IdResponse
    @PATCH("api/admin/news/{id}") suspend fun updateNews(@Path("id") id: String, @Body b: NewsInput): OkResponse
    @DELETE("api/admin/news/{id}") suspend fun deleteNews(@Path("id") id: String): OkResponse

    @GET("api/admin/lectures") suspend fun lectures(): AdminLecturePage
    @POST("api/admin/lectures") suspend fun createLecture(@Body b: LectureInput): IdResponse
    @PATCH("api/admin/lectures/{id}") suspend fun updateLecture(@Path("id") id: String, @Body b: LectureInput): OkResponse
    @DELETE("api/admin/lectures/{id}") suspend fun deleteLecture(@Path("id") id: String): OkResponse
    @GET("api/admin/lectures/{id}/registrations") suspend fun lectureRegistrations(@Path("id") id: String): LectureRegistrationList
    @PATCH("api/admin/lectures/{id}/registrations/{userId}") suspend fun setLectureRegistration(@Path("id") id: String, @Path("userId") userId: String, @Body b: StatusBody): OkResponse
    @GET("api/admin/activities") suspend fun activities(@Query("page") page: Int): AdminActivityPage
    @POST("api/admin/activities") suspend fun createActivity(@Body b: ActivityInput): IdResponse
    @PATCH("api/admin/activities/{id}") suspend fun updateActivity(@Path("id") id: String, @Body b: ActivityInput): OkResponse
    @GET("api/admin/activities/{id}/participants") suspend fun participants(@Path("id") id: String): Participants
    @PATCH("api/admin/activities/{id}/participants/{userId}") suspend fun setParticipant(@Path("id") id: String, @Path("userId") userId: String, @Body b: StatusBody): OkResponse

    @GET("api/admin/polls") suspend fun polls(): AdminPollList
    @GET("api/polls/{id}") suspend fun pollDetail(@Path("id") id: String): dz.association.app.core.network.PollDetail
    @POST("api/admin/polls") suspend fun createPoll(@Body b: PollInput): IdResponse
    @PATCH("api/admin/polls/{id}") suspend fun updatePoll(@Path("id") id: String, @Body b: PollInput): OkResponse
    @GET("api/admin/polls/{id}/results") suspend fun pollResults(@Path("id") id: String): PollResultsAdmin
    @POST("api/admin/polls/{id}/members") suspend fun addMembers(@Path("id") id: String, @Body b: MembersRequest): MembersResult

    @GET("api/admin/volunteers") suspend fun volunteers(@Query("page") page: Int, @Query("status") status: String?, @Query("q") q: String?): VolunteerPage
    @PATCH("api/admin/volunteers/{id}") suspend fun reviewVolunteer(@Path("id") id: String, @Body b: ReviewRequest): OkResponse
    @POST("api/admin/volunteers/{id}/assignments") suspend fun assignVolunteer(@Path("id") id: String, @Body b: AssignRequest): OkResponse

    @GET("api/admin/media") suspend fun media(@Query("page") page: Int): AdminMediaPage
    @POST("api/admin/media") suspend fun addExternalMedia(@Body b: ExternalMediaInput): IdResponse
    @Multipart @POST("api/admin/media/upload") suspend fun uploadMedia(
        @Part file: MultipartBody.Part,
        @Part("titleAr") title: RequestBody,
        @Part("category") category: RequestBody,
        @Part("featured") featured: RequestBody,
    ): IdResponse
    @PATCH("api/admin/media/{id}") suspend fun updateMedia(@Path("id") id: String, @Body b: MediaUpdate): OkResponse
    @DELETE("api/admin/media/{id}") suspend fun deleteMedia(@Path("id") id: String): OkResponse

    @GET("api/donations") suspend fun donations(): DonationsResponse
    @PUT("api/admin/donations") suspend fun updateDonations(@Body b: DonationInput): DonationsResponse
    @GET("api/settings") suspend fun settings(): SettingsResponse
    @PUT("api/admin/settings") suspend fun updateSettings(@Body b: Map<String, String>): SettingsResponse

    @GET("api/admin/notifications") suspend fun notifications(): NotificationLog
    @POST("api/admin/notifications") suspend fun sendNotification(@Body b: SendNotification): SendResult

    @POST("api/auth/device-requests") suspend fun requestActivation(@Body b: DeviceRequestBody): DeviceRequestCreated
    @GET("api/auth/device-requests/{id}") suspend fun pollActivation(@Path("id") id: String, @retrofit2.http.Header("x-request-secret") secret: String): DeviceRequestStatus
    @POST("api/auth/activate") suspend fun activate(@Body b: ActivateRequest): dz.association.app.core.network.AuthResponse
    @POST("api/admin/activation-codes") suspend fun createActivationCode(@Body b: ActivationCodeRequest): ActivationCode
    @GET("api/admin/devices") suspend fun devices(): DeviceList
    @DELETE("api/admin/devices/{id}") suspend fun revokeDevice(@Path("id") id: String): OkResponse

    @GET("api/admin/audit") suspend fun audit(@Query("source") source: String, @Query("page") page: Int, @Query("pageSize") pageSize: Int = 50): AuditPage
}

@Module
@InstallIn(SingletonComponent::class)
object AdminApiModule {
    @Provides @Singleton
    fun adminApi(retrofit: Retrofit): AdminApi = retrofit.create(AdminApi::class.java)
}
