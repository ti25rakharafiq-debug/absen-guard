package com.absenguard.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

data class VaultCourse(
    val key: String,
    val base: String,
    val name: String
)

data class ClassificationResult(
    val suggestedCourseKey: String,
    val suggestedBase: String?,
    val label: String,
    val share: Double,
    val matchedKeywords: List<String>
)

data class PdfExtractionResult(
    val ok: Boolean,
    val text: String = "",
    val pageCount: Int = 0,
    val scanned: Boolean = false,
    val error: String = "",
    val prebuiltAnalysis: TextAnalysisResult? = null
)

data class SectionBlock(
    val heading: String,
    val bullets: List<String>,
    val body: String,
    val page: Int = 1
)

data class FormulaItem(
    val label: String,
    val expression: String,
    val variableNotes: String
)

data class ExtractedTable(
    val title: String,
    val page: Int,
    val headers: List<String>,
    val rows: List<List<String>>
)

data class ExtractedFigure(
    val caption: String,
    val page: Int,
    val imagePath: String
)

data class ProceduralDiagram(
    val title: String,
    val kind: String, // "flow", "bep", "eoq", "normal"
    val nodes: List<String>
)

data class TextAnalysisResult(
    val summary: List<String>,
    val sections: List<SectionBlock>,
    val definitions: List<Pair<String, String>>,
    val formulas: List<Pair<String, String>>,
    val formulaDetails: List<FormulaItem> = emptyList(),
    val tables: List<ExtractedTable> = emptyList(),
    val figures: List<ExtractedFigure> = emptyList(),
    val diagrams: List<ProceduralDiagram> = emptyList(),
    val schemaVersion: Int = 2
)

private data class GlyphToken(
    val ch: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fontSize: Float,
    val isBold: Boolean
)

private data class TextSpan(
    val text: String,
    val x: Float,
    val endX: Float,
    val y: Float,
    val height: Float,
    val fontSize: Float,
    val isBold: Boolean
)

private data class SpatialLine(
    val page: Int,
    val y: Float,
    val height: Float,
    val fontSize: Float,
    val isBold: Boolean,
    val spans: List<TextSpan>
) {
    val fullText: String get() = spans.joinToString(" ") { it.text }.trim()
    val minX: Float get() = spans.minOfOrNull { it.x } ?: 0f
}

private data class PageLayoutData(
    val pageNum: Int,
    val widthPt: Float,
    val heightPt: Float,
    val lines: List<SpatialLine>
)

object KuliahVaultEngine {

    val VAULT_COURSES: List<VaultCourse> = listOf(
        VaultCourse("pemasaran-industri", "Pemasaran Industri", "Pemasaran Industri"),
        VaultCourse("ilmu-bahan-kimia-lingkungan", "Ilmu Bahan dan Kimia Lingkungan", "Ilmu Bahan dan Kimia Lingkungan"),
        VaultCourse("teori-probabilitas", "Teori Probabilitas", "Teori Probabilitas"),
        VaultCourse("analisis-estimasi-biaya", "Analisis dan Estimasi Biaya", "Analisis dan Estimasi Biaya"),
        VaultCourse("algoritma-pemrograman-komputer", "Algoritma & Pemrograman Komputer", "Algoritma & Pemrograman Komputer"),
        VaultCourse("operasional-riset-1", "Operasional Riset 1", "Operasional Riset 1"),
        VaultCourse("analisis-perancangan-kerja", "Analisis dan Perancangan Kerja", "Analisis dan Perancangan Kerja"),
        VaultCourse("jatidiri-bangsa", "Jatidiri Bangsa", "Jatidiri Bangsa"),
        VaultCourse("umum", "Umum", "Umum / Belum Terkategori")
    )

    val COURSE_KEYWORDS: Map<String, List<Pair<String, Double>>> = mapOf(
        "Pemasaran Industri" to listOf(
            "pemasaran" to 2.2, "industri" to 1.2, "segmentasi" to 2.6, "targeting" to 2.4,
            "positioning" to 2.4, "b2b" to 3.0, "konsumen" to 1.6, "pelanggan" to 1.6,
            "produk" to 1.3, "harga" to 1.2, "distribusi" to 1.8, "promosi" to 1.6,
            "marketing mix" to 2.8, "bauran pemasaran" to 2.8, "pasar" to 1.4, "merek" to 1.8,
            "brand" to 1.7, "penjualan" to 1.5, "permintaan" to 1.4, "kompetitor" to 1.8,
            "swot" to 2.2, "nilai pelanggan" to 2.4, "rantai pasok" to 1.8
        ),
        "Ilmu Bahan dan Kimia Lingkungan" to listOf(
            "bahan" to 1.4, "material" to 2.0, "logam" to 2.4, "polimer" to 2.6,
            "keramik" to 2.4, "komposit" to 2.6, "korosi" to 2.8, "kristal" to 2.3,
            "atom" to 2.0, "ikatan kimia" to 2.8, "molekul" to 2.2, "reaksi kimia" to 2.5,
            "limbah" to 2.6, "pencemaran" to 2.6, "lingkungan" to 2.0, "emisi" to 2.3,
            "b3" to 2.8, "ph" to 1.8, "asam" to 1.8, "basa" to 1.8, "oksidasi" to 2.3,
            "reduksi" to 2.2, "diagram fasa" to 3.0, "baja" to 2.2, "paduan" to 2.2,
            "perlakuan panas" to 2.6, "toksik" to 2.4, "bod" to 2.5, "cod" to 2.5, "amdal" to 2.8
        ),
        "Teori Probabilitas" to listOf(
            "probabilitas" to 3.0, "peluang" to 2.6, "kejadian" to 2.0, "ruang sampel" to 3.0,
            "variabel acak" to 3.0, "distribusi" to 2.2, "binomial" to 2.8, "poisson" to 3.0,
            "normal" to 1.8, "eksponensial" to 2.5, "bayes" to 3.0, "bersyarat" to 2.4,
            "ekspektasi" to 2.6, "variansi" to 2.6, "deviasi standar" to 2.5, "permutasi" to 2.7,
            "kombinasi" to 2.5, "faktorial" to 2.4, "stokastik" to 2.8, "fungsi kepadatan" to 2.9,
            "pdf" to 1.4, "cdf" to 2.6, "bernoulli" to 2.9, "hipotesis" to 2.0
        ),
        "Analisis dan Estimasi Biaya" to listOf(
            "biaya" to 2.2, "estimasi biaya" to 3.2, "harga pokok" to 2.8, "hpp" to 3.0,
            "overhead" to 2.8, "biaya tetap" to 2.7, "biaya variabel" to 2.7, "fixed cost" to 2.7,
            "variable cost" to 2.7, "break even" to 3.0, "bep" to 3.0, "titik impas" to 3.0,
            "depresiasi" to 2.8, "penyusutan" to 2.6, "investasi" to 2.0, "npv" to 2.9,
            "irr" to 2.9, "payback" to 2.7, "anggaran" to 2.2, "tenaga kerja langsung" to 2.7,
            "bahan baku" to 2.2, "margin" to 2.0, "laba" to 1.8, "akuntansi biaya" to 2.8,
            "activity based costing" to 3.0, "abc" to 1.6
        ),
        "Algoritma & Pemrograman Komputer" to listOf(
            "algoritma" to 3.0, "pemrograman" to 2.8, "program" to 2.0, "kode" to 1.8,
            "variabel" to 1.8, "tipe data" to 2.6, "integer" to 2.5, "float" to 2.4,
            "string" to 2.4, "boolean" to 2.6, "array" to 2.7, "perulangan" to 2.6,
            "loop" to 2.5, "for" to 1.4, "while" to 2.0, "percabangan" to 2.6,
            "if else" to 2.6, "fungsi" to 1.8, "prosedur" to 2.2, "rekursi" to 2.8,
            "flowchart" to 2.8, "pseudocode" to 3.0, "python" to 2.8, "java" to 2.4,
            "c++" to 2.7, "kompilasi" to 2.4, "debugging" to 2.6, "struktur data" to 2.7
        ),
        "Operasional Riset 1" to listOf(
            "operasional riset" to 3.2, "riset operasi" to 3.2, "pemrograman linier" to 3.0,
            "linear programming" to 3.0, "simpleks" to 3.2, "simplex" to 3.2, "fungsi tujuan" to 2.9,
            "kendala" to 2.5, "constraint" to 2.6, "maksimasi" to 2.7, "minimasi" to 2.7,
            "dualitas" to 2.9, "sensitivitas" to 2.6, "transportasi" to 2.6, "penugasan" to 2.6,
            "assignment" to 2.4, "northwest corner" to 3.0, "stepping stone" to 3.0,
            "vam" to 2.8, "big m" to 2.9, "two phase" to 2.8, "optimal" to 2.0,
            "solusi layak" to 2.8, "variabel slack" to 3.0, "shadow price" to 3.0
        ),
        "Analisis dan Perancangan Kerja" to listOf(
            "perancangan kerja" to 3.2, "pengukuran kerja" to 3.0, "studi waktu" to 3.0,
            "time study" to 3.0, "studi gerakan" to 3.0, "motion study" to 3.0, "ergonomi" to 3.0,
            "antropometri" to 3.1, "peta proses" to 2.9, "opc" to 2.8, "fpc" to 2.8,
            "peta tangan" to 2.9, "therblig" to 3.2, "waktu normal" to 3.0, "waktu baku" to 3.1,
            "waktu siklus" to 3.0, "performance rating" to 3.0, "westinghouse" to 3.1,
            "allowance" to 2.9, "kelonggaran" to 2.8, "work sampling" to 3.0, "kelelahan" to 2.3,
            "stasiun kerja" to 2.7, "postur" to 2.4, "biomekanika" to 2.9, "beban kerja" to 2.7
        ),
        "Jatidiri Bangsa" to listOf(
            "jatidiri" to 3.2, "bangsa" to 2.2, "pancasila" to 3.2, "uud 1945" to 3.0,
            "nkri" to 3.0, "bhinneka tunggal ika" to 3.1, "wawasan nusantara" to 3.1,
            "ketahanan nasional" to 3.0, "karakter" to 2.0, "identitas nasional" to 3.0,
            "nasionalisme" to 2.8, "patriotisme" to 2.8, "budaya" to 1.8, "kearifan lokal" to 2.7,
            "karawang" to 2.4, "kebangsaan" to 2.6, "konstitusi" to 2.6, "ideologi" to 2.6,
            "demokrasi" to 2.3, "hak asasi" to 2.3, "bela negara" to 2.9, "gotong royong" to 2.6
        )
    )

