package dz.association.admin

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dz.association.app.core.util.Connectivity
import dz.association.app.ui.components.ConnectionBanner
import dz.association.app.ui.components.LocalOnline
import javax.inject.Inject
import dz.association.admin.ui.AdminNav
import dz.association.app.ui.theme.AssociationTheme

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var connectivity: Connectivity

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Administrative data (phone numbers, results) must not appear in screenshots or the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val online = connectivity.online.collectAsStateWithLifecycle(initialValue = true)
            AssociationTheme {
                CompositionLocalProvider(LocalOnline provides online) {
                    Box { AdminNav(); ConnectionBanner() }
                }
            }
        }
    }
}
