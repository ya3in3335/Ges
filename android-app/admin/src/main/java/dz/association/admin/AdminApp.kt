package dz.association.admin

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dz.association.app.core.util.AppLocale

@HiltAndroidApp
class AdminApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.ensureDefault()
    }
}
