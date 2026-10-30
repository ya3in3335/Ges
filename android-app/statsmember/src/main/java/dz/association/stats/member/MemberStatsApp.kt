package dz.association.stats.member

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import dz.association.app.core.network.Images
import dz.association.app.core.util.AppLocale
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class MemberStatsApp : Application(), ImageLoaderFactory {
    @Inject @Named("plain") lateinit var plainClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        AppLocale.ensureDefault()
    }

    override fun newImageLoader(): ImageLoader = Images.loader(this, plainClient)
}
