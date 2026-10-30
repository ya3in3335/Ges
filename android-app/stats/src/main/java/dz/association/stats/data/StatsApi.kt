package dz.association.stats.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dz.association.app.core.network.OkResponse
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Singleton

/** Census endpoints. Uses the association's Retrofit client: same server, failover, tokens and renewal. */
interface StatsApi {
    @GET("api/me") suspend fun me(): MeResponse
    @GET("api/me/notifications") suspend fun notifications(): dz.association.app.core.network.NotificationList
    @GET("api/stats/access") suspend fun access(): AccessResponse
    @GET("api/stats/sync") suspend fun sync(): SyncResponse

    // members
    @GET("api/stats/meetings") suspend fun openMeetings(): MeetingList
    @POST("api/stats/meetings/{id}/register") suspend fun register(@Path("id") id: String, @Body body: PersonInput): RegistrationResponse
    @GET("api/stats/me/profile") suspend fun profile(): ProfileResponse
    @PUT("api/stats/me/profile") suspend fun saveProfile(@Body body: PersonInput): ProfileResponse
    @GET("api/stats/me/registrations") suspend fun myRegistrations(): RegistrationList
    @GET("api/stats/me/registrations/{id}") suspend fun myRegistration(@Path("id") id: String): RegistrationResponse
    @POST("api/stats/me/registrations/{id}/cancel") suspend fun cancelMine(@Path("id") id: String): RegistrationResponse

    // officers & managers
    @GET("api/stats/admin/dashboard") suspend fun dashboard(@Query("meetingId") meetingId: String? = null): Dashboard
    @GET("api/stats/admin/meetings") suspend fun meetings(): MeetingList
    @GET("api/stats/admin/meetings/{id}") suspend fun meeting(@Path("id") id: String): MeetingResponse
    @POST("api/stats/admin/meetings") suspend fun createMeeting(@Body body: MeetingRequest): IdResponse
    @PATCH("api/stats/admin/meetings/{id}") suspend fun updateMeeting(@Path("id") id: String, @Body body: MeetingPatch): MeetingResponse
    @POST("api/stats/admin/meetings/{id}/close") suspend fun closeMeeting(@Path("id") id: String): CloseResponse
    @GET("api/stats/admin/registrations") suspend fun registrations(
        @Query("meetingId") meetingId: String? = null,
        @Query("q") query: String? = null,
        @Query("wilaya") wilaya: Int? = null,
        @Query("commune") commune: Int? = null,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
        @Query("all") all: Int? = null,
    ): RegistrationList
    @POST("api/stats/admin/registrations") suspend fun staffRegister(@Body body: StaffRegisterRequest): RegistrationResponse
    @GET("api/stats/admin/registrations/{id}") suspend fun registration(@Path("id") id: String): RegistrationResponse
    @PATCH("api/stats/admin/registrations/{id}") suspend fun updateRegistration(@Path("id") id: String, @Body body: RegistrationPatch): RegistrationResponse
    @POST("api/stats/admin/checkin") suspend fun checkin(@Body body: CheckinRequest): RegistrationResponse
    @GET("api/stats/admin/staff") suspend fun staff(): StaffList
    @DELETE("api/stats/admin/staff/{userId}") suspend fun revokeStaff(@Path("userId") userId: String): OkResponse
    @GET("api/stats/admin/audit") suspend fun audit(): AuditList
    @GET("api/stats/admin/users") suspend fun users(
        @Query("q") query: String? = null,
        @Query("role") role: String? = null,
        @Query("staff") staff: Int? = null,
        @Query("page") page: Int = 1,
    ): UserList

    // Census admin app activation, approved in the Telegram bot (same mechanism as the supervisors' app).
    @POST("api/auth/device-requests") suspend fun requestActivation(@Body body: ActivationRequest): ActivationCreated
    @GET("api/auth/device-requests/{id}") suspend fun pollActivation(@Path("id") id: String, @retrofit2.http.Header("x-request-secret") secret: String): ActivationStatus
}

@Module
@InstallIn(SingletonComponent::class)
object StatsModule {
    @Provides @Singleton
    fun statsApi(retrofit: Retrofit): StatsApi = retrofit.create(StatsApi::class.java)
}
