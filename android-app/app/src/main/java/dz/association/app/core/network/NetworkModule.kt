package dz.association.app.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dz.association.app.BuildConfig
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
    fun okHttp(@ApplicationContext ctx: Context, session: SessionManager, json: Json): OkHttpClient {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Respects the API's Cache-Control headers for public content (saves bandwidth).
            .cache(Cache(File(ctx.cacheDir, "http"), 10L * 1024 * 1024))
            .build()
        val builder = base.newBuilder()
            .addInterceptor(AuthInterceptor(session))
            .authenticator(TokenAuthenticator(session, BuildConfig.API_BASE_URL, json) { base })
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
    fun api(client: OkHttpClient, json: Json): ApiService = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)
}
