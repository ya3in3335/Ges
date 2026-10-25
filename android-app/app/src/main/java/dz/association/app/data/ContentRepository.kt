package dz.association.app.data

import dz.association.app.core.db.CacheDao
import dz.association.app.core.db.CachedPayload
import dz.association.app.core.network.ActivityDetail
import dz.association.app.core.network.ActivityPage
import dz.association.app.core.network.ApiService
import dz.association.app.core.network.AppError
import dz.association.app.core.network.DonationsResponse
import dz.association.app.core.network.HomeResponse
import dz.association.app.core.network.MediaPage
import dz.association.app.core.network.NewsDetail
import dz.association.app.core.network.NewsPage
import dz.association.app.core.network.Result
import dz.association.app.core.network.SettingsResponse
import dz.association.app.core.network.apiCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Data plus whether it came from the offline cache. */
data class Loaded<T>(val data: T, val fromCache: Boolean, val savedAt: Long = 0)

@Singleton
class ContentRepository @Inject constructor(
    private val api: ApiService,
    private val cache: CacheDao,
    private val json: Json,
) {
    /** Network first; on failure falls back to the last cached copy (public content only). */
    private suspend fun <T> cached(key: String, ser: KSerializer<T>, fetch: suspend () -> T): Result<Loaded<T>> =
        when (val r = apiCall(fetch)) {
            is Result.Ok -> {
                runCatching { cache.put(CachedPayload(key, json.encodeToString(ser, r.value), System.currentTimeMillis())) }
                Result.Ok(Loaded(r.value, fromCache = false))
            }
            is Result.Err -> {
                val c = cache.get(key)
                val decoded = c?.let { runCatching { json.decodeFromString(ser, it.json) }.getOrNull() }
                if (decoded != null && r.error is AppError.Network) Result.Ok(Loaded(decoded, fromCache = true, savedAt = c.savedAt)) else r
            }
        }

    suspend fun home() = cached("home", HomeResponse.serializer()) { api.home() }
    suspend fun settings() = cached("settings", SettingsResponse.serializer()) { api.settings() }
    suspend fun donations() = cached("donations", DonationsResponse.serializer()) { api.donations() }

    suspend fun news(page: Int, category: String?, query: String?): Result<Loaded<NewsPage>> {
        // Only the unfiltered first pages are cached; searches always need the network.
        return if (query.isNullOrBlank()) cached("news:$page:${category ?: "all"}", NewsPage.serializer()) { api.news(page, category = category) }
        else apiCall { Loaded(api.news(page, category = category, query = query), false) }
    }

    suspend fun newsDetail(id: String) = cached("news:$id", NewsDetail.serializer()) { api.newsDetail(id) }
    suspend fun activities(phase: String, page: Int) = cached("activities:$phase:$page", ActivityPage.serializer()) { api.activities(phase, page) }
    suspend fun activity(id: String) = cached("activity:$id", ActivityDetail.serializer()) { api.activity(id) }
    suspend fun media(kind: String?, category: String?, page: Int) =
        cached("media:${kind ?: "all"}:${category ?: "all"}:$page", MediaPage.serializer()) { api.media(kind, category, page) }

    suspend fun purgeOld() = cache.purgeOlderThan(System.currentTimeMillis() - 30L * 86_400_000)
}
