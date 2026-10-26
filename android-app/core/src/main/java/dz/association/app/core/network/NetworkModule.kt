package dz.association.app.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dz.association.app.core.BuildConfig
import dz.association.app.core.auth.KeystoreCrypto
import dz.association.app.core.auth.SessionManager
import dz.association.app.core.auth.TokenStore
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides @Singleton
    fun tokenStore(@ApplicationContext ctx: Context): TokenStore = TokenStore(ctx, KeystoreCrypto())

    @Provides @Singleton
    fun apiHosts(@ApplicationContext ctx: Context): ApiHosts = ApiHosts(
        listOf(BuildConfig.API_BASE_URL) + BuildConfig.API_FALLBACK_URLS.split(',').filter { it.isNotBlank() },
        ctx.getSharedPreferences("network", Context.MODE_PRIVATE),
    )

    /** Client without credentials: also used for images, which may come from third-party hosts. */
    @Provides @Singleton @Named("plain")
    fun plainOkHttp(@ApplicationContext ctx: Context, hosts: ApiHosts): OkHttpClient {
        val bootstrap = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        return OkHttpClient.Builder()
            .dns(ResilientDns(bootstrap))
            // Short connect timeout: an unreachable address is abandoned quickly for the next one.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(RetryInterceptor())
            .addInterceptor(FailoverInterceptor(hosts))
            // Respects the API's Cache-Control headers for public content (saves bandwidth).
            .cache(Cache(File(ctx.cacheDir, "http"), 10L * 1024 * 1024))
            .build()
    }

    @Provides @Singleton
    fun okHttp(@Named("plain") base: OkHttpClient, session: SessionManager, json: Json): OkHttpClient {
        val refresher = TokenRefresher(session, BuildConfig.API_BASE_URL, json) { base }
        val builder = base.newBuilder()
            .addInterceptor(AuthInterceptor(session, refresher))
            .authenticator(TokenAuthenticator(refresher))
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                okhttp3.logging.HttpLoggingInterceptor().apply {
                    level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                },
            )
        }
        return builder.build()
    }

    @Provides @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton
    fun api(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)
}
