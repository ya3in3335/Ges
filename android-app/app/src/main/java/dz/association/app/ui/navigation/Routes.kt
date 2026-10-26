package dz.association.app.ui.navigation

import android.net.Uri

object Routes {
    const val INTRO = "intro"
    const val HOME = "home"
    const val ACTIVITIES = "activities"
    const val POLLS = "polls"
    const val VOLUNTEER = "volunteer"
    const val ACCOUNT = "account"
    const val NEWS = "news"
    const val NEWS_DETAIL = "news/{id}"
    const val ACTIVITY_DETAIL = "activity/{id}"
    const val POLL_DETAIL = "poll/{id}"
    const val GALLERY = "gallery"
    const val VIDEO = "video?url={url}"
    const val PHOTO = "photo?url={url}"
    const val DONATIONS = "donations"
    const val ABOUT = "about"
    const val NOTIFICATIONS = "notifications"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT = "forgot"
    const val VERIFY = "verify"
    const val LECTURES = "lectures"
    const val LECTURE_DETAIL = "lecture/{id}"
    const val LECTURE_FORM = "lecture/{id}/register"
    fun lecture(id: String) = "lecture/${Uri.encode(id)}"
    fun lectureForm(id: String) = "lecture/${Uri.encode(id)}/register"
    const val VERIFY_EMAIL = "verify_email?next={next}"
    fun verifyEmail(next: String? = null) = "verify_email?next=${Uri.encode(next ?: "")}"
    const val SETTINGS = "settings"
    const val MY_ACTIVITIES = "my_activities"

    fun news(id: String) = "news/${Uri.encode(id)}"
    fun activity(id: String) = "activity/${Uri.encode(id)}"
    fun poll(id: String) = "poll/${Uri.encode(id)}"
    fun video(url: String) = "video?url=${Uri.encode(url)}"
    fun photo(url: String) = "photo?url=${Uri.encode(url)}"

    val TABS = listOf(HOME, ACTIVITIES, POLLS, VOLUNTEER, ACCOUNT)

    /** Maps notification deep links (app://news/<id>, app://polls/<id>, ...) to routes. Unknown links are ignored. */
    fun fromDeepLink(link: String?): String? {
        if (link == null || !link.startsWith("app://")) return null
        val parts = link.removePrefix("app://").split('/').filter { it.isNotBlank() }
        val id = parts.getOrNull(1)?.takeIf { it.matches(Regex("^[A-Za-z0-9-]{1,64}$")) }
        return when (parts.firstOrNull()) {
            "news" -> id?.let(::news) ?: NEWS
            "activities" -> id?.let(::activity) ?: ACTIVITIES
            "polls" -> id?.let(::poll) ?: POLLS
            "lectures" -> id?.let(::lecture) ?: LECTURES
            "volunteer" -> VOLUNTEER
            "notifications" -> NOTIFICATIONS
            "donations" -> DONATIONS
            else -> null
        }
    }
}
