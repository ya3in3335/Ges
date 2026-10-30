package dz.association.stats.admin

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.CaptureActivity
import dagger.hilt.android.AndroidEntryPoint
import dz.association.app.core.util.Connectivity
import dz.association.app.ui.components.ConnectionBanner
import dz.association.app.ui.components.LocalOnline
import dz.association.stats.data.SyncPoller
import dz.association.stats.ui.StatsTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var connectivity: Connectivity
    @Inject lateinit var poller: SyncPoller

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Participants' personal data must not appear in screenshots or the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { poller.run() } }
        setContent {
            val online = connectivity.online.collectAsStateWithLifecycle(initialValue = true)
            StatsTheme {
                CompositionLocalProvider(LocalOnline provides online) {
                    Box { AdminNav(); ConnectionBanner() }
                }
            }
        }
    }
}

/** The library's scanner, allowed to follow the phone's orientation. */
class PortraitCaptureActivity : CaptureActivity()
