package dz.association.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dz.association.app.core.R

/** Labels for the lecture form values, shared by the members' and supervisors' apps. */
object LectureLabels {
    val kinds = listOf("lecture", "meeting", "training")
    val genders = listOf("male", "female")
    val marital = listOf("single", "married", "divorced", "widowed")
    val occupations = listOf("university_student", "pupil", "worker", "unemployed", "retired", "other")

    /** The 58 wilayas of Algeria, numbered as in official codes. */
    val wilayas = listOf(
        "أدرار", "الشلف", "الأغواط", "أم البواقي", "باتنة", "بجاية", "بسكرة", "بشار", "البليدة", "البويرة",
        "تمنراست", "تبسة", "تلمسان", "تيارت", "تيزي وزو", "الجزائر", "الجلفة", "جيجل", "سطيف", "سعيدة",
        "سكيكدة", "سيدي بلعباس", "عنابة", "قالمة", "قسنطينة", "المدية", "مستغانم", "المسيلة", "معسكر", "ورقلة",
        "وهران", "البيض", "إليزي", "برج بوعريريج", "بومرداس", "الطارف", "تندوف", "تيسمسيلت", "الوادي", "خنشلة",
        "سوق أهراس", "تيبازة", "ميلة", "عين الدفلى", "النعامة", "عين تموشنت", "غرداية", "غليزان", "تيميمون", "برج باجي مختار",
        "أولاد جلال", "بني عباس", "عين صالح", "عين قزام", "تقرت", "جانت", "المغير", "المنيعة",
    ).mapIndexed { i, n -> "${(i + 1).toString().padStart(2, '0')} - $n" }

    /** Whether the occupation needs a detail (specialty, school level or profession). */
    fun needsDetail(occupation: String?) = occupation in setOf("university_student", "pupil", "worker", "other")

    @Composable fun kind(k: String) = stringResource(when (k) { "meeting" -> R.string.lec_kind_meeting; "training" -> R.string.lec_kind_training; else -> R.string.lec_kind_lecture })
    @Composable fun gender(g: String?) = when (g) { "male" -> stringResource(R.string.lec_male); "female" -> stringResource(R.string.lec_female); else -> "—" }
    @Composable fun marital(m: String?) = when (m) {
        "single" -> stringResource(R.string.lec_single); "married" -> stringResource(R.string.lec_married)
        "divorced" -> stringResource(R.string.lec_divorced); "widowed" -> stringResource(R.string.lec_widowed); else -> "—"
    }
    @Composable fun occupation(o: String?) = when (o) {
        "university_student" -> stringResource(R.string.lec_occ_student); "pupil" -> stringResource(R.string.lec_occ_pupil)
        "worker" -> stringResource(R.string.lec_occ_worker); "unemployed" -> stringResource(R.string.lec_occ_unemployed)
        "retired" -> stringResource(R.string.lec_occ_retired); "other" -> stringResource(R.string.lec_occ_other); else -> "—"
    }
    @Composable fun detailLabel(o: String?) = stringResource(when (o) {
        "university_student" -> R.string.lec_detail_student; "pupil" -> R.string.lec_detail_pupil
        "worker" -> R.string.lec_detail_worker; else -> R.string.lec_detail_other
    })
}
