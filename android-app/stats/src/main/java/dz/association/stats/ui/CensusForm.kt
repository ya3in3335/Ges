package dz.association.stats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dz.association.app.core.util.formatDateTime
import dz.association.stats.R
import dz.association.stats.data.GeoData
import dz.association.stats.data.Meeting
import dz.association.stats.data.PersonInput
import dz.association.stats.data.ProfileDraft
import java.util.Calendar
import java.util.TimeZone

enum class Field { FIRST_NAME, LAST_NAME, WILAYA, COMMUNE, BIRTH_DATE, MARITAL, MEETING }

/** Validation shared by both apps (the server checks everything again). */
object CensusValidation {
    private val NAME = Regex("^[\\p{L}\\p{M}' .-]+$")
    val MARITAL = listOf("single", "married", "divorced", "widowed")

    fun nameValid(s: String) = s.trim().length in 2..60 && NAME.matches(s.trim())

    fun dateValid(day: Int?, month: Int?, year: Int?, now: Calendar = Calendar.getInstance(TimeZone.getTimeZone("Africa/Algiers"))): Boolean {
        if (day == null || month == null || year == null || year < 1900) return false
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { isLenient = false; clear(); set(year, month - 1, day) }
        return runCatching { c.timeInMillis }.isSuccess && c.get(Calendar.DAY_OF_MONTH) == day &&
            (year < now.get(Calendar.YEAR) || (year == now.get(Calendar.YEAR) && (month < now.get(Calendar.MONTH) + 1 || (month == now.get(Calendar.MONTH) + 1 && day <= now.get(Calendar.DAY_OF_MONTH)))))
    }

    fun daysIn(month: Int?, year: Int?): Int {
        if (month == null) return 31
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year ?: 2000, month - 1, 1) }
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    fun birthDate(day: Int, month: Int, year: Int) = "%04d-%02d-%02d".format(java.util.Locale.ROOT, year, month, day)
}

/** The census form's state; survives recomposition, filled from the member's profile when available. */
@Stable
class CensusFormState(requireMeeting: Boolean = true) {
    var firstName by mutableStateOf("")
    var lastName by mutableStateOf("")
    var gender by mutableStateOf<String?>(null)
    var wilaya by mutableStateOf<Int?>(null)
    var commune by mutableStateOf<Int?>(null)
    var day by mutableStateOf<Int?>(null)
    var month by mutableStateOf<Int?>(null)
    var year by mutableStateOf<Int?>(null)
    var marital by mutableStateOf<String?>(null)
    var meetingId by mutableStateOf<String?>(null)
    var showErrors by mutableStateOf(false)
    private val needsMeeting = requireMeeting

    fun prefill(p: ProfileDraft) {
        if (firstName.isBlank()) firstName = p.firstName
        if (lastName.isBlank()) lastName = p.lastName
        if (gender == null) gender = p.gender
        if (wilaya == null) { wilaya = p.wilayaCode; commune = p.communeId }
        if (marital == null) marital = p.maritalStatus
        p.birthDate?.split('-')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 && year == null }?.let { (y, m, d) -> year = y; month = m; day = d }
    }

    fun errors(geo: GeoData?): Set<Field> = buildSet {
        if (!CensusValidation.nameValid(firstName)) add(Field.FIRST_NAME)
        if (!CensusValidation.nameValid(lastName)) add(Field.LAST_NAME)
        if (wilaya == null) add(Field.WILAYA)
        if (commune == null || (geo != null && geo.commune(commune)?.wilaya != wilaya)) add(Field.COMMUNE)
        if (!CensusValidation.dateValid(day, month, year)) add(Field.BIRTH_DATE)
        if (marital !in CensusValidation.MARITAL) add(Field.MARITAL)
        if (needsMeeting && meetingId == null) add(Field.MEETING)
    }

    /** The values to send, or null when something is missing or invalid. */
    fun toInput(geo: GeoData?): PersonInput? {
        if (errors(geo).isNotEmpty()) return null
        return PersonInput(firstName.trim(), lastName.trim(), gender, wilaya!!, commune!!, CensusValidation.birthDate(day!!, month!!, year!!), marital!!)
    }

    fun clearPerson() { firstName = ""; lastName = ""; gender = null; day = null; month = null; year = null; marital = null; showErrors = false }
}

/**
 * "التسجيل في الإحصاء": personal information, place of residence (wilaya → commune), date of birth
 * (day / month / year), family situation and the meeting. Used by the member and the admin apps.
 */
