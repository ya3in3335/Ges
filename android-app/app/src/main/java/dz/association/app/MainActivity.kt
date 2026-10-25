package dz.association.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dz.association.app.core.prefs.AppPreferences
import dz.association.app.core.prefs.LocalPrefs
import dz.association.app.ui.navigation.AppNavHost
import dz.association.app.ui.theme.AssociationTheme
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var preferences: AppPreferences

    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Restore the normal theme once the starting window has been shown.
        setTheme(R.style.Theme_Association)
        deepLink = intent.getStringExtra(EXTRA_DEEP_LINK)
        // Small, local read; needed to decide the start destination without a flash.
        val initial = runBlocking { preferences.current() }
        // Intro plays on cold start only (not after rotation / process restore) and not when opened from a notification.
        val showIntro = savedInstanceState == null && initial.playIntro && deepLink == null
        setContent {
            val prefs by preferences.prefs.collectAsStateWithLifecycle(initialValue = initial)
            AssociationTheme((prefs as LocalPrefs).theme) {
                AppNavHost(showIntro = showIntro, pendingDeepLink = deepLink, onDeepLinkHandled = { deepLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_DEEP_LINK)?.let { deepLink = it }
    }

    companion object { const val EXTRA_DEEP_LINK = "deep_link" }
}
