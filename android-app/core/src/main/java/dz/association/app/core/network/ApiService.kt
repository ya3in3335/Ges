package dz.association.app.core.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    // public content
    @GET("api/home") suspend fun home(): HomeResponse
    @GET("api/settings") suspend fun settings(): SettingsResponse
    @GET("api/donations") suspend fun donations(): DonationsResponse
    @GET("api/news") suspend fun news(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int = 20,
        @Query("category") category: String? = null,
        @Query("q") query: String? = null,
    ): NewsPage
    @GET("api/news/{id}") suspend fun newsDetail(@Path("id") id: String): NewsDetail
    @GET("api/activities") suspend fun activities(@Query("phase") phase: String, @Query("page") page: Int = 1): ActivityPage
    @GET("api/activities/{id}") suspend fun activity(@Path("id") id: String): ActivityDetail
    @GET("api/media") suspend fun media(@Query("kind") kind: String?, @Query("category") category: String?, @Query("page") page: Int): MediaPage
    @GET("api/polls") suspend fun polls(@Query("state") state: String): PollList
    @GET("api/polls/{id}") suspend fun poll(@Path("id") id: String): PollDetail

    // auth
    @POST("api/auth/register") suspend fun register(@Body body: RegisterRequest): AuthResponse
    @POST("api/auth/login") suspend fun login(@Body body: LoginRequest): AuthResponse
    @POST("api/auth/logout") suspend fun logout(@Body body: RefreshRequest): OkResponse
    @POST("api/auth/password/forgot") suspend fun forgotPassword(@Body body: ForgotRequest): OkResponse
    @POST("api/auth/password/reset") suspend fun resetPassword(@Body body: ResetRequest): OkResponse
    @POST("api/auth/email/send") suspend fun sendEmailCode(@Body body: EmptyRequest = EmptyRequest()): EmailSendResponse
    @POST("api/auth/email/verify") suspend fun verifyEmail(@Body body: EmailCodeRequest): OkResponse

    // account
    @GET("api/me") suspend fun me(): UserResponse
    @PATCH("api/me") suspend fun updateMe(@Body body: UpdateMeRequest): UserResponse
    @POST("api/me/password") suspend fun changePassword(@Body body: ChangePasswordRequest): OkResponse
    @HTTP(method = "DELETE", path = "api/me", hasBody = true) suspend fun deleteMe(@Body body: DeleteAccountRequest): OkResponse
    @GET("api/me/notifications") suspend fun myNotifications(): NotificationList
    @GET("api/me/activities") suspend fun myActivities(): ActivityList
    @POST("api/devices") suspend fun registerDevice(@Body body: DeviceRequest): OkResponse
    @POST("api/devices/unregister") suspend fun unregisterDevice(@Body body: DeviceUnregisterRequest): OkResponse

    // verification
    @POST("api/verification/telegram") suspend fun startTelegramVerification(): VerificationStart
    @GET("api/verification/telegram/{id}") suspend fun telegramVerification(@Path("id") id: String): VerificationStatus

    // participation
    @POST("api/polls/{id}/vote") suspend fun vote(@Path("id") id: String, @Body body: VoteRequest): VoteResponse
    @POST("api/activities/{id}/register") suspend fun registerActivity(@Path("id") id: String): OkResponse
    @POST("api/activities/{id}/cancel") suspend fun cancelActivity(@Path("id") id: String): OkResponse
    @GET("api/lectures") suspend fun lectures(@Query("when") whenFilter: String, @Query("page") page: Int): LecturePage
    @GET("api/lectures/{id}") suspend fun lecture(@Path("id") id: String): LectureDetail
    @GET("api/me/lecture-profile") suspend fun lectureProfile(): LectureProfileResponse
    @POST("api/lectures/{id}/register") suspend fun registerLecture(@Path("id") id: String, @Body body: LectureRegisterRequest): OkResponse
    @POST("api/lectures/{id}/cancel") suspend fun cancelLecture(@Path("id") id: String): OkResponse
    @POST("api/volunteers") suspend fun applyVolunteer(@Body body: VolunteerRequest): OkResponse
    @GET("api/volunteers/me") suspend fun myVolunteer(): VolunteerMe
    @DELETE("api/volunteers/me") suspend fun withdrawVolunteer(): OkResponse
}
