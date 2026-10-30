package dz.association.stats.admin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class TableFormatsTest {
    private val table = Table(
        "السجل", "", listOf("رقم", "الاسم", "ملاحظة"),
        listOf(listOf("1", "أحمد", "a,b"), listOf("2", "سارة \"س\"", "=HYPERLINK(\"x\")"), listOf("3", "<&>", "line\nbreak")),
        rtl = true,
    )

    @Test fun csvHasBomQuotingAndNeutralisesFormulas() {
        val bytes = TableFormats.csv(table)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.copyOfRange(0, 3))
        val text = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        val lines = text.split("\r\n")
        assertEquals("رقم,الاسم,ملاحظة", lines[0])
        assertEquals("1,أحمد,\"a,b\"", lines[1])
        assertTrue(lines[2].startsWith("2,\"سارة \"\"س\"\"\","))
        // A cell starting with '=' is written as text, never as a formula.
        assertTrue(lines[2].contains("'=HYPERLINK"))
    }

    @Test fun xlsxIsAValidWorkbookWithEscapedRightToLeftSheet() {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(TableFormats.xlsx(table))).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"), entries.keys)
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        // Every part is well-formed XML.
        entries.values.forEach { parser.parse(ByteArrayInputStream(it.toByteArray())) }
        val sheet = entries["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("rightToLeft=\"1\""))
        assertTrue(sheet.contains("&lt;&amp;&gt;"))
        assertTrue(sheet.contains("<c r=\"C4\""))
        assertFalse(sheet.contains("<&>"))
    }
}
