package dz.association.admin

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import dz.association.app.core.network.Images
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import dz.association.app.core.util.AppLocale

@HiltAndroidApp
class AdminApp : Application(), ImageLoaderFactory {
    @Inject @Named("plain") lateinit var plainClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        AppLocale.ensureDefault()
    }

    override fun newImageLoader(): ImageLoader = Images.loader(this, plainClient)
}
