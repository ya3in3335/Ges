package dz.association.app

import dz.association.app.core.network.MemberProfile
import dz.association.app.ui.components.LectureLabels
import dz.association.app.ui.lectures.missingFields
import dz.association.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LectureFormTest {
    private val complete = MemberProfile("أحمد", "بن علي", "male", 1995, "31 - وهران", "married", 2, "worker", "أستاذ")

    @Test fun completeFormHasNothingMissing() = assertTrue(missingFields(complete, 2026).isEmpty())

    @Test fun childrenAskedOnlyWhenMarriedBefore() {
        assertEquals(setOf("childrenCount"), missingFields(complete.copy(childrenCount = null), 2026))
        assertTrue(missingFields(complete.copy(maritalStatus = "single", childrenCount = null), 2026).isEmpty())
    }

    @Test fun occupationDetailDependsOnOccupation() {
        assertEquals(setOf("occupationDetail"), missingFields(complete.copy(occupation = "university_student", occupationDetail = null), 2026))
        assertTrue(missingFields(complete.copy(occupation = "unemployed", occupationDetail = null), 2026).isEmpty())
    }

    @Test fun invalidValuesAreReported() {
        val m = missingFields(MemberProfile(firstName = "A", lastName = "<x>", birthYear = 2024), 2026)
        assertTrue(m.containsAll(listOf("firstName", "lastName", "gender", "birthYear", "wilaya", "maritalStatus", "occupation")))
        assertTrue(missingFields(complete.copy(firstName = "مُحَمَّد"), 2026).isEmpty()) // Arabic diacritics are fine
    }

    @Test fun allWilayasAreListed() {
        assertEquals(58, LectureLabels.wilayas.size)
        assertEquals("31 - وهران", LectureLabels.wilayas[30])
        assertEquals("16 - الجزائر", LectureLabels.wilayas[15])
    }

    @Test fun notificationOpensTheLecture() = assertEquals("lecture/abc-1", Routes.fromDeepLink("app://lectures/abc-1"))
}
