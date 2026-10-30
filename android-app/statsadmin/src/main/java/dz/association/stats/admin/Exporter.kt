package dz.association.stats.admin

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A table to export: localized headers and text cells. */
data class Table(val title: String, val subtitle: String, val headers: List<String>, val rows: List<List<String>>, val rtl: Boolean)

/** CSV and Excel (.xlsx) writers — plain JVM code, unit-tested. */
object TableFormats {
    /** UTF-8 with BOM (Excel then reads Arabic correctly), RFC 4180 quoting, CRLF lines. */
    fun csv(t: Table): ByteArray {
        fun cell(s: String): String {
            // A leading = + - @ would be run as a formula by spreadsheet programs.
            val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
        }
        val text = (listOf(t.headers) + t.rows).joinToString("\r\n") { r -> r.joinToString(",") { cell(it) } } + "\r\n"
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
    }

    private fun xml(s: String) = buildString {
        s.forEach { c ->
            when {
                c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;"); c == '"' -> append("&quot;")
                c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> Unit // not allowed in XML 1.0
                else -> append(c)
            }
        }
    }

    private fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }; return sb.toString() }

    /** A minimal Office Open XML workbook: one sheet, bold frozen header row, right-to-left when Arabic. */
    fun xlsx(t: Table): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xml(t.title.take(31).replace(Regex("[\\[\\]:*?/\\\\]"), " "))}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="11"/><name val="Arial"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf fontId="0"/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>""")
            val sheet = StringBuilder()
            sheet.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"""")
            if (t.rtl) sheet.append(""" rightToLeft="1"""")
            sheet.append("""><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetData>""")
            (listOf(t.headers) + t.rows).forEachIndexed { r, row ->
                sheet.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, v -> sheet.append("<c r=\"${col(c)}${r + 1}\" t=\"inlineStr\"${if (r == 0) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${xml(v)}</t></is></c>") }
                sheet.append("</row>")
            }
            sheet.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet1.xml", sheet.toString())
        }
        return out.toByteArray()
    }
}

/** Writes PDF (A4 landscape, Arabic shaped and right-to-left) and hands files to the share sheet. */
object Exporter {
    fun pdf(context: Context, t: Table): ByteArray {
        val doc = PdfDocument()
        val w = 842; val h = 595; val margin = 28f
        val font = ResourcesCompat.getFont(context, dz.association.app.core.R.font.ibm_plex_sans_arabic_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, dz.association.app.core.R.font.ibm_plex_sans_arabic_bold) ?: Typeface.DEFAULT_BOLD
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = 8.5f; color = 0xFF1A1A1A.toInt() }
        val head = TextPaint(text).apply { typeface = bold; color = 0xFFFFFFFF.toInt() }
        val titleP = TextPaint(text).apply { typeface = bold; textSize = 15f }
        val subP = TextPaint(text).apply { textSize = 9.5f; color = 0xFF555555.toInt() }
        val fill = Paint().apply { color = 0xFF1F5A44.toInt() }
        val zebra = Paint().apply { color = 0xFFEFF6F2.toInt() }
        val n = t.headers.size
        val colW = (w - 2 * margin) / n
        val dir = if (t.rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
        fun layout(s: String, p: TextPaint) = StaticLayout.Builder.obtain(s, 0, s.length, p, colW.toInt() - 6)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(dir).setMaxLines(3).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        // Columns run right-to-left in Arabic.
        fun x(i: Int) = if (t.rtl) w - margin - (i + 1) * colW else margin + i * colW
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let(doc::finishPage)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            val c = page!!.canvas
            y = margin
            if (pageNo == 1) {
                val tl = StaticLayout.Builder.obtain(t.title, 0, t.title.length, titleP, (w - 2 * margin).toInt()).setTextDirection(dir).build()
                c.save(); c.translate(margin, y); tl.draw(c); c.restore(); y += tl.height + 4
                val sl = StaticLayout.Builder.obtain(t.subtitle, 0, t.subtitle.length, subP, (w - 2 * margin).toInt()).setTextDirection(dir).build()
                c.save(); c.translate(margin, y); sl.draw(c); c.restore(); y += sl.height + 10
            }
            val hl = t.headers.map { layout(it, head) }
            val hh = hl.maxOf { it.height } + 8f
            c.drawRect(margin, y, w - margin, y + hh, fill)
            hl.forEachIndexed { i, l -> c.save(); c.translate(x(i) + 3, y + 4); l.draw(c); c.restore() }
            y += hh
        }
        newPage()
        t.rows.forEachIndexed { r, row ->
            val ls = row.map { layout(it, text) }
            val rh = ls.maxOf { it.height } + 6f
            if (y + rh > h - margin - 14) newPage()
            val c = page!!.canvas
            if (r % 2 == 1) c.drawRect(margin, y, w - margin, y + rh, zebra)
            ls.forEachIndexed { i, l -> c.save(); c.translate(x(i) + 3, y + 3); l.draw(c); c.restore() }
            y += rh
        }
        page?.let { p ->
            p.canvas.drawText("${t.rows.size}", margin, h - 12f, subP)
            doc.finishPage(p)
        }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    /** Saves to the app's private cache and opens the share sheet (Drive, e-mail, WhatsApp, files…). */
    fun share(context: Context, bytes: ByteArray, name: String, mime: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Older exports are removed so personal data does not pile up on the phone.
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val file = File(dir, name).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
