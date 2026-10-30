package dz.association.stats

import dz.association.stats.data.GeoData
import dz.association.stats.data.Meeting
import dz.association.stats.data.MeetingList
import dz.association.stats.data.Dashboard
import dz.association.stats.data.RegistrationResponse
import dz.association.stats.ui.CensusFormState
import dz.association.stats.ui.CensusValidation
import dz.association.stats.ui.Field
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class CensusLogicTest {
    private val geo = GeoData.parse(File("src/main/assets/dz_geo.json").readText())
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }

    @Test fun geographyHasThe58WilayasAndTheirCommunes() {
        assertEquals(58, geo.wilayas.size)
        assertEquals((1..58).toList(), geo.wilayas.map { it.code })
        assertEquals(1541, geo.communes.size)
        assertTrue(geo.communes.all { geo.wilaya(it.wilaya) != null })
        assertEquals("وهران", geo.wilaya(31)!!.ar)
        assertEquals(26, geo.communesOf(31).size)
        assertTrue(geo.communesOf(31).any { it.ar == "السانية" })
    }

    @Test fun namesAndDates() {
        assertTrue(CensusValidation.nameValid("بن علي"))
        assertTrue(CensusValidation.nameValid("Ait-Ahmed"))
        assertFalse(CensusValidation.nameValid("ا"))
        assertFalse(CensusValidation.nameValid("Ahmed2"))
        val now = Calendar.getInstance(TimeZone.getTimeZone("Africa/Algiers")).apply { clear(); set(2026, 9, 30) }
        assertTrue(CensusValidation.dateValid(29, 2, 2024, now))
        assertFalse(CensusValidation.dateValid(29, 2, 2023, now))
        assertFalse(CensusValidation.dateValid(31, 4, 2000, now))
        assertFalse(CensusValidation.dateValid(31, 10, 2026, now)) // tomorrow
        assertTrue(CensusValidation.dateValid(30, 10, 2026, now))
        assertFalse(CensusValidation.dateValid(1, 1, 1899, now))
        assertEquals(29, CensusValidation.daysIn(2, 2024))
        assertEquals(28, CensusValidation.daysIn(2, 2023))
        assertEquals("1995-04-02", CensusValidation.birthDate(2, 4, 1995))
    }

    @Test fun formReportsEveryMissingFieldThenProducesTheRequest() {
        val f = CensusFormState()
        assertEquals(Field.entries.toSet(), f.errors(geo))
        assertNull(f.toInput(geo))
        f.firstName = " أحمد "; f.lastName = "بن علي"; f.wilaya = 31; f.commune = geo.communesOf(16).first().id
        f.day = 12; f.month = 4; f.year = 1995; f.marital = "married"; f.meetingId = "m1"
        // A commune from another wilaya is refused.
        assertEquals(setOf(Field.COMMUNE), f.errors(geo))
        f.commune = geo.communesOf(31).first().id
        val input = f.toInput(geo)!!
        assertEquals("أحمد", input.firstName)
        assertEquals("1995-04-12", input.birthDate)
        assertNull(input.gender)
        // The profile form does not ask for a meeting.
        assertFalse(Field.MEETING in CensusFormState(requireMeeting = false).errors(geo))
    }

    @Test fun prefillKeepsWhatWasTyped() {
        val f = CensusFormState()
        f.firstName = "سارة"
        f.prefill(dz.association.stats.data.ProfileDraft("أحمد", "بن علي", "male", 31, 1134, "1990-01-05", "single"))
        assertEquals("سارة", f.firstName)
        assertEquals("بن علي", f.lastName)
        assertEquals(1990, f.year); assertEquals(1, f.month); assertEquals(5, f.day)
    }

    /** The JSON the server sends (backend/src/routes/stats.ts) decodes into the app's models. */
    @Test fun decodesTheServerContract() {
        val meetings = json.decodeFromString(MeetingList.serializer(), """{"items":[{"id":"m1","title":"الاجتماع العام","description":null,"location":"مقر الجمعية","startsAt":1,"checkinOpensAt":0,"checkinClosesAt":5,"lateAfter":2,"registrationEnabled":true,"registrationOpen":true,"status":"open","closedAt":null,"myRegistration":{"id":"r1","status":"cancelled","regNo":3}}]}""")
        val m: Meeting = meetings.items.single()
        assertTrue(m.canJoin)
        val reg = json.decodeFromString(RegistrationResponse.serializer(), """{"item":{"id":"r1","meetingId":"m1","regNo":7,"firstName":"أحمد","lastName":"بن علي","gender":null,"wilayaCode":31,"wilayaAr":"وهران","wilayaFr":"Oran","communeId":1134,"communeAr":"x","communeFr":"y","birthDate":"1995-04-12","maritalStatus":"married","status":"present","checkedInAt":10,"checkinMethod":"qr","source":"member","hasAccount":true,"createdAt":1,"updatedAt":2,"meeting":{"title":"t","startsAt":1,"location":"l","status":"open"},"qr":"AST1.r1.sig"}}""").item
        assertEquals(7, reg.regNo); assertEquals("present", reg.status); assertEquals("AST1.r1.sig", reg.qr)
        val d = json.decodeFromString(Dashboard.serializer(), """{"stats":{"meetingId":null,"totals":{"registrations":4,"registered":2,"present":1,"late":1,"absent":0,"cancelled":0,"attended":2},"attendanceRate":0.5,"gender":{"male":2,"female":2,"unknown":0},"byWilaya":[{"code":31,"ar":"وهران","fr":"Oran","count":4,"attended":2}],"byCommune":[],"ageGroups":[{"key":"18-25","count":1}],"maritalStatus":[{"key":"single","count":1}]},"meetings":[],"generatedAt":5}""")
        assertEquals(0.5, d.stats.attendanceRate!!, 0.0)
        assertEquals(31, d.stats.byWilaya.single().code)
    }
}
