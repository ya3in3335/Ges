package dz.association.app.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import dz.association.app.BuildConfig
import dz.association.app.R
import dz.association.app.core.network.ApiService
import dz.association.app.core.network.DeviceRequest
import dz.association.app.core.network.DeviceUnregisterRequest
import dz.association.app.core.prefs.AppPreferences
import dz.association.app.core.util.AppLocale
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Cloud Messaging integration. Firebase is configured from BuildConfig values supplied at
 * build time; when they are absent push is simply disabled (in-app notifications still work).
 */
@Singleton
class PushManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val api: ApiService,
) {
    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    fun initialize() {
        createChannel()
        if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
        scheduleSync()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Registers the device token and topic subscriptions; retried by WorkManager when offline. */
    fun scheduleSync() {
        if (!isConfigured) return
        val req = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("push_sync", ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun sync() {
        if (!isConfigured) return
        val fm = FirebaseMessaging.getInstance()
        val token = fm.token.await()
        val p = prefs.current()
        val lang = AppLocale.current()
        val other = if (lang == "ar") "fr" else "ar"
        // Topic per category and language; "important" is always on.
        val wanted = mapOf("news" to p.notifyNews, "activities" to p.notifyActivities, "polls" to p.notifyPolls, "important" to true)
        for ((topic, on) in wanted) {
            if (on) fm.subscribeToTopic("${topic}_$lang").await() else fm.unsubscribeFromTopic("${topic}_$lang").await()
            fm.unsubscribeFromTopic("${topic}_$other").await()
        }
        api.registerDevice(DeviceRequest(token, lang))
        prefs.setPushToken(token)
    }

    /** Called on logout so personal notifications stop reaching this device. */
    suspend fun unlinkDevice() {
        val token = prefs.pushToken() ?: return
        runCatching { api.unregisterDevice(DeviceUnregisterRequest(token)) }
        prefs.setPushToken(null)
        scheduleSync() // re-register anonymously for public topics
    }

    companion object { const val CHANNEL_ID = "association_default" }
}
