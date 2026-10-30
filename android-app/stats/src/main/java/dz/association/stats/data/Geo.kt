package dz.association.stats.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dz.association.app.core.util.AppLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class Wilaya(val code: Int, val ar: String, val fr: String) {
    val label get() = "${code.toString().padStart(2, '0')} - ${if (AppLocale.isFrench) fr else ar}"
}
data class Commune(val id: Int, val wilaya: Int, val ar: String, val fr: String) {
    val label get() = if (AppLocale.isFrench) fr else ar
}

/** Algeria's 58 wilayas and 1,541 communes, bundled (assets/dz_geo.json, same data as the server). */
class GeoData(val wilayas: List<Wilaya>, val communes: List<Commune>) {
    private val byWilaya = communes.groupBy { it.wilaya }
    private val communeById = communes.associateBy { it.id }
    private val wilayaByCode = wilayas.associateBy { it.code }
    fun communesOf(wilaya: Int): List<Commune> = byWilaya[wilaya].orEmpty().sortedBy { it.label }
    fun commune(id: Int?): Commune? = id?.let(communeById::get)
    fun wilaya(code: Int?): Wilaya? = code?.let(wilayaByCode::get)

    companion object {
        fun parse(text: String): GeoData {
            val root = Json.parseToJsonElement(text).jsonObject
            val w = root["w"]!!.jsonArray.map { val a = it.jsonArray; Wilaya(a[0].jsonPrimitive.int, a[1].jsonPrimitive.content, a[2].jsonPrimitive.content) }
            val c = root["c"]!!.jsonArray.map { val a = it.jsonArray; Commune(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, a[2].jsonPrimitive.content, a[3].jsonPrimitive.content) }
            return GeoData(w, c)
        }
    }
}

@Singleton
class Geo @Inject constructor(@ApplicationContext private val context: Context) {
    val data: GeoData by lazy { GeoData.parse(context.assets.open("dz_geo.json").bufferedReader(Charsets.UTF_8).use { it.readText() }) }
}