    private val FORMULA_VARIABLE_HINTS: Map<String, String> = mapOf(
        "wb" to "Wb = Waktu Baku",
        "wn" to "Wn = Waktu Normal",
        "ws" to "Ws = Waktu Siklus",
        "rf" to "RF = Performance Rating Factor",
        "bep" to "BEP = Break-Even Point (Titik Impas)",
        "fc" to "FC = Fixed Cost (Biaya Tetap)",
        "vc" to "VC = Variable Cost (Biaya Variabel)",
        "hpp" to "HPP = Harga Pokok Produksi",
        "npv" to "NPV = Net Present Value",
        "irr" to "IRR = Internal Rate of Return",
        "eoq" to "EOQ = Economic Order Quantity",
        "rop" to "ROP = Reorder Point",
        "oee" to "OEE = Overall Equipment Effectiveness"
    )

    fun courseByKey(key: String): VaultCourse =
        VAULT_COURSES.firstOrNull { it.key == key } ?: VAULT_COURSES.last()

    fun mapScheduleNameToCourseKey(courseName: String): String {
        val clean = courseName
            .replace(Regex("\\s*\\([TP]\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase()
        return VAULT_COURSES.firstOrNull { it.base.lowercase() == clean }?.key ?: "umum"
    }

    fun classifyText(rawText: String): ClassificationResult {
        val text = rawText.lowercase()
        if (text.trim().length < 25) {
            return ClassificationResult("umum", null, "Umum (Teks terlalu singkat)", 0.0, emptyList())
        }

        val scores = mutableMapOf<String, Double>()
        val matchedMap = mutableMapOf<String, MutableList<String>>()
        for ((courseBase, keywords) in COURSE_KEYWORDS) {
            var score = 0.0
            val matched = mutableListOf<String>()
            for ((kw, weight) in keywords) {
                val pattern = Regex("(?<![a-z0-9])${Regex.escape(kw)}(?![a-z0-9])")
                val count = pattern.findAll(text).count()
                if (count > 0) {
                    score += weight * (1.0 + kotlin.math.ln(count.toDouble()).coerceAtMost(2.2))
                    if (matched.size < 6) matched.add(kw)
                }
            }
            scores[courseBase] = score
            matchedMap[courseBase] = matched
        }

        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull()
        val total = ranked.sumOf { it.value }
        if (top == null || top.value < 4.5 || total <= 0.0) {
            return ClassificationResult("umum", null, "Umum (Skor kata kunci rendah)", 0.0, emptyList())
        }

        val share = top.value / total
        val matched = matchedMap[top.key].orEmpty()
        val confident = top.value >= 7.0 && share >= 0.34
        return if (confident) {
            val key = VAULT_COURSES.firstOrNull { it.base == top.key }?.key ?: "umum"
            ClassificationResult(key, top.key, top.key, share, matched)
        } else {
            ClassificationResult("umum", top.key, "Umum (Saran: ${top.key})", share, matched)
        }
    }

    // ==================== NASA-GRADE SPATIAL PDF EXTRACTOR ====================

    fun extractPdfInfo(context: Context, file: File): PdfExtractionResult {
        return try {
            PDFBoxResourceLoader.init(context.applicationContext)
            var pageCount = 0
            val pageLayouts = mutableListOf<PageLayoutData>()

            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) {
                    return PdfExtractionResult(ok = false, error = "PDF terkunci password.")
                }
                pageCount = doc.numberOfPages
                for (p in 1..pageCount) {
                    val extractor = SpatialPageExtractor(p)
                    extractor.startPage = p
                    extractor.endPage = p
                    extractor.getText(doc)
                    pageLayouts.add(extractor.buildPageLayout())
                }
            }

            val totalChars = pageLayouts.sumOf { pl -> pl.lines.sumOf { it.fullText.length } }
            val tables = reconstructTablesFromLayouts(pageLayouts)
            val figures = extractFiguresFromPdf(context, file, pageLayouts)

            if (totalChars >= 80) {
                val structured = buildStructuredAnalysisFromLayouts(pageLayouts, tables, figures)
                val fullCleanText = pageLayouts.joinToString("\n\n") { pl ->
                    "--- Halaman ${pl.pageNum} ---\n" + pl.lines.joinToString("\n") { it.fullText }
                }
                return PdfExtractionResult(
                    ok = true,
                    text = fullCleanText,
                    pageCount = pageCount,
                    scanned = false,
                    prebuiltAnalysis = structured
                )
            }

            // Fallback OCR untuk PDF hasil scan (gambar tanpa layer teks)
            val ocrBuilder = StringBuilder()
            val ocrFigures = mutableListOf<ExtractedFigure>()
            val figDir = File(context.filesDir, "kuliahvault/figures").apply { mkdirs() }
            val stamp = System.currentTimeMillis()

            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val maxPages = renderer.pageCount.coerceAtMost(20)
            for (i in 0 until maxPages) {
                val page = renderer.openPage(i)
                val scale = 2.0f
                val bmp = Bitmap.createBitmap(
                    (page.width * scale).toInt(),
                    (page.height * scale).toInt(),
                    Bitmap.Config.ARGB_8888
                )
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val pageText = recognizeBitmapSync(bmp)
                if (pageText.isNotBlank()) {
                    ocrBuilder.append("\n--- Halaman ${i + 1} ---\n").append(pageText).append("\n")
                }

                // Simpan halaman scan sebagai visual halaman agar tetap bisa dilihat
                if (i < 8) {
                    val outImg = File(figDir, "scan_${stamp}_p${i + 1}.png")
                    FileOutputStream(outImg).use { fos -> bmp.compress(Bitmap.CompressFormat.PNG, 88, fos) }
                    ocrFigures.add(ExtractedFigure("Halaman Scan #${i + 1}", i + 1, outImg.absolutePath))
                }
                bmp.recycle()
            }
            renderer.close()
            pfd.close()

            val finalText = ocrBuilder.toString().trim()
            if (finalText.length < 20) {
                PdfExtractionResult(ok = false, pageCount = pageCount, error = "PDF kosong atau tulisan tidak terbaca.")
            } else {
                val baseAnalysis = analyzeText(finalText).copy(figures = ocrFigures)
                PdfExtractionResult(
                    ok = true,
                    text = finalText,
                    pageCount = pageCount,
                    scanned = true,
                    prebuiltAnalysis = baseAnalysis
                )
            }
        } catch (e: Exception) {
            PdfExtractionResult(ok = false, error = "Gagal memproses PDF: ${e.message}")
        }
    }