@Composable
fun CensusForm(state: CensusFormState, geo: GeoData, meetings: List<Meeting>?, modifier: Modifier = Modifier, lockMeeting: Boolean = false) {
    val err = if (state.showErrors) state.errors(geo) else emptySet()
    val required = stringResource(R.string.st_err_required)
    val months = stringArrayResource(R.array.st_months)
    val thisYear = Calendar.getInstance(TimeZone.getTimeZone("Africa/Algiers")).get(Calendar.YEAR)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StatsCard {
            SectionTitle(stringResource(R.string.st_sec_personal), Icons.Outlined.Person)
            OutlinedTextField(
                state.firstName, { state.firstName = it.take(60) }, label = { Text(stringResource(R.string.st_first_name)) }, singleLine = true,
                isError = Field.FIRST_NAME in err, supportingText = if (Field.FIRST_NAME in err) ({ Text(stringResource(R.string.st_err_name)) }) else null,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("census_first_name"),
            )
            OutlinedTextField(
                state.lastName, { state.lastName = it.take(60) }, label = { Text(stringResource(R.string.st_last_name)) }, singleLine = true,
                isError = Field.LAST_NAME in err, supportingText = if (Field.LAST_NAME in err) ({ Text(stringResource(R.string.st_err_name)) }) else null,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("census_last_name"),
            )
            Text(stringResource(R.string.st_gender_optional), style = MaterialTheme.typography.labelLarge, color = StatsColors.TextSecondary)
            ChoiceChips(listOf("male", "female"), state.gender, { genderLabel(it) }, { state.gender = if (state.gender == it) null else it })
        }
        StatsCard {
            SectionTitle(stringResource(R.string.st_sec_residence), Icons.Outlined.Place)
            Dropdown(
                stringResource(R.string.st_wilaya), geo.wilayas, geo.wilaya(state.wilaya), { it.label },
                { if (state.wilaya != it.code) { state.wilaya = it.code; state.commune = null } },
                error = if (Field.WILAYA in err) required else null, searchable = true,
            )
            val communes = state.wilaya?.let(geo::communesOf).orEmpty()
            Dropdown(
                stringResource(R.string.st_commune), communes, geo.commune(state.commune)?.takeIf { it.wilaya == state.wilaya }, { it.label },
                { state.commune = it.id }, enabled = state.wilaya != null,
                placeholder = if (state.wilaya == null) stringResource(R.string.st_pick_wilaya_first) else null,
                error = if (Field.COMMUNE in err) required else null, searchable = true,
            )
        }
        StatsCard {
            SectionTitle(stringResource(R.string.st_sec_birth), Icons.Outlined.Cake)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown(stringResource(R.string.st_day), (1..CensusValidation.daysIn(state.month, state.year)).toList(), state.day, { it.toString() }, { state.day = it },
                    Modifier.weight(0.8f), error = if (Field.BIRTH_DATE in err && state.day == null) required else null)
                Dropdown(stringResource(R.string.st_month), (1..12).toList(), state.month, { months[it - 1] }, {
                    state.month = it
                    state.day?.let { d -> if (d > CensusValidation.daysIn(it, state.year)) state.day = null }
                }, Modifier.weight(1.2f), error = if (Field.BIRTH_DATE in err && state.month == null) required else null)
                Dropdown(stringResource(R.string.st_year), (thisYear downTo 1900).toList(), state.year, { it.toString() }, { state.year = it },
                    Modifier.weight(1f), error = if (Field.BIRTH_DATE in err && state.year == null) required else null)
            }
            if (Field.BIRTH_DATE in err && state.day != null && state.month != null && state.year != null) {
                Text(stringResource(R.string.st_err_date), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        StatsCard {
            SectionTitle(stringResource(R.string.st_sec_marital), Icons.Outlined.FamilyRestroom)
            ChoiceChips(CensusValidation.MARITAL, state.marital, { maritalLabel(it) }, { state.marital = it })
            if (Field.MARITAL in err) Text(required, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (meetings != null) StatsCard {
            SectionTitle(stringResource(R.string.st_sec_meeting), Icons.AutoMirrored.Outlined.EventNote)
            if (meetings.isEmpty()) EmptyNote(stringResource(R.string.st_no_open_meetings))
            else Dropdown(
                stringResource(R.string.st_meeting), meetings, meetings.firstOrNull { it.id == state.meetingId },
                { "${it.title} — ${formatDateTime(it.startsAt)}" }, { state.meetingId = it.id }, enabled = !lockMeeting,
                error = if (Field.MEETING in err) required else null,
            )
        }
    }
}
