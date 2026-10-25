package dz.association.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dz.association.app.core.push.PushManager
import dz.association.app.core.util.AppLocale
import javax.inject.Inject

@HiltAndroidApp
class AssociationApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var push: PushManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        AppLocale.ensureDefault()
        push.initialize()
    }
}