    private class SpatialPageExtractor(private val pageNum: Int) : PDFTextStripper() {
        private val tokens = mutableListOf<GlyphToken>()
        private var pageW = 595f
        private var pageH = 842f

        init {
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (tp in textPositions) {
                val ch = tp.unicode ?: continue
                if (ch.isEmpty() || ch[0].code < 32) continue
                try {
                    currentPage?.cropBox?.let { box ->
                        if (box.width > 50f) pageW = box.width
                        if (box.height > 50f) pageH = box.height
                    }
                } catch (_: Exception) {
                }
                val fontName = tp.font?.name.orEmpty().lowercase()
                val isBold = fontName.contains("bold") || fontName.contains("black") || fontName.contains("heavy") || fontName.contains("demi")
                val fs = if (tp.fontSizeInPt > 1f) tp.fontSizeInPt else tp.heightDir.coerceAtLeast(8f)
                tokens.add(
                    GlyphToken(
                        ch = ch,
                        x = tp.xDirAdj,
                        y = tp.yDirAdj,
                        width = tp.widthDirAdj.coerceAtLeast(1f),
                        height = tp.heightDir.coerceAtLeast(6f),
                        fontSize = fs,
                        isBold = isBold
                    )
                )
            }
        }

        fun buildPageLayout(): PageLayoutData {
            if (tokens.isEmpty()) return PageLayoutData(pageNum, pageW, pageH, emptyList())

            val sortedByY = tokens.sortedWith(compareBy({ it.y }, { it.x }))
            val rawRows = mutableListOf<MutableList<GlyphToken>>()

            for (tok in sortedByY) {
                val lastRow = rawRows.lastOrNull()
                if (lastRow == null) {
                    rawRows.add(mutableListOf(tok))
                } else {
                    val refY = lastRow.first().y
                    val tol = max(3.6f, lastRow.first().fontSize * 0.45f)
                    if (abs(tok.y - refY) <= tol) {
                        lastRow.add(tok)
                    } else {
                        rawRows.add(mutableListOf(tok))
                    }
                }
            }

            val spatialLines = mutableListOf<SpatialLine>()
            for (row in rawRows) {
                val ordered = row.sortedBy { it.x }
                val spans = mutableListOf<TextSpan>()
                val sb = StringBuilder()
                var spanStartX = ordered.first().x
                var prevEndX = ordered.first().x
                var spanY = ordered.first().y
                var maxH = ordered.first().height
                var maxFs = ordered.first().fontSize
                var boldCount = 0
                var charCount = 0

                for (i in ordered.indices) {
                    val t = ordered[i]
                    if (i == 0) {
                        sb.append(t.ch)
                        spanStartX = t.x
                        prevEndX = t.x + t.width
                        spanY = t.y
                        maxH = t.height
                        maxFs = t.fontSize
                        if (t.isBold) boldCount++
                        charCount++
                    } else {
                        val gap = t.x - prevEndX
                        val colGapThreshold = max(13f, t.fontSize * 1.15f)
                        val wordSpaceThreshold = max(1.8f, t.fontSize * 0.22f)

                        if (gap >= colGapThreshold && sb.toString().trim().isNotEmpty()) {
                            spans.add(
                                TextSpan(
                                    text = sb.toString().trim(),
                                    x = spanStartX,
                                    endX = prevEndX,
                                    y = spanY,
                                    height = maxH,
                                    fontSize = maxFs,
                                    isBold = boldCount > charCount / 2
                                )
                            )
                            sb.clear()
                            sb.append(t.ch)
                            spanStartX = t.x
                            prevEndX = t.x + t.width
                            spanY = t.y
                            maxH = t.height
                            maxFs = t.fontSize
                            boldCount = if (t.isBold) 1 else 0
                            charCount = 1
                        } else {
                            if (gap >= wordSpaceThreshold && sb.isNotEmpty() && sb.last() != ' ') {
                                sb.append(' ')
                            }
                            sb.append(t.ch)
                            prevEndX = max(prevEndX, t.x + t.width)
                            maxH = max(maxH, t.height)
                            maxFs = max(maxFs, t.fontSize)
                            if (t.isBold) boldCount++
                            charCount++
                        }
                    }
                }
                if (sb.toString().trim().isNotEmpty()) {
                    spans.add(
                        TextSpan(
                            text = sb.toString().trim(),
                            x = spanStartX,
                            endX = prevEndX,
                            y = spanY,
                            height = maxH,
                            fontSize = maxFs,
                            isBold = boldCount > charCount / 2
                        )
                    )
                }

                if (spans.isNotEmpty()) {
                    val lineY = spans.minOf { it.y }
                    val fullStr = spans.joinToString(" ") { it.text }.trim()
                    // Abaikan header/footer running halaman
                    val isMarginNoise = (lineY < pageH * 0.055f || lineY > pageH * 0.945f) && fullStr.length < 55
                    if (!isMarginNoise) {
                        spatialLines.add(
                            SpatialLine(
                                page = pageNum,
                                y = lineY,
                                height = spans.maxOf { it.height },
                                fontSize = spans.maxOf { it.fontSize },
                                isBold = spans.any { it.isBold },
                                spans = spans
                            )
                        )
                    }
                }
            }
            return PageLayoutData(pageNum, pageW, pageH, spatialLines)
        }
    }

