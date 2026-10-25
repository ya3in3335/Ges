package dz.association.app.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("app_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class LocalPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val playIntro: Boolean = true,
    val notifyNews: Boolean = true,
    val notifyActivities: Boolean = true,
    val notifyPolls: Boolean = true,
    val notifyVolunteer: Boolean = true,
)

/** Non-sensitive user preferences, cached for offline use. */
@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val playIntro = booleanPreferencesKey("play_intro")
        val news = booleanPreferencesKey("n_news")
        val activities = booleanPreferencesKey("n_activities")
        val polls = booleanPreferencesKey("n_polls")
        val volunteer = booleanPreferencesKey("n_volunteer")
        val pushToken = stringPreferencesKey("push_token")
    }

    val prefs: Flow<LocalPrefs> = context.dataStore.data.map { p ->
        LocalPrefs(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            playIntro = p[K.playIntro] ?: true,
            notifyNews = p[K.news] ?: true,
            notifyActivities = p[K.activities] ?: true,
            notifyPolls = p[K.polls] ?: true,
            notifyVolunteer = p[K.volunteer] ?: true,
        )
    }

    suspend fun current(): LocalPrefs = prefs.first()
    suspend fun setTheme(m: ThemeMode) = context.dataStore.edit { it[K.theme] = m.name }
    suspend fun setPlayIntro(v: Boolean) = context.dataStore.edit { it[K.playIntro] = v }
    suspend fun setNotifications(news: Boolean, activities: Boolean, polls: Boolean, volunteer: Boolean) = context.dataStore.edit {
        it[K.news] = news; it[K.activities] = activities; it[K.polls] = polls; it[K.volunteer] = volunteer
    }
    suspend fun pushToken(): String? = context.dataStore.data.first()[K.pushToken]
    suspend fun setPushToken(t: String?) = context.dataStore.edit { if (t == null) it.remove(K.pushToken) else it[K.pushToken] = t }
}