    /**
     * Rekonstruksi Tabel 2D sejati dari koordinat X-Y setiap span di halaman PDF.
     */
    private fun reconstructTablesFromLayouts(layouts: List<PageLayoutData>): List<ExtractedTable> {
        val allTables = mutableListOf<ExtractedTable>()
        var tableCounter = 1

        for (pl in layouts) {
            val lines = pl.lines
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                // Baris kandidat tabel punya minimal 2 kolom terpisah dengan sel pertama bukan sekadar bullet
                val firstColText = line.spans.firstOrNull()?.text.orEmpty()
                val isBulletPrefix = firstColText in setOf("•", "-", "*", "▪", "o", "○", "►") ||
                    firstColText.matches(Regex("^\\d{1,2}[.)]$"))

                if (line.spans.size >= 2 && !isBulletPrefix) {
                    val candidateGroup = mutableListOf<SpatialLine>()
                    candidateGroup.add(line)
                    var j = i + 1
                    while (j < lines.size) {
                        val next = lines[j]
                        val dy = next.y - candidateGroup.last().y
                        if (dy > line.height * 3.2f) break
                        if (next.spans.size >= 2) {
                            candidateGroup.add(next)
                            j++
                        } else if (next.spans.size == 1 && dy <= next.height * 1.8f && j + 1 < lines.size && lines[j + 1].spans.size >= 2) {
                            // Baris lanjutan sel yang turun baris di tengah tabel
                            candidateGroup.add(next)
                            j++
                        } else {
                            break
                        }
                    }

                    val multiColLines = candidateGroup.filter { it.spans.size >= 2 }
                    if (multiColLines.size >= 2) {
                        // Bangun cluster batas X kolom
                        val colStarts = mutableListOf<Float>()
                        for (row in multiColLines) {
                            for (sp in row.spans) {
                                val existingIdx = colStarts.indexOfFirst { abs(it - sp.x) <= 22f }
                                if (existingIdx == -1) {
                                    colStarts.add(sp.x)
                                } else {
                                    colStarts[existingIdx] = min(colStarts[existingIdx], sp.x)
                                }
                            }
                        }
                        colStarts.sort()

                        if (colStarts.size in 2..9) {
                            val grid = mutableListOf<MutableList<String>>()
                            for (row in candidateGroup) {
                                if (row.spans.size == 1 && grid.isNotEmpty()) {
                                    // Gabungkan teks lanjutan ke kolom yang sesuai pada baris sebelumnya
                                    val sp = row.spans.first()
                                    val colIdx = findClosestColumn(sp.x, colStarts)
                                    val prevRow = grid.last()
                                    prevRow[colIdx] = (prevRow[colIdx] + " " + sp.text).trim()
                                } else {
                                    val cells = MutableList(colStarts.size) { "" }
                                    for (sp in row.spans) {
                                        val colIdx = findClosestColumn(sp.x, colStarts)
                                        cells[colIdx] = if (cells[colIdx].isEmpty()) sp.text else "${cells[colIdx]} ${sp.text}"
                                    }
                                    grid.add(cells)
                                }
                            }

                            // Hapus kolom yang benar-benar kosong di semua baris
                            val nonEmptyCols = (0 until colStarts.size).filter { c -> grid.any { it[c].isNotBlank() } }
                            if (nonEmptyCols.size >= 2 && grid.size >= 2) {
                                val cleanedGrid = grid.map { r -> nonEmptyCols.map { c -> r[c].ifBlank { "-" } } }
                                val captionAbove = (i - 1 downTo max(0, i - 2))
                                    .map { lines[it].fullText }
                                    .firstOrNull { it.matches(Regex("^(Tabel|Table)\\s+\\d+.*", RegexOption.IGNORE_CASE)) }
                                val title = captionAbove ?: "Tabel #$tableCounter (Halaman ${pl.pageNum})"
                                allTables.add(
                                    ExtractedTable(
                                        title = title,
                                        page = pl.pageNum,
                                        headers = cleanedGrid.first(),
                                        rows = cleanedGrid.drop(1)
                                    )
                                )
                                tableCounter++
                                i = j
                                continue
                            }
                        }
                    }
                }
                i++
            }
        }
        return allTables
    }

    private fun findClosestColumn(x: Float, colStarts: List<Float>): Int {
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for (idx in colStarts.indices) {
            val d = abs(x - colStarts[idx])
            if (d < bestDist) {
                bestDist = d
                bestIdx = idx
            }
        }
        return bestIdx
    }

    /**
     * Memotong dan mengekstrak gambar, grafik, diagram, dan bagan asli dari setiap halaman PDF.
     */
    private fun extractFiguresFromPdf(
        context: Context,
        file: File,
        layouts: List<PageLayoutData>
    ): List<ExtractedFigure> {
        val figures = mutableListOf<ExtractedFigure>()
        val figDir = File(context.filesDir, "kuliahvault/figures").apply { mkdirs() }
        val stamp = System.currentTimeMillis()

        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val maxPages = renderer.pageCount.coerceAtMost(24)

            for (pageIdx in 0 until maxPages) {
                val layout = layouts.getOrNull(pageIdx) ?: continue
                val page = renderer.openPage(pageIdx)
                val targetW = 900
                val scale = targetW.toFloat() / page.width.coerceAtLeast(1)
                val targetH = (page.height * scale).toInt().coerceAtLeast(1)

                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // Buat mask salinan di mana seluruh area teks ditutup putih agar tersisa hanya area gambar/grafik
                val maskBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
                val maskCanvas = Canvas(maskBmp)
                val whitePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }

                // Tutup margin tepi halaman
                maskCanvas.drawRect(0f, 0f, targetW.toFloat(), targetH * 0.05f, whitePaint)
                maskCanvas.drawRect(0f, targetH * 0.95f, targetW.toFloat(), targetH.toFloat(), whitePaint)

                val scaleX = targetW.toFloat() / layout.widthPt.coerceAtLeast(1f)
                val scaleY = targetH.toFloat() / layout.heightPt.coerceAtLeast(1f)

                for (ln in layout.lines) {
                    for (sp in ln.spans) {
                        val left = (sp.x * scaleX - 6f).coerceAtLeast(0f)
                        val right = (sp.endX * scaleX + 6f).coerceAtMost(targetW.toFloat())
                        val top = ((sp.y - sp.height * 1.15f) * scaleY - 4f).coerceAtLeast(0f)
                        val bottom = ((sp.y + sp.height * 0.35f) * scaleY + 4f).coerceAtMost(targetH.toFloat())
                        maskCanvas.drawRect(left, top, right, bottom, whitePaint)
                    }
                }

                // Analisis grid 10x10 piksel untuk menemukan klaster grafis/gambar/kurva
                val cell = 10
                val gw = targetW / cell
                val gh = targetH / cell
                val active = Array(gh) { BooleanArray(gw) }
                val pixels = IntArray(targetW * targetH)
                maskBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                maskBmp.recycle()

                for (gy in 0 until gh) {
                    for (gx in 0 until gw) {
                        var inkCount = 0
                        val startY = gy * cell
                        val startX = gx * cell
                        for (dy in 0 until cell step 2) {
                            val rowOff = (startY + dy) * targetW
                            for (dx in 0 until cell step 2) {
                                val c = pixels[rowOff + startX + dx]
                                val r = (c shr 16) and 0xFF
                                val g = (c shr 8) and 0xFF
                                val b = c and 0xFF
                                if (r < 238 || g < 238 || b < 238) inkCount++
                            }
                        }
                        if (inkCount >= 3) active[gy][gx] = true
                    }
                }

                val visited = Array(gh) { BooleanArray(gw) }
                val captionLines = layout.lines.filter {
                    it.fullText.matches(Regex("^(Gambar|Figure|Fig\\.|Grafik|Diagram|Bagan|Kurva)\\s*\\d*.*", RegexOption.IGNORE_CASE))
                }

                var figIdxOnPage = 1
                for (sy in 0 until gh) {
                    for (sx in 0 until gw) {
                        if (!active[sy][sx] || visited[sy][sx]) continue
                        var minGx = sx
                        var maxGx = sx
                        var minGy = sy
                        var maxGy = sy
                        var cellArea = 0
                        val queue = ArrayDeque<Pair<Int, Int>>()
                        queue.add(sx to sy)
                        visited[sy][sx] = true

                        while (queue.isNotEmpty()) {
                            val (cx, cy) = queue.removeFirst()
                            cellArea++
                            if (cx < minGx) minGx = cx
                            if (cx > maxGx) maxGx = cx
                            if (cy < minGy) minGy = cy
                            if (cy > maxGy) maxGy = cy

                            // Jembatani celah hingga 2 sel (20px) agar garis sumbu & panah tetap menyatu
                            for (ny in (cy - 2)..(cy + 2)) {
                                for (nx in (cx - 2)..(cx + 2)) {
                                    if (ny in 0 until gh && nx in 0 until gw && !visited[ny][nx] && active[ny][nx]) {
                                        visited[ny][nx] = true
                                        queue.add(nx to ny)
                                    }
                                }
                            }
                        }

                        val boxW = (maxGx - minGx + 1) * cell
                        val boxH = (maxGy - minGy + 1) * cell
                        // Filter ukuran minimal agar garis pembatas tipis tidak ikut terpotong
                        if (boxW >= 150 && boxH >= 95 && cellArea >= 24) {
                            val cropLeft = (minGx * cell - 22).coerceAtLeast(0)
                            val cropTop = (minGy * cell - 22).coerceAtLeast(0)
                            val cropRight = ((maxGx + 1) * cell + 22).coerceAtMost(targetW)
                            val cropBottom = ((maxGy + 1) * cell + 22).coerceAtMost(targetH)

                            val cropped = Bitmap.createBitmap(
                                bmp,
                                cropLeft,
                                cropTop,
                                (cropRight - cropLeft).coerceAtLeast(1),
                                (cropBottom - cropTop).coerceAtLeast(1)
                            )
                            val outFile = File(figDir, "fig_${stamp}_p${pageIdx + 1}_$figIdxOnPage.png")
                            FileOutputStream(outFile).use { fos -> cropped.compress(Bitmap.CompressFormat.PNG, 92, fos) }
                            cropped.recycle()

                            val matchedCap = captionLines.minByOrNull { cap ->
                                val capYpx = cap.y * scaleY
                                min(abs(capYpx - cropBottom), abs(capYpx - cropTop))
                            }?.fullText

                            val caption = if (!matchedCap.isNullOrBlank() && matchedCap.length <= 120) {
                                matchedCap
                            } else {
                                "Gambar / Visual #${figures.size + 1} (Halaman ${pageIdx + 1})"
                            }

                            figures.add(
                                ExtractedFigure(
                                    caption = caption,
                                    page = pageIdx + 1,
                                    imagePath = outFile.absolutePath
                                )
                            )
                            figIdxOnPage++
                        }
                    }
                }
                bmp.recycle()
            }
            renderer.close()
            pfd.close()
        } catch (_: Exception) {
        }
        return figures
    }

    /**
     * Membangun struktur bab, penyambungan paragraf tipografis, tabel, gambar, rumus, & diagram vektor.
     */
    private fun buildStructuredAnalysisFromLayouts(
        layouts: List<PageLayoutData>,
        tables: List<ExtractedTable>,
        figures: List<ExtractedFigure>
    ): TextAnalysisResult {
        val allFontSizes = layouts.flatMap { it.lines.map { ln -> ln.fontSize } }.sorted()
        val medianFs = if (allFontSizes.isNotEmpty()) allFontSizes[allFontSizes.size / 2] else 11f

        val sections = mutableListOf<SectionBlock>()
        var currentHeading = "Pendahuluan & Ringkasan Materi"
        var currentPage = 1
        val currentBullets = mutableListOf<String>()
        val paragraphLines = mutableListOf<String>()
        var prevLineY = -1f
        var prevLineX = -1f

        fun flushParagraph() {
            if (paragraphLines.isNotEmpty()) {
                val stitched = stitchLinesIntoCleanParagraph(paragraphLines)
                if (stitched.isNotBlank()) {
                    currentBullets.add(stitched)
                }
                paragraphLines.clear()
            }
        }

        fun flushSection() {
            flushParagraph()
            if (currentBullets.isNotEmpty()) {
                sections.add(
                    SectionBlock(
                        heading = currentHeading,
                        bullets = currentBullets.toList(),
                        body = "",
                        page = currentPage
                    )
                )
                currentBullets.clear()
            }
        }

        for (pl in layouts) {
            prevLineY = -1f
            for (ln in pl.lines) {
                val txt = ln.fullText.trim()
                if (txt.isEmpty()) continue

                val isTypographicHeading = (ln.fontSize >= medianFs * 1.16f && txt.length in 3..115) ||
                    (ln.isBold && txt.length in 4..95 && !txt.endsWith(".") && !txt.endsWith(",") && ln.spans.size == 1)
                val isRegexHeading = txt.matches(Regex("^(BAB|CHAPTER|MODUL|PERTEMUAN|BAGIAN)\\s+[IVX0-9]+.*", RegexOption.IGNORE_CASE)) ||
                    txt.matches(Regex("^\\d+(\\.\\d+){0,2}\\s+[A-Z][A-Za-z0-9\\s,&()/-]{3,85}$"))

                if (isTypographicHeading || isRegexHeading) {
                    flushSection()
                    currentHeading = txt
                    currentPage = pl.pageNum
                    prevLineY = ln.y
                    prevLineX = ln.minX
                    continue
                }

                val isBullet = txt.matches(Regex("^([•\\-*▪◦►]|\\d{1,2}[.)]|[a-zA-Z][.)])\\s+.*"))
                if (isBullet) {
                    flushParagraph()
                    val cleanBullet = txt
                        .replace(Regex("^[•\\-*▪◦►]\\s*"), "• ")
                        .replace(Regex("\\s{2,}"), " ")
                    currentBullets.add(cleanBullet)
                    prevLineY = ln.y
                    prevLineX = ln.minX
                    continue
                }

                val verticalJump = if (prevLineY >= 0f) (ln.y - prevLineY) else 0f
                val indentShift = if (prevLineX >= 0f) abs(ln.minX - prevLineX) else 0f
                if (paragraphLines.isNotEmpty() && (verticalJump > ln.height * 1.85f || indentShift > 28f)) {
                    flushParagraph()
                }
                paragraphLines.add(txt)
                prevLineY = ln.y
                prevLineX = ln.minX
            }
        }
        flushSection()

        val rawCombined = layouts.joinToString("\n") { pl -> pl.lines.joinToString("\n") { it.fullText } }
        val baseTextAnalysis = analyzeText(rawCombined)
        val diagrams = generateProceduralDiagrams(rawCombined, sections)

        return baseTextAnalysis.copy(
            sections = if (sections.isNotEmpty()) sections else baseTextAnalysis.sections,
            tables = if (tables.isNotEmpty()) tables else baseTextAnalysis.tables,
            figures = figures,
            diagrams = diagrams,
            schemaVersion = 2
        )
    }

    private fun stitchLinesIntoCleanParagraph(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(part)
            } else if (sb.endsWith("-")) {
                sb.deleteCharAt(sb.length - 1)
                sb.append(part)
            } else {
                sb.append(' ').append(part)
            }
        }
        return sb.toString().replace(Regex("\\s{2,}"), " ").trim()
    }

    /**
     * Menghasilkan diagram vektor prosedural (Flowchart tahapan & Kurva Teknik Industri) dari isi dokumen.
     */
    private fun generateProceduralDiagrams(rawText: String, sections: List<SectionBlock>): List<ProceduralDiagram> {
        val out = mutableListOf<ProceduralDiagram>()
        val lower = rawText.lowercase()

        // 1. Deteksi tahapan berurutan (1 -> 2 -> 3 -> 4) untuk digambar sebagai Flowchart
        val stepNodes = mutableListOf<String>()
        for (sec in sections) {
            if (sec.heading.matches(Regex("^\\d+[.)]\\s+.*")) && stepNodes.size < 6) {
                stepNodes.add(sec.heading.replace(Regex("^\\d+[.)]\\s+"), "").take(34))
            }
        }
        if (stepNodes.size < 3) {
            stepNodes.clear()
            rawText.lines().forEach { ln ->
                val m = Regex("^(\\d+)[.)]\\s+([A-Z][A-Za-z0-9\\s]{4,36})").find(ln.trim())
                if (m != null && stepNodes.size < 6) {
                    stepNodes.add(m.groupValues[2].trim())
                }
            }
        }
        if (stepNodes.size >= 3) {
            out.add(ProceduralDiagram("Diagram Alur Tahapan Materi", "flow", stepNodes))
        }

        // 2. Deteksi kurva Teknik Industri
        if (lower.contains("break-even") || lower.contains("break even") || lower.contains("titik impas") || Regex("\\bbep\\b").containsMatchIn(lower)) {
            out.add(ProceduralDiagram("Kurva Analisis Break-Even Point (BEP)", "bep", listOf("Total Revenue (TR)", "Total Cost (TC)", "Fixed Cost (FC)", "Titik Impas (BEP)")))
        }
        if (lower.contains("economic order quantity") || Regex("\\beoq\\b").containsMatchIn(lower)) {
            out.add(ProceduralDiagram("Kurva Biaya Persediaan & EOQ Optimal", "eoq", listOf("Total Cost", "Holding Cost", "Ordering Cost", "Titik Optimal EOQ")))
        }
        if (lower.contains("distribusi normal") || lower.contains("normal distribution") || lower.contains("deviasi standar")) {
            out.add(ProceduralDiagram("Kurva Distribusi Normal (Bell Curve)", "normal", listOf("-2σ", "-1σ", "Mean (μ)", "+1σ", "+2σ")))
        }

        return out
    }

    /**
     * Merender ProceduralDiagram menjadi Bitmap vektor resolusi tinggi untuk ditampilkan & dicetak.
     */
    fun renderProceduralDiagramBitmap(diagram: ProceduralDiagram): Bitmap {
        val w = 900
        val h = if (diagram.kind == "flow") (130 + diagram.nodes.size * 95).coerceIn(320, 760) else 460
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#F8FAFC"))

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(RectF(8f, 8f, w - 8f, h - 8f), 22f, 22f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E1B4B")
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(diagram.title, 34f, 48f, titlePaint)

        when (diagram.kind) {
            "flow" -> {
                val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EEF2FF") }
                val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#4F46E5")
                    style = Paint.Style.STROKE
                    strokeWidth = 3.5f
                }
                val numBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4F46E5") }
                val numText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 20f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val nodeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#0F172A")
                    textSize = 22f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#4F46E5")
                    strokeWidth = 4f
                }

                var y = 82f
                diagram.nodes.forEachIndexed { idx, label ->
                    val rect = RectF(90f, y, w - 90f, y + 62f)
                    canvas.drawRoundRect(rect, 16f, 16f, boxFill)
                    canvas.drawRoundRect(rect, 16f, 16f, boxStroke)
                    canvas.drawCircle(132f, y + 31f, 18f, numBadge)
                    canvas.drawText("${idx + 1}", 132f, y + 38f, numText)
                    canvas.drawText(label, 168f, y + 39f, nodeText)

                    if (idx < diagram.nodes.lastIndex) {
                        val midX = w / 2f
                        canvas.drawLine(midX, y + 62f, midX, y + 92f, arrowPaint)
                        canvas.drawLine(midX - 10f, y + 80f, midX, y + 92f, arrowPaint)
                        canvas.drawLine(midX + 10f, y + 80f, midX, y + 92f, arrowPaint)
                    }
                    y += 92f
                }
            }

            "bep" -> {
                val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#334155"); strokeWidth = 3.5f }
                canvas.drawLine(100f, 380f, 820f, 380f, axis)
                canvas.drawLine(100f, 90f, 100f, 380f, axis)

                val fcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#64748B"); strokeWidth = 3.5f }
                val tcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DC2626"); strokeWidth = 4.5f }
                val trPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#16A34A"); strokeWidth = 4.5f }

                // FC horizontal
                canvas.drawLine(100f, 280f, 780f, 280f, fcPaint)
                // TC starts at FC
                canvas.drawLine(100f, 280f, 780f, 140f, tcPaint)
                // TR starts at origin (100, 380)
                canvas.drawLine(100f, 380f, 780f, 100f, trPaint)

                // BEP Intersection at ~(440, 210)
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4F46E5") }
                canvas.drawCircle(440f, 210f, 11f, dotPaint)

                val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
                lbl.color = Color.parseColor("#16A34A"); canvas.drawText("TR (Pendapatan)", 620f, 115f, lbl)
                lbl.color = Color.parseColor("#DC2626"); canvas.drawText("TC (Total Biaya)", 620f, 185f, lbl)
                lbl.color = Color.parseColor("#64748B"); canvas.drawText("FC (Biaya Tetap)", 620f, 270f, lbl)
                lbl.color = Color.parseColor("#4F46E5"); canvas.drawText("Titik Impas (BEP)", 458f, 215f, lbl)
                lbl.color = Color.parseColor("#0F172A"); canvas.drawText("Volume Produksi (Q) →", 590f, 415f, lbl)
            }

            "eoq" -> {
                val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#334155"); strokeWidth = 3.5f }
                canvas.drawLine(100f, 380f, 820f, 380f, axis)
                canvas.drawLine(100f, 90f, 100f, 380f, axis)

                val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0284C7"); strokeWidth = 4f }
                canvas.drawLine(100f, 380f, 760f, 150f, holdPaint)

                val ordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D97706"); strokeWidth = 4f; style = Paint.Style.STROKE }
                val tcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4F46E5"); strokeWidth = 4.5f; style = Paint.Style.STROKE }

                for (x in 130..750 step 10) {
                    val t = (x - 100f) / 650f
                    val yOrd = (380f - (95f / (t + 0.28f))).coerceIn(100f, 375f)
                    val yHold = 380f - t * 230f
                    val yTot = (yOrd + yHold - 380f).coerceIn(95f, 360f)
                    if (x > 130) {
                        val pt = (x - 110f) / 650f
                        val pyOrd = (380f - (95f / (pt + 0.28f))).coerceIn(100f, 375f)
                        val pyHold = 380f - pt * 230f
                        val pyTot = (pyOrd + pyHold - 380f).coerceIn(95f, 360f)
                        canvas.drawLine((x - 10).toFloat(), pyOrd, x.toFloat(), yOrd, ordPaint)
                        canvas.drawLine((x - 10).toFloat(), pyTot, x.toFloat(), yTot, tcPaint)
                    }
                }
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DC2626") }
                canvas.drawCircle(375f, 285f, 10f, dot)
                val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
                lbl.color = Color.parseColor("#4F46E5"); canvas.drawText("Total Cost (TC)", 560f, 115f, lbl)
                lbl.color = Color.parseColor("#0284C7"); canvas.drawText("Holding Cost", 610f, 185f, lbl)
                lbl.color = Color.parseColor("#D97706"); canvas.drawText("Ordering Cost", 600f, 335f, lbl)
                lbl.color = Color.parseColor("#DC2626"); canvas.drawText("EOQ*", 360f, 412f, lbl)
            }

            else -> {
                val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#334155"); strokeWidth = 3.5f }
                canvas.drawLine(100f, 360f, 800f, 360f, axis)
                val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4F46E5"); strokeWidth = 4.5f }
                val midX = 450f
                for (x in 120..780 step 6) {
                    val z = (x - midX) / 110.0
                    val y = 360f - (230.0 * kotlin.math.exp(-0.5 * z * z)).toFloat()
                    val pz = (x - 6 - midX) / 110.0
                    val py = 360f - (230.0 * kotlin.math.exp(-0.5 * pz * pz)).toFloat()
                    canvas.drawLine((x - 6).toFloat(), py, x.toFloat(), y, curve)
                }
                canvas.drawLine(midX, 125f, midX, 360f, axis)
                val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#1E1B4B")
                    textSize = 20f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("Mean (μ)", midX, 395f, lbl)
                canvas.drawText("-1σ", midX - 110f, 395f, lbl)
                canvas.drawText("+1σ", midX + 110f, 395f, lbl)
            }
        }
        return bmp
    }

    // ==================== TEXT & FORMULA ANALYZER ====================

    fun analyzeText(rawText: String): TextAnalysisResult {
        val clean = rawText
            .replace("\r\n", "\n")
            .replace(Regex("[ \t]+"), " ")
            .trim()
        if (clean.isEmpty()) {
            return TextAnalysisResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        val lines = clean.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.matches(Regex("^---\\s*Halaman\\s+\\d+\\s*---$", RegexOption.IGNORE_CASE)) }

        val sections = mutableListOf<SectionBlock>()
        var currentHeading = "Ringkasan Materi"
        val currentBullets = mutableListOf<String>()
        val currentPara = mutableListOf<String>()

        fun flushSection() {
            if (currentBullets.isNotEmpty() || currentPara.isNotEmpty()) {
                sections.add(
                    SectionBlock(
                        heading = currentHeading,
                        bullets = currentBullets.toList(),
                        body = currentPara.joinToString(" ").trim()
                    )
                )
                currentBullets.clear()
                currentPara.clear()
            }
        }

        for (line in lines) {
            val isHeading = line.matches(Regex("^(BAB|CHAPTER|MODUL|PERTEMUAN|BAGIAN)\\s+[IVX0-9]+.*", RegexOption.IGNORE_CASE)) ||
                line.matches(Regex("^\\d+(\\.\\d+){0,2}\\s+[A-Z][A-Za-z0-9\\s,&()/-]{3,75}$")) ||
                (line.length in 5..65 && line == line.uppercase(Locale.getDefault()) && line.any { it.isLetter() } && !line.contains("="))

            if (isHeading) {
                flushSection()
                currentHeading = line
            } else if (line.startsWith("•") || line.startsWith("-") || line.startsWith("*") || line.matches(Regex("^\\d+[.)]\\s+.*"))) {
                currentBullets.add(line)
            } else {
                currentPara.add(line)
            }
        }
        flushSection()

        val definitions = mutableListOf<Pair<String, String>>()
        val defRegex = Regex("^([A-Za-z][A-Za-z0-9\\s()-]{2,42})\\s+(adalah|merupakan|ialah|yaitu|didefinisikan sebagai)\\s+(.{18,240})", RegexOption.IGNORE_CASE)
        for (line in lines) {
            val m = defRegex.find(line)
            if (m != null && definitions.size < 16) {
                val term = m.groupValues[1].trim()
                val desc = "${m.groupValues[2]} ${m.groupValues[3]}".trim().trimEnd('.') + "."
                if (definitions.none { it.first.equals(term, ignoreCase = true) }) {
                    definitions.add(term to desc)
                }
            }
        }

        val formulaDetails = extractDetailedFormulas(clean)
        val formulas = formulaDetails.map { it.label to it.expression }

        // Fallback deteksi tabel dari teks (jika ada pemisah '|' atau tab)
        val textTables = extractPipeOrTabTables(lines)

        val sentences = clean
            .replace("\n", " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length in 38..230 && !it.startsWith("---") }

        val allKw = COURSE_KEYWORDS.values.flatten().map { it.first }.toSet()
        val scored = sentences.mapIndexed { idx, s ->
            val lower = s.lowercase()
            var score = (s.length / 42.0).coerceAtMost(3.5)
            for (kw in allKw) {
                if (lower.contains(kw)) score += 2.2
            }
            if (lower.contains("adalah") || lower.contains("merupakan") || lower.contains("tujuan") || lower.contains("digunakan untuk")) {
                score += 1.6
            }
            if (idx < 3) score += 1.2
            Triple(idx, s, score)
        }
        val summary = scored
            .sortedByDescending { it.third }
            .take(6)
            .sortedBy { it.first }
            .map { it.second }

        return TextAnalysisResult(
            summary = summary,
            sections = sections,
            definitions = definitions,
            formulas = formulas,
            formulaDetails = formulaDetails,
            tables = textTables,
            figures = emptyList(),
            diagrams = generateProceduralDiagrams(clean, sections),
            schemaVersion = 2
        )
    }

    private fun extractPipeOrTabTables(lines: List<String>): List<ExtractedTable> {
        val out = mutableListOf<ExtractedTable>()
        var i = 0
        var idxCount = 1
        while (i < lines.size) {
            val ln = lines[i]
            val splitter = when {
                ln.count { it == '|' } >= 2 -> "|"
                ln.contains("\t") -> "\t"
                else -> null
            }
            if (splitter != null) {
                val block = mutableListOf<List<String>>()
                var j = i
                while (j < lines.size && lines[j].contains(splitter)) {
                    val cells = lines[j].split(splitter).map { it.trim() }.filter { it.isNotEmpty() && !it.matches(Regex("^[-:]+$")) }
                    if (cells.size >= 2) block.add(cells)
                    j++
                }
                if (block.size >= 2) {
                    val maxCols = block.maxOf { it.size }
                    val norm = block.map { r -> r + List((maxCols - r.size).coerceAtLeast(0)) { "-" } }
                    out.add(ExtractedTable("Tabel #$idxCount", 1, norm.first(), norm.drop(1)))
                    idxCount++
                    i = j
                    continue
                }
            }
            i++
        }
        return out
    }

    fun looksLikeFormula(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length !in 4..120) return false
        val hasEq = trimmed.contains("=") || trimmed.contains("≈") || trimmed.contains("≤") || trimmed.contains("≥")
        val hasMathSymbol = trimmed.any { it in charArrayOf('∑', '∫', '√', 'σ', 'μ', 'λ', 'π', 'Δ', '×', '÷', '^') } ||
            trimmed.contains("sqrt(", ignoreCase = true) ||
            trimmed.contains("lim", ignoreCase = true)
        val hasVarAssignment = Regex("^[A-Za-z][A-Za-z0-9_()]{0,14}\\s*=\\s*[A-Za-z0-9()\\[\\]+\\-*/^.,\\s√∑σμλ]+$").matches(trimmed)
        return (hasEq && (hasVarAssignment || hasMathSymbol || trimmed.count { it in "+-*/^()" } >= 1)) ||
            (hasMathSymbol && trimmed.count { it.isLetterOrDigit() } >= 2)
    }

    fun extractDetailedFormulas(rawText: String): List<FormulaItem> {
        val out = mutableListOf<FormulaItem>()
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (i in lines.indices) {
            val line = lines[i]
            if (!looksLikeFormula(line) || out.size >= 24) continue
            if (out.any { it.expression.equals(line, ignoreCase = true) }) continue

            val prev = lines.getOrNull(i - 1).orEmpty()
            val label = if (prev.length in 5..65 && !looksLikeFormula(prev) && !prev.startsWith("---")) {
                prev.trimEnd(':', '.')
            } else {
                "Rumus #${out.size + 1}"
            }

            val varNotes = mutableListOf<String>()
            val tokens = Regex("\\b[A-Za-z]{1,5}\\b").findAll(line).map { it.value.lowercase() }.toSet()
            for (tok in tokens) {
                FORMULA_VARIABLE_HINTS[tok]?.let { varNotes.add(it) }
            }
            for (k in 1..3) {
                val nextLine = lines.getOrNull(i + k).orEmpty()
                if (nextLine.matches(Regex("^(dimana|keterangan|dengan)\\s*:?.*", RegexOption.IGNORE_CASE)) ||
                    nextLine.matches(Regex("^[A-Za-z]{1,4}\\s*=\\s*[A-Za-z\\s]{4,45}$"))
                ) {
                    varNotes.add(nextLine)
                }
            }
            out.add(FormulaItem(label, line, varNotes.distinct().joinToString(" • ")))
        }
        return out
    }

    fun extractFormulas(rawText: String): List<Pair<String, String>> =
        extractDetailedFormulas(rawText).map { it.label to it.expression }

    fun analysisToJson(analysis: TextAnalysisResult): String {
        val root = JSONObject()
        root.put("schemaVersion", analysis.schemaVersion)
        root.put("summary", JSONArray(analysis.summary))

        val secArr = JSONArray()
        for (s in analysis.sections) {
            secArr.put(JSONObject().apply {
                put("heading", s.heading)
                put("bullets", JSONArray(s.bullets))
                put("body", s.body)
                put("page", s.page)
            })
        }
        root.put("sections", secArr)

        val defArr = JSONArray()
        for ((t, d) in analysis.definitions) {
            defArr.put(JSONObject().apply {
                put("term", t)
                put("desc", d)
            })
        }
        root.put("definitions", defArr)

        val fArr = JSONArray()
        val details = analysis.formulaDetails.ifEmpty {
            analysis.formulas.map { FormulaItem(it.first, it.second, "") }
        }
        for (fd in details) {
            fArr.put(JSONObject().apply {
                put("label", fd.label)
                put("expression", fd.expression)
                put("variableNotes", fd.variableNotes)
            })
        }
        root.put("formulas", fArr)

        val tabArr = JSONArray()
        for (tb in analysis.tables) {
            val rowsJson = JSONArray()
            for (r in tb.rows) rowsJson.put(JSONArray(r))
            tabArr.put(JSONObject().apply {
                put("title", tb.title)
                put("page", tb.page)
                put("headers", JSONArray(tb.headers))
                put("rows", rowsJson)
            })
        }
        root.put("tables", tabArr)

        val figArr = JSONArray()
        for (fg in analysis.figures) {
            figArr.put(JSONObject().apply {
                put("caption", fg.caption)
                put("page", fg.page)
                put("imagePath", fg.imagePath)
            })
        }
        root.put("figures", figArr)

        val diagArr = JSONArray()
        for (dg in analysis.diagrams) {
            diagArr.put(JSONObject().apply {
                put("title", dg.title)
                put("kind", dg.kind)
                put("nodes", JSONArray(dg.nodes))
            })
        }
        root.put("diagrams", diagArr)

        return root.toString()
    }

    fun parseAnalysisJson(raw: String): TextAnalysisResult {
        return try {
            val root = JSONObject(raw)
            val schemaVersion = root.optInt("schemaVersion", 1)
            val summary = mutableListOf<String>()
            val sumArr = root.optJSONArray("summary") ?: JSONArray()
            for (i in 0 until sumArr.length()) summary.add(sumArr.getString(i))

            val sections = mutableListOf<SectionBlock>()
            val secArr = root.optJSONArray("sections") ?: JSONArray()
            for (i in 0 until secArr.length()) {
                val o = secArr.getJSONObject(i)
                val bullets = mutableListOf<String>()
                val bArr = o.optJSONArray("bullets") ?: JSONArray()
                for (j in 0 until bArr.length()) bullets.add(bArr.getString(j))
                sections.add(SectionBlock(o.optString("heading"), bullets, o.optString("body"), o.optInt("page", 1)))
            }

            val defs = mutableListOf<Pair<String, String>>()
            val defArr = root.optJSONArray("definitions") ?: JSONArray()
            for (i in 0 until defArr.length()) {
                val o = defArr.getJSONObject(i)
                defs.add(o.optString("term") to o.optString("desc"))
            }

            val fDetails = mutableListOf<FormulaItem>()
            val fArr = root.optJSONArray("formulas") ?: JSONArray()
            for (i in 0 until fArr.length()) {
                val o = fArr.getJSONObject(i)
                fDetails.add(FormulaItem(o.optString("label"), o.optString("expression"), o.optString("variableNotes")))
            }

            val tables = mutableListOf<ExtractedTable>()
            val tabArr = root.optJSONArray("tables") ?: JSONArray()
            for (i in 0 until tabArr.length()) {
                val o = tabArr.getJSONObject(i)
                val hArr = o.optJSONArray("headers") ?: JSONArray()
                val headers = (0 until hArr.length()).map { hArr.getString(it) }
                val rArr = o.optJSONArray("rows") ?: JSONArray()
                val rows = (0 until rArr.length()).map { rIdx ->
                    val cellArr = rArr.getJSONArray(rIdx)
                    (0 until cellArr.length()).map { cIdx -> cellArr.getString(cIdx) }
                }
                tables.add(ExtractedTable(o.optString("title"), o.optInt("page", 1), headers, rows))
            }

            val figures = mutableListOf<ExtractedFigure>()
            val figArr = root.optJSONArray("figures") ?: JSONArray()
            for (i in 0 until figArr.length()) {
                val o = figArr.getJSONObject(i)
                figures.add(ExtractedFigure(o.optString("caption"), o.optInt("page", 1), o.optString("imagePath")))
            }

            val diagrams = mutableListOf<ProceduralDiagram>()
            val diagArr = root.optJSONArray("diagrams") ?: JSONArray()
            for (i in 0 until diagArr.length()) {
                val o = diagArr.getJSONObject(i)
                val nArr = o.optJSONArray("nodes") ?: JSONArray()
                val nodes = (0 until nArr.length()).map { nArr.getString(it) }
                diagrams.add(ProceduralDiagram(o.optString("title"), o.optString("kind"), nodes))
            }

            TextAnalysisResult(
                summary = summary,
                sections = sections,
                definitions = defs,
                formulas = fDetails.map { it.label to it.expression },
                formulaDetails = fDetails,
                tables = tables,
                figures = figures,
                diagrams = diagrams,
                schemaVersion = schemaVersion
            )
        } catch (_: Exception) {
            TextAnalysisResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    // ==================== ANTI-GLARE WHITEBOARD ENHANCEMENT & OCR ====================

    fun enhanceWhiteboard(context: Context, sourceUri: Uri): File {
        val raw = loadOrientedBitmap(context, sourceUri, 2000)
            ?: throw IllegalStateException("Gagal memuat gambar papan tulis")

        val w = raw.width
        val h = raw.height
        val pixels = IntArray(w * h)
        raw.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        val grid = 32
        val gw = (w + grid - 1) / grid
        val gh = (h + grid - 1) / grid
        val bgGrid = IntArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var p90 = 220
                val samples = IntArray(64)
                var count = 0
                val startY = gy * grid
                val startX = gx * grid
                for (dy in 0 until grid step 4) {
                    val py = (startY + dy).coerceAtMost(h - 1)
                    for (dx in 0 until grid step 4) {
                        val px = (startX + dx).coerceAtMost(w - 1)
                        if (count < 64) samples[count++] = luma[py * w + px]
                    }
                }
                if (count > 0) {
                    samples.sort(0, count)
                    p90 = samples[(count * 85) / 100].coerceAtLeast(90)
                }
                bgGrid[gy * gw + gx] = p90
            }
        }

        val outPixels = IntArray(w * h)
        for (y in 0 until h) {
            val gy = (y / grid).coerceAtMost(gh - 1)
            val rowOffset = y * w
            for (x in 0 until w) {
                val idx = rowOffset + x
                val gx = (x / grid).coerceAtMost(gw - 1)
                val bg = bgGrid[gy * gw + gx]
                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val nr = ((r * 255) / bg).coerceIn(0, 255)
                val ng = ((g * 255) / bg).coerceIn(0, 255)
                val nb = ((b * 255) / bg).coerceIn(0, 255)
                val normLuma = (nr * 77 + ng * 150 + nb * 29) shr 8

                outPixels[idx] = if (normLuma > 214) {
                    Color.rgb(252, 253, 255)
                } else {
                    val dr = ((nr - 128) * 1.45f + 115f).toInt().coerceIn(0, 255)
                    val dg = ((ng - 128) * 1.45f + 115f).toInt().coerceIn(0, 255)
                    val db = ((nb - 128) * 1.45f + 115f).toInt().coerceIn(0, 255)
                    Color.rgb(dr, dg, db)
                }
            }
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, w, 0, 0, w, h)
        raw.recycle()

        val dir = File(context.filesDir, "kuliahvault/papan").apply { mkdirs() }
        val outFile = File(dir, "board_sharp_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { fos ->
            outBmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        outBmp.recycle()
        return outFile
    }

    private fun loadOrientedBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val largest = max(opts.outWidth, opts.outHeight).coerceAtLeast(1)
        var sample = 1
        while (largest / sample > maxDim) sample *= 2

        val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decOpts) }
            ?: return null

        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }

    fun recognizeImageText(context: Context, uri: Uri, callback: (Boolean, String) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val structuredLines = mutableListOf<Pair<Int, String>>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val top = line.boundingBox?.top ?: 0
                            val txt = line.text.trim()
                            if (txt.isNotEmpty()) structuredLines.add(top to txt)
                        }
                    }
                    val finalText = if (structuredLines.isNotEmpty()) {
                        structuredLines.sortedBy { it.first }.joinToString("\n") { it.second }
                    } else {
                        result.text.trim()
                    }
                    callback(true, finalText)
                }
                .addOnFailureListener {
                    callback(false, "")
                }
        } catch (_: Exception) {
            callback(false, "")
        }
    }

    private fun recognizeBitmapSync(bitmap: Bitmap): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var textOut = ""
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { res ->
                    textOut = res.text.trim()
                    latch.countDown()
                }
                .addOnFailureListener {
                    latch.countDown()
                }
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        return textOut
    }

    // ==================== STORAGE & EXPORT DOSSIER PDF ====================

    fun importFileToInternalStorage(
        context: Context,
        sourceUri: Uri,
        subDir: String,
        prefix: String,
        ext: String
    ): File {
        val dir = File(context.filesDir, "kuliahvault/$subDir").apply { mkdirs() }
        val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(36)
        val outFile = File(dir, "${safePrefix}_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Tidak dapat membuka file sumber")
        return outFile
    }

    fun makeSnippet(text: String, lowerQuery: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return ""
        val idx = clean.lowercase().indexOf(lowerQuery)
        if (idx < 0) return clean.take(120) + if (clean.length > 120) "..." else ""
        val start = (idx - 42).coerceAtLeast(0)
        val end = (idx + lowerQuery.length + 72).coerceAtMost(clean.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < clean.length) "..." else ""
        return prefix + clean.substring(start, end) + suffix
    }

    fun exportMeetingDossierPdf(
        context: Context,
        db: ScheduleDbHelper,
        courseKey: String,
        dateKey: String
    ): File {
        val course = courseByKey(courseKey)
        val (pdfs, recs, boards) = db.getVaultMeetingMaterial(courseKey, dateKey)
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas

        val bannerPaint = Paint().apply { color = Color.parseColor("#1E1B4B") }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C7D2FE")
            textSize = 10f
        }
        val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#312E81")
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 10f
        }

        canvas.drawRoundRect(RectF(32f, 32f, (pageWidth - 32).toFloat(), 106f), 14f, 14f, bannerPaint)
        canvas.drawText("DOSSIER MATERI PERTEMUAN", 48f, 62f, titlePaint)
        canvas.drawText("${course.name} • Tanggal $dateKey • AbsenGuard × KuliahVault IE", 48f, 84f, subPaint)

        var y = 132f
        fun ensureSpace(needed: Float) {
            if (y + needed > pageHeight - 44f) {
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                y = 46f
            }
        }

        fun drawWrapped(text: String, paint: Paint, indent: Float = 38f) {
            val maxChars = 84
            val clean = text.replace(Regex("\\s+"), " ").trim()
            var i = 0
            while (i < clean.length) {
                val end = (i + maxChars).coerceAtMost(clean.length)
                ensureSpace(16f)
                canvas.drawText(clean.substring(i, end), indent, y, paint)
                y += 15f
                i = end
            }
        }

        ensureSpace(24f)
        canvas.drawText("1. RINGKASAN MATERI PDF (${pdfs.size} Dokumen)", 36f, y, secPaint)
        y += 18f
        if (pdfs.isEmpty()) {
            drawWrapped("Tidak ada PDF pada tanggal ini.", bodyPaint)
        } else {
            for (p in pdfs) {
                drawWrapped("• ${p.title} (${p.pageCount} halaman)", bodyPaint)
                val an = parseAnalysisJson(p.analysisJson)
                an.summary.take(3).forEach { s -> drawWrapped("   - $s", bodyPaint, 48f) }
            }
        }
        y += 10f

        ensureSpace(24f)
        canvas.drawText("2. TRANSKRIP REKAMAN DOSEN (${recs.size} Sesi)", 36f, y, secPaint)
        y += 18f
        if (recs.isEmpty()) {
            drawWrapped("Tidak ada rekaman suara pada tanggal ini.", bodyPaint)
        } else {
            for (r in recs) {
                drawWrapped("• ${r.title}", bodyPaint)
                val snippet = r.transcriptText.ifBlank { "(Rekaman audio tanpa transkrip)" }
                drawWrapped("   $snippet", bodyPaint, 48f)
            }
        }
        y += 10f

        ensureSpace(24f)
        canvas.drawText("3. CATATAN PAPAN TULIS & OCR (${boards.size} Foto)", 36f, y, secPaint)
        y += 18f
        if (boards.isEmpty()) {
            drawWrapped("Tidak ada foto papan tulis pada tanggal ini.", bodyPaint)
        } else {
            for (b in boards) {
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(b.createdAt))
                drawWrapped("• Papan Tulis ($timeStr): ${b.ocrText.ifBlank { "Tanpa teks OCR" }}", bodyPaint)
                val imgFile = File(b.imageUri)
                if (imgFile.exists()) {
                    val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                    if (bmp != null) {
                        val targetW = 240f
                        val targetH = (bmp.height * (targetW / bmp.width.coerceAtLeast(1))).coerceAtMost(150f)
                        ensureSpace(targetH + 14f)
                        canvas.drawBitmap(bmp, null, RectF(44f, y, 44f + targetW, y + targetH), null)
                        y += targetH + 12f
                        bmp.recycle()
                    }
                }
            }
        }

        pdf.finishPage(page)
        val outDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val outFile = File(outDir, "Dossier_${courseKey}_$dateKey.pdf")
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()
        return outFile
    }
}
