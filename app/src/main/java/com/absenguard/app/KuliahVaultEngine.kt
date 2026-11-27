package com.absenguard.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

data class VaultCourse(
    val key: String,
    val base: String,
    val name: String,
    val variant: String? = null
)

data class ClassificationResult(
    val suggestedCourseKey: String,
    val suggestedBase: String,
    val label: String,
    val share: Double,
    val confident: Boolean,
    val matchedKeywords: List<String>
)

data class PdfExtractionResult(
    val ok: Boolean,
    val pageCount: Int,
    val text: String,
    val scanned: Boolean,
    val error: String = ""
)

data class StudySection(
    val heading: String,
    val bullets: List<String>,
    val body: String
)

data class FormulaItem(
    val label: String,
    val expression: String,
    val variableNotes: String = ""
)

data class TextAnalysisResult(
    val summary: List<String>,
    val definitions: List<Pair<String, String>>,
    val formulas: List<Pair<String, String>>,
    val formulaDetails: List<FormulaItem> = emptyList(),
    val sections: List<StudySection> = emptyList()
)

object KuliahVaultEngine {

    private var pdfBoxInitialized = false

    val VAULT_COURSES = listOf(
        VaultCourse("pemasaran", "pemasaran", "Pemasaran Industri"),
        VaultCourse("bahan", "bahan", "Ilmu Bahan dan Kimia Lingkungan"),
        VaultCourse("probabilitas", "probabilitas", "Teori Probabilitas"),
        VaultCourse("biaya", "biaya", "Analisis dan Estimasi Biaya"),
        VaultCourse("algo_t", "algo", "Algoritma & Pemrograman Komputer (T)", "T"),
        VaultCourse("algo_p", "algo", "Algoritma & Pemrograman Komputer (P)", "P"),
        VaultCourse("or1", "or1", "Operasional Riset 1"),
        VaultCourse("apk_t", "apk", "Analisis dan Perancangan Kerja (T)", "T"),
        VaultCourse("apk_p", "apk", "Analisis Perancangan Kerja (P)", "P"),
        VaultCourse("jatidiri", "jatidiri", "Jatidiri Bangsa"),
        VaultCourse("umum", "umum", "Umum (Belum Terklasifikasi)")
    )

    val BASE_LABELS = mapOf(
        "pemasaran" to "Pemasaran Industri",
        "bahan" to "Ilmu Bahan dan Kimia Lingkungan",
        "probabilitas" to "Teori Probabilitas",
        "biaya" to "Analisis dan Estimasi Biaya",
        "algo" to "Algoritma & Pemrograman Komputer",
        "or1" to "Operasional Riset 1",
        "apk" to "Analisis dan Perancangan Kerja",
        "jatidiri" to "Jatidiri Bangsa",
        "umum" to "Umum (Belum Terklasifikasi)"
    )

    val COURSE_KEYWORDS: Map<String, List<Pair<String, Int>>> = mapOf(
        "pemasaran" to listOf(
            "pemasaran" to 4, "marketing" to 4, "bauran pemasaran" to 5, "marketing mix" to 5,
            "segmentasi" to 4, "targeting" to 3, "positioning" to 3, "perilaku konsumen" to 5,
            "konsumen" to 2, "pelanggan" to 2, "merek" to 3, "brand" to 3, "promosi" to 3,
            "saluran distribusi" to 4, "distribusi" to 2, "pasar sasaran" to 4, "pangsa pasar" to 4,
            "riset pemasaran" to 5, "strategi pemasaran" to 5, "kepuasan pelanggan" to 4,
            "loyalitas" to 3, "siklus hidup produk" to 4, "diferensiasi" to 3, "kompetitor" to 2,
            "pesaing" to 2, "periklanan" to 3, "iklan" to 2, "penjualan" to 2, "b2b" to 3,
            "b2c" to 3, "penetapan harga" to 4, "harga jual" to 2, "swot" to 2,
            "pemasaran industri" to 6, "pasar industri" to 4
        ),
        "bahan" to listOf(
            "ilmu bahan" to 6, "material" to 3, "logam" to 4, "polimer" to 4, "keramik" to 4,
            "komposit" to 4, "baja" to 3, "paduan" to 4, "alloy" to 3, "struktur kristal" to 5,
            "tegangan" to 2, "regangan" to 3, "kekuatan tarik" to 5, "kekerasan" to 4,
            "korosi" to 4, "perlakuan panas" to 5, "diagram fasa" to 5, "ikatan atom" to 4,
            "kimia lingkungan" to 6, "limbah" to 4, "pencemaran" to 4, "polusi" to 4,
            "titrasi" to 4, "ekosistem" to 3, "air limbah" to 5, "bod" to 3, "cod" to 3,
            "amdal" to 4, "daur ulang" to 3, "emisi" to 3, "senyawa" to 3, "reaksi kimia" to 4,
            "larutan" to 3, "atom" to 3, "molekul" to 3, "sifat mekanik" to 5, "sifat material" to 4
        ),
        "probabilitas" to listOf(
            "probabilitas" to 6, "peluang" to 5, "ruang sampel" to 6, "kejadian" to 3,
            "variabel acak" to 6, "distribusi" to 3, "binomial" to 5, "poisson" to 5,
            "distribusi normal" to 5, "eksponensial" to 3, "nilai harapan" to 5, "ekspektasi" to 3,
            "varians" to 4, "simpangan baku" to 3, "bersyarat" to 4, "teorema bayes" to 6,
            "bayes" to 4, "kombinasi" to 2, "permutasi" to 3, "fungsi kepadatan" to 5,
            "fungsi distribusi kumulatif" to 5, "fungsi pembangkit momen" to 5, "saling bebas" to 4,
            "saling lepas" to 4, "distribusi gabungan" to 5, "kovarians" to 4,
            "hukum bilangan besar" to 5, "limit sentral" to 5, "geometrik" to 3,
            "hipergeometrik" to 5, "bernoulli" to 4, "distribusi diskrit" to 5, "distribusi kontinu" to 5
        ),
        "biaya" to listOf(
            "estimasi biaya" to 6, "analisis biaya" to 6, "biaya" to 3, "harga pokok" to 5,
            "hpp" to 4, "biaya tetap" to 5, "biaya variabel" to 5, "overhead" to 4, "bop" to 3,
            "break even" to 5, "titik impas" to 5, "biaya langsung" to 5, "biaya tidak langsung" to 5,
            "depresiasi" to 4, "nilai waktu uang" to 5, "suku bunga" to 3, "bunga majemuk" to 4,
            "anggaran" to 3, "kurva belajar" to 4, "learning curve" to 4, "cost estimating" to 5,
            "estimasi parametrik" to 5, "indeks biaya" to 5, "job order" to 4, "inflasi" to 3,
            "npv" to 3, "irr" to 3, "payback" to 3, "biaya tenaga kerja" to 4,
            "biaya bahan baku" to 4, "life cycle cost" to 5, "biaya siklus hidup" to 5, "rab" to 3
        ),
        "algo" to listOf(
            "algoritma" to 6, "pemrograman" to 5, "program" to 2, "variabel" to 2, "tipe data" to 5,
            "percabangan" to 5, "perulangan" to 5, "array" to 4, "string" to 3, "pointer" to 4,
            "rekursif" to 4, "rekursi" to 4, "flowchart" to 4, "diagram alir" to 4,
            "pseudocode" to 5, "python" to 5, "c++" to 5, "java" to 3, "kompilasi" to 4,
            "compiler" to 4, "if else" to 4, "for loop" to 4, "struktur data" to 5,
            "sorting" to 4, "searching" to 4, "debug" to 4, "sintaks" to 4,
            "praktikum pemrograman" to 6, "kode program" to 5, "source code" to 4, "jupyter" to 3
        ),
        "or1" to listOf(
            "riset operasi" to 6, "operasional riset" to 6, "program linier" to 6,
            "program linear" to 6, "linear programming" to 6, "simpleks" to 6,
            "metode simpleks" to 6, "metode grafik" to 4, "fungsi tujuan" to 5,
            "fungsi kendala" to 4, "solusi optimal" to 5, "dualitas" to 5,
            "analisis sensitivitas" to 4, "transportasi" to 4, "penugasan" to 4,
            "vogel" to 5, "northwest corner" to 5, "stepping stone" to 5,
            "variabel keputusan" to 5, "fungsi objektif" to 5, "daerah layak" to 5,
            "variabel basis" to 5, "big m" to 4, "dua fase" to 4, "shadow price" to 4,
            "harga bayangan" to 4, "optimasi" to 3, "integer programming" to 4,
            "maksimasi" to 4, "minimasi" to 4, "metode hungarian" to 5
        ),
        "apk" to listOf(
            "studi waktu" to 5, "time study" to 5, "studi gerak" to 5, "work sampling" to 5,
            "sampling kerja" to 5, "peta kerja" to 5, "peta proses operasi" to 6,
            "flow process chart" to 5, "therblig" to 6, "waktu baku" to 6, "waktu standar" to 6,
            "waktu normal" to 5, "performance rating" to 5, "allowance" to 4, "kelonggaran" to 5,
            "stopwatch" to 5, "mtm" to 4, "antropometri" to 6, "ergonomi" to 6, "beban kerja" to 5,
            "analisis kerja" to 5, "metode kerja" to 5, "stasiun kerja" to 4, "tata cara kerja" to 5,
            "biomekanika" to 5, "kelelahan" to 4, "lingkungan kerja" to 4, "pengukuran kerja" to 6,
            "elemen kerja" to 5, "westinghouse" to 5, "perancangan kerja" to 6
        ),
        "jatidiri" to listOf(
            "pancasila" to 6, "jati diri" to 6, "jatidiri" to 6, "kewarganegaraan" to 5,
            "uud 1945" to 6, "undang-undang dasar" to 5, "nasionalisme" to 5,
            "wawasan nusantara" to 6, "bhinneka tunggal ika" to 6, "bhinneka" to 4,
            "demokrasi" to 4, "hak asasi manusia" to 5, "identitas nasional" to 6,
            "proklamasi" to 5, "konstitusi" to 5, "ketahanan nasional" to 6, "geopolitik" to 5,
            "bela negara" to 6, "kebangsaan" to 5, "integrasi nasional" to 6,
            "sumpah pemuda" to 6, "warga negara" to 4, "otonomi daerah" to 5,
            "karakter bangsa" to 6, "patriotisme" to 5
        )
    )

    private val STOPWORDS_ID_EN = setOf(
        "yang", "dan", "di", "ke", "dari", "pada", "dalam", "untuk", "dengan", "atau", "ini", "itu",
        "adalah", "merupakan", "yaitu", "ialah", "sebagai", "oleh", "karena", "jika", "maka", "dapat",
        "akan", "telah", "sudah", "belum", "tidak", "bukan", "sangat", "lebih", "paling", "antara",
        "serta", "secara", "hal", "tersebut", "suatu", "satu", "dua", "tiga", "kita", "kami", "anda",
        "the", "and", "of", "to", "in", "is", "are", "for", "with", "that", "this", "from", "by", "as"
    )

    private val BOILERPLATE_PATTERNS = listOf(
        Regex("^(dosen|pengampu|penyusun|disusun oleh|oleh|nama|nim|kelas|prodi|program studi|fakultas|universitas|pertemuan|minggu ke|slide|halaman|page)\\s*[:\\-]", RegexOption.IGNORE_CASE),
        Regex("^(universitas buana perjuangan|ubp karawang|teknik industri|daftar pustaka|referensi|terima kasih|sekian|thank you|q\\s*&\\s*a)\\b", RegexOption.IGNORE_CASE),
        Regex("^\\d+\\s*(/|of|dari)?\\s*\\d*$")
    )

    private val MATH_UNICODE_CHARS = setOf(
        '∑', '∫', '√', '≤', '≥', '≈', '≠', '±', '×', '÷', '∝', '∞', '∂', '∇',
        'α', 'β', 'γ', 'δ', 'ε', 'θ', 'λ', 'μ', 'π', 'ρ', 'σ', 'τ', 'φ', 'ω', 'Δ', 'Σ', 'Ω', '∏'
    )

    private val IE_SYMBOL_REGEX = Regex(
        "\\b(EOQ|ROP|OEE|NPV|IRR|BEP|HPP|BOP|TAC|TC|LT|SS|UCL|LCL|CL|Cp|Cpk|Pp|Ppk|Wb|Wn|Ws|Pr|All|MTBF|MTTR|WIP|P\\([A-Za-z0-9|,'\\s]+\\)|E\\([A-Za-z0-9,'\\s]+\\)|Var\\([A-Za-z0-9,'\\s]+\\)|f\\([xyz]\\))\\b"
    )

    fun ensurePdfBox(context: Context) {
        if (!pdfBoxInitialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxInitialized = true
        }
    }

    fun courseByKey(key: String): VaultCourse =
        VAULT_COURSES.firstOrNull { it.key == key } ?: VAULT_COURSES.last()

    fun mapScheduleNameToCourseKey(courseName: String): String {
        val lower = courseName.lowercase()
        return when {
            lower.contains("pemasaran") -> "pemasaran"
            lower.contains("bahan") || lower.contains("kimia") -> "bahan"
            lower.contains("probabilitas") -> "probabilitas"
            lower.contains("biaya") -> "biaya"
            lower.contains("algoritma") && lower.contains("(p)") -> "algo_p"
            lower.contains("algoritma") -> "algo_t"
            lower.contains("operasional") || lower.contains("riset") -> "or1"
            lower.contains("perancangan kerja") && lower.contains("(p)") -> "apk_p"
            lower.contains("perancangan kerja") -> "apk_t"
            lower.contains("jatidiri") || lower.contains("jati diri") -> "jatidiri"
            else -> "umum"
        }
    }

    fun classifyText(text: String): ClassificationResult {
        val lower = text.lowercase()
        if (lower.length < 30) {
            return ClassificationResult("umum", "umum", BASE_LABELS["umum"]!!, 0.0, false, emptyList())
        }
        val scores = mutableListOf<Triple<String, Int, List<String>>>()
        var totalScore = 0
        for ((base, keywords) in COURSE_KEYWORDS) {
            var score = 0
            val hits = mutableListOf<String>()
            for ((kw, weight) in keywords) {
                var idx = lower.indexOf(kw)
                var count = 0
                while (idx >= 0 && count < 10) {
                    count++
                    idx = lower.indexOf(kw, idx + kw.length)
                }
                if (count > 0) {
                    score += weight * count
                    hits.add(kw)
                }
            }
            totalScore += score
            scores.add(Triple(base, score, hits))
        }
        scores.sortByDescending { it.second }
        val top = scores.firstOrNull()
        if (top == null || top.second < 10 || totalScore == 0) {
            return ClassificationResult("umum", "umum", BASE_LABELS["umum"]!!, 0.0, false, emptyList())
        }
        val share = top.second.toDouble() / totalScore.toDouble()
        val confident = share >= 0.34
        val base = if (confident) top.first else "umum"
        val courseKey = if (!confident) "umum" else VAULT_COURSES.firstOrNull { it.base == base }?.key ?: "umum"
        return ClassificationResult(
            suggestedCourseKey = courseKey,
            suggestedBase = base,
            label = BASE_LABELS[base] ?: base,
            share = share,
            confident = confident,
            matchedKeywords = top.third.take(5)
        )
    }

    // ==================== 1. LAYOUT-AWARE PDF EXTRACTION ====================

    private data class StyledLine(
        val text: String,
        val fontSize: Float,
        val isBold: Boolean,
        val page: Int,
        val yNorm: Float
    )

    private class LayoutPdfStripper : PDFTextStripper() {
        val capturedLines = mutableListOf<StyledLine>()
        private var currentPageHeight = 842f

        init {
            sortByPosition = true
        }

        override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
            super.startPage(page)
            currentPageHeight = page.mediaBox?.height?.takeIf { it > 0f } ?: 842f
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val cleaned = text.trim()
            if (cleaned.isEmpty() || textPositions.isEmpty()) return

            var sumSize = 0f
            var boldChars = 0
            var minY = Float.MAX_VALUE
            val sb = StringBuilder()
            var prevEndX = -1f

            for (pos in textPositions) {
                val sz = pos.fontSizeInPt.takeIf { it in 4f..96f } ?: pos.heightDir.takeIf { it in 4f..96f } ?: 11f
                sumSize += sz
                val fname = pos.font?.name?.lowercase().orEmpty()
                if (fname.contains("bold") || fname.contains("black") || fname.contains("heavy") || fname.contains("demi")) {
                    boldChars++
                }
                if (pos.yDirAdj < minY) minY = pos.yDirAdj

                val curX = pos.xDirAdj
                val spaceW = pos.widthOfSpace.takeIf { it > 0.5f } ?: (sz * 0.28f)
                if (prevEndX > 0f) {
                    val gap = curX - prevEndX
                    if (gap > spaceW * 3.2f) {
                        sb.append("  |  ")
                    } else if (gap > spaceW * 0.48f && sb.isNotEmpty() && sb.last() != ' ') {
                        sb.append(' ')
                    }
                }
                sb.append(pos.unicode)
                prevEndX = curX + pos.widthDirAdj
            }

            val finalLine = sb.toString().replace(Regex("\\s{2,}"), " ").trim()
            if (finalLine.isNotEmpty()) {
                val avgSize = sumSize / textPositions.size
                val isBold = boldChars >= (textPositions.size * 0.55f)
                val yNorm = (minY / currentPageHeight).coerceIn(0f, 1f)
                capturedLines.add(StyledLine(finalLine, avgSize, isBold, currentPageNo, yNorm))
            }
        }
    }

    fun extractPdfInfo(context: Context, file: File): PdfExtractionResult {
        return try {
            ensurePdfBox(context)
            var pageCount = 0
            val rawLines = mutableListOf<StyledLine>()

            PDDocument.load(file).use { doc ->
                pageCount = doc.numberOfPages
                val maxPagesToParse = min(pageCount, 80)
                val stripper = LayoutPdfStripper().apply {
                    startPage = 1
                    endPage = maxPagesToParse
                }
                stripper.getText(doc)
                rawLines.addAll(stripper.capturedLines)
            }

            val reconstructed = reconstructLayoutText(rawLines, pageCount)
            val alphaCount = reconstructed.count { it.isLetterOrDigit() }

            // Fallback OCR otomatis jika PDF dosen berupa hasil scan gambar
            if (alphaCount < 60 && pageCount > 0) {
                val ocrText = extractScannedPdfViaOcr(file, min(pageCount, 12))
                val ocrAlpha = ocrText.count { it.isLetterOrDigit() }
                if (ocrAlpha > alphaCount) {
                    return PdfExtractionResult(
                        ok = true,
                        pageCount = pageCount,
                        text = ocrText,
                        scanned = true
                    )
                }
            }

            PdfExtractionResult(
                ok = true,
                pageCount = pageCount,
                text = reconstructed,
                scanned = alphaCount < 40
            )
        } catch (e: Exception) {
            PdfExtractionResult(
                ok = false,
                pageCount = 0,
                text = "",
                scanned = false,
                error = e.message ?: "Gagal memproses dokumen PDF"
            )
        }
    }

    private fun extractScannedPdfViaOcr(file: File, maxPages: Int): String {
        val sb = StringBuilder()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val limit = min(renderer.pageCount, maxPages)
                    for (i in 0 until limit) {
                        renderer.openPage(i).use { page ->
                            val targetW = 1500
                            val scale = targetW.toFloat() / max(page.width, 1).toFloat()
                            val targetH = (page.height * scale).roundToInt().coerceAtLeast(100)
                            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val normalized = normalizeIlluminationBitmap(bmp)
                            val image = InputImage.fromBitmap(normalized, 0)
                            val visionText = Tasks.await(recognizer.process(image))
                            val pageText = formatSortedVisionText(visionText)
                            if (pageText.isNotBlank()) {
                                sb.append("[BAB] Halaman ").append(i + 1).append("\n")
                                sb.append(pageText).append("\n\n")
                            }
                            if (normalized !== bmp) normalized.recycle()
                            bmp.recycle()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            recognizer.close()
        }
        return sb.toString().trim()
    }

    private fun reconstructLayoutText(lines: List<StyledLine>, pageCount: Int): String {
        if (lines.isEmpty()) return ""

        // 1. Hitung frekuensi teks header/footer berulang di top/bottom halaman
        val edgeFreq = mutableMapOf<String, Int>()
        for (ln in lines) {
            if (ln.yNorm < 0.08f || ln.yNorm > 0.91f) {
                val norm = ln.text.lowercase().replace(Regex("\\d+"), "#").trim()
                edgeFreq[norm] = (edgeFreq[norm] ?: 0) + 1
            }
        }
        val repeatedThreshold = if (pageCount >= 3) 2 else 999

        // 2. Hitung median ukuran huruf tubuh dokumen
        val sizes = lines.map { it.fontSize }.sorted()
        val medianFont = sizes[sizes.size / 2].coerceIn(8f, 18f)

        val cleanedLines = mutableListOf<Pair<String, Boolean>>() // (lineText, isHeading)
        for (ln in lines) {
            val t = ln.text.trim()
            if (t.isEmpty()) continue
            if ((ln.yNorm < 0.08f || ln.yNorm > 0.91f)) {
                val norm = t.lowercase().replace(Regex("\\d+"), "#").trim()
                if ((edgeFreq[norm] ?: 0) >= repeatedThreshold) continue
                if (t.matches(Regex("^(hal\\.?|page|slide)?\\s*\\d+\\s*(of|/)?\\s*\\d*$", RegexOption.IGNORE_CASE))) continue
            }

            val isHeading = (
                (ln.fontSize >= medianFont * 1.18f && t.length in 3..95) ||
                (ln.isBold && t.length in 4..80 && !t.endsWith(".") && !t.endsWith(",") && !looksLikeFormula(t)) ||
                t.matches(Regex("^(BAB|MODUL|PERTEMUAN|MATERI|CHAPTER|SUB\\s*BAB)\\s+[0-9IVX]+.*", RegexOption.IGNORE_CASE))
            )
            cleanedLines.add(t to isHeading)
        }

        // 3. Gabungkan baris kalimat yang terpotong (De-hyphenation & Paragraph Merging)
        val out = StringBuilder()
        var buffer = StringBuilder()

        fun flushBuffer() {
            val s = buffer.toString().trim()
            if (s.isNotEmpty()) {
                out.append(s).append("\n\n")
            }
            buffer = StringBuilder()
        }

        for ((rawLine, isHeading) in cleanedLines) {
            val line = normalizeBulletPrefix(rawLine)
            val isBullet = line.startsWith("• ") || line.matches(Regex("^(\\d+[.)]|[a-zA-Z][.)])\\s+.*"))
            val isFormula = looksLikeFormula(line)

            if (isHeading) {
                flushBuffer()
                out.append("[BAB] ").append(line).append("\n")
                continue
            }

            if (isBullet || isFormula) {
                flushBuffer()
                out.append(line).append("\n")
                continue
            }

            if (buffer.isEmpty()) {
                buffer.append(line)
            } else {
                val cur = buffer.toString()
                // Jika kata terpotong tanda hubung di akhir baris (misal: "per-\nancangan")
                if (cur.endsWith("-") && line.firstOrNull()?.isLowerCase() == true) {
                    buffer.deleteCharAt(buffer.length - 1)
                    buffer.append(line)
                } else if (!cur.endsWith(".") && !cur.endsWith(":") && !cur.endsWith("!") && !cur.endsWith("?") && cur.length < 360) {
                    buffer.append(' ').append(line)
                } else {
                    flushBuffer()
                    buffer.append(line)
                }
            }
        }
        flushBuffer()

        return out.toString()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun normalizeBulletPrefix(line: String): String {
        return line
            .replace(Regex("^[▪▫●○◆◇►▸➢➣✓✔]\\s*"), "• ")
            .replace(Regex("^[-*]\\s+(?=[A-Za-z0-9])"), "• ")
    }

    // ==================== 2. ANALISIS MATERI, RUMUS TI & GLOSARIUM ====================

    fun analyzeText(text: String): TextAnalysisResult {
        val rawLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return TextAnalysisResult(emptyList(), emptyList(), emptyList())

        // A. Bangun Struktur Bab / Topik (Sections)
        val sections = buildStructuredSections(rawLines)

        // B. Ekstrak Rumus Matematika & Teknik Industri beserta Keterangan Variabel
        val formulaDetails = extractDetailedFormulas(rawLines)
        val formulaPairs = formulaDetails.map { it.label to it.expression }

        // C. Ekstrak Glosarium / Definisi Penting (filter metadata sampul)
        val definitions = extractCleanDefinitions(rawLines)

        // D. Ekstrak Intisari Materi berbasis Skor Kepadatan Kata Kunci (TF-IDF + Domain Weight)
        val summary = extractRankedKeyPoints(rawLines, maxPoints = 7)

        return TextAnalysisResult(
            summary = summary,
            definitions = definitions,
            formulas = formulaPairs,
            formulaDetails = formulaDetails,
            sections = sections
        )
    }

    private fun buildStructuredSections(lines: List<String>): List<StudySection> {
        val sections = mutableListOf<StudySection>()
        var currentHeading = "Ringkasan Materi"
        val bullets = mutableListOf<String>()
        val bodyLines = mutableListOf<String>()

        fun commitSection() {
            if (bullets.isNotEmpty() || bodyLines.isNotEmpty()) {
                sections.add(
                    StudySection(
                        heading = currentHeading,
                        bullets = bullets.toList(),
                        body = bodyLines.joinToString("\n\n")
                    )
                )
            }
            bullets.clear()
            bodyLines.clear()
        }

        for (ln in lines) {
            if (ln.startsWith("[BAB] ")) {
                val h = ln.removePrefix("[BAB] ").trim()
                if (!isBoilerplateLine(h)) {
                    commitSection()
                    currentHeading = h
                }
            } else if (isBoilerplateLine(ln)) {
                continue
            } else if (ln.startsWith("• ") || ln.matches(Regex("^(\\d+[.)]|[a-zA-Z][.)])\\s+.*"))) {
                bullets.add(ln)
            } else {
                bodyLines.add(ln)
            }
        }
        commitSection()
        return sections.take(25)
    }

    private fun isBoilerplateLine(line: String): Boolean {
        val clean = line.removePrefix("[BAB] ").trim()
        if (clean.length < 3) return true
        return BOILERPLATE_PATTERNS.any { it.containsMatchIn(clean) }
    }

    private fun extractRankedKeyPoints(lines: List<String>, maxPoints: Int): List<String> {
        val candidates = mutableListOf<String>()
        for (raw in lines) {
            if (raw.startsWith("[BAB] ")) continue
            val clean = raw.removePrefix("• ").trim()
            if (isBoilerplateLine(clean)) continue
            if (looksLikeFormula(clean)) continue

            // Pecah paragraf panjang menjadi kalimat utuh
            val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
            for (s in sentences) {
                val st = s.trim()
                if (st.length in 38..240 && !isBoilerplateLine(st) && !looksLikeFormula(st)) {
                    candidates.add(st)
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        // Hitung frekuensi kata bermakna (TF)
        val termFreq = mutableMapOf<String, Int>()
        for (c in candidates) {
            tokenizeMeaningful(c).forEach { tok ->
                termFreq[tok] = (termFreq[tok] ?: 0) + 1
            }
        }

        val allDomainKeywords = COURSE_KEYWORDS.values.flatten().map { it.first }.toSet()

        val scored = candidates.mapIndexed { idx, sent ->
            val tokens = tokenizeMeaningful(sent)
            var score = tokens.sumOf { (termFreq[it] ?: 1).coerceAtMost(5) }.toDouble() / max(tokens.size, 4)
            val lower = sent.lowercase()
            if (allDomainKeywords.any { lower.contains(it) }) score += 2.2
            if (lower.contains("adalah") || lower.contains("merupakan") || lower.contains("tujuan") || lower.contains("fungsi") || lower.contains("metode")) {
                score += 1.5
            }
            // Penalti sedikit untuk halaman paling awal yang sering berisi info administratif
            if (idx == 0 && candidates.size > 5) score -= 0.4
            Triple(idx, sent, score)
        }

        val topSelected = scored
            .sortedByDescending { it.third }
            .distinctBy { it.second.lowercase().take(45) }
            .take(maxPoints)
            .sortedBy { it.first } // Kembalikan sesuai urutan alur materi
            .map { it.second }

        return topSelected
    }

    private fun tokenizeMeaningful(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 4 && it !in STOPWORDS_ID_EN }
    }

    private fun extractCleanDefinitions(lines: List<String>): List<Pair<String, String>> {
        val definitions = mutableListOf<Pair<String, String>>()
        val defRegex = Regex(
            "^([A-Z][A-Za-z0-9\\s\\-()/]{2,48}?)\\s+(adalah|merupakan|yaitu|ialah|didefinisikan sebagai)\\s+([^.!?]{18,210}[.!?]?)"
        )
        val colonGlossaryRegex = Regex(
            "^([A-Z][A-Za-z0-9\\s\\-()/]{2,36})\\s*:\\s*([A-Za-z][^\\n]{22,190})$"
        )

        for (raw in lines) {
            if (raw.startsWith("[BAB] ")) continue
            val clean = raw.removePrefix("• ").trim()
            if (isBoilerplateLine(clean)) continue

            val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
            for (s in sentences) {
                val st = s.trim()
                val m1 = defRegex.find(st)
                if (m1 != null) {
                    val term = m1.groupValues[1].trim()
                    val desc = m1.groupValues[3].trim()
                    if (!isBoilerplateLine(term) && definitions.none { it.first.equals(term, ignoreCase = true) }) {
                        definitions.add(term to desc)
                    }
                    continue
                }
                val m2 = colonGlossaryRegex.find(st)
                if (m2 != null && !looksLikeFormula(st)) {
                    val term = m2.groupValues[1].trim()
                    val desc = m2.groupValues[2].trim()
                    if (!isBoilerplateLine(term) && definitions.none { it.first.equals(term, ignoreCase = true) }) {
                        definitions.add(term to desc)
                    }
                }
            }
            if (definitions.size >= 15) break
        }
        return definitions
    }

    fun looksLikeFormula(rawLine: String): Boolean {
        val line = rawLine.removePrefix("• ").trim()
        if (line.length !in 4..105) return false
        if (line.contains("http://", true) || line.contains("https://", true) || line.contains("www.", true)) return false

        val hasMathUnicode = line.any { it in MATH_UNICODE_CHARS }
        val hasIeSymbol = IE_SYMBOL_REGEX.containsMatchIn(line)
        val hasRelation = line.contains('=') || line.contains('≈') || line.contains('≤') || line.contains('≥')
        val hasOperator = line.any { it in charArrayOf('+', '-', '*', '/', '^', '×', '÷', '√', '∑', '∫') }
        val hasSubOrPow = line.contains(Regex("[A-Za-z][_^]\\{?[0-9a-zA-Z]+\\}?")) || line.contains(Regex("\\b[xyzXYZ][1-9]\\b"))

        if (!hasRelation && !hasMathUnicode && !hasIeSymbol) return false

        // Hindari kalimat naratif panjang yang kebetulan mengandung tanda '-'
        val words = line.split(Regex("\\s+"))
        val normalWords = words.count { it.length >= 4 && it.all(Char::isLetter) && it.lowercase() in STOPWORDS_ID_EN }
        if (normalWords >= 3) return false

        return (hasRelation && (hasOperator || hasMathUnicode || hasIeSymbol || hasSubOrPow || line.any(Char::isDigit))) ||
            (hasMathUnicode && (hasOperator || line.any(Char::isDigit)))
    }

    fun extractFormulas(text: String): List<Pair<String, String>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return extractDetailedFormulas(lines).map { it.label to it.expression }
    }

    private fun extractDetailedFormulas(lines: List<String>): List<FormulaItem> {
        val out = mutableListOf<FormulaItem>()
        var lastHeading = ""

        for (i in lines.indices) {
            val raw = lines[i]
            if (raw.startsWith("[BAB] ")) {
                lastHeading = raw.removePrefix("[BAB] ").trim()
                continue
            }
            val clean = raw.removePrefix("• ").trim()
            if (!looksLikeFormula(clean)) continue

            val leftSide = clean.substringBefore("=").substringBefore("≈").substringBefore("≤").substringBefore("≥").trim()
            val label = when {
                leftSide.length in 1..24 -> "Rumus $leftSide"
                lastHeading.length in 3..34 -> lastHeading
                else -> "Persamaan #${out.size + 1}"
            }

            // Cari keterangan variabel di baris-baris tepat di bawah rumus ("dimana:", "dengan:", "x = ...")
            val varNotes = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size && varNotes.size < 4) {
                val nextLn = lines[j].removePrefix("• ").trim()
                if (nextLn.matches(Regex("^(dimana|di mana|dengan|keterangan|where)\\s*:?$", RegexOption.IGNORE_CASE))) {
                    j++
                    continue
                }
                if (nextLn.matches(Regex("^[A-Za-zα-ωΔΣ_0-9()]{1,12}\\s*(=|:|adalah)\\s*[^=]{3,70}$")) && !looksLikeFormula(nextLn)) {
                    varNotes.add(nextLn)
                    j++
                } else {
                    break
                }
            }

            if (out.none { it.expression == clean }) {
                out.add(
                    FormulaItem(
                        label = label,
                        expression = clean,
                        variableNotes = varNotes.joinToString(" • ")
                    )
                )
            }
            if (out.size >= 20) break
        }
        return out
    }

    fun analysisToJson(a: TextAnalysisResult): String {
        val root = JSONObject()
        root.put("summary", JSONArray(a.summary))

        val defs = JSONArray()
        a.definitions.forEach { (t, d) ->
            defs.put(JSONObject().apply {
                put("term", t)
                put("desc", d)
            })
        }
        root.put("definitions", defs)

        val forms = JSONArray()
        a.formulas.forEach { (l, e) ->
            forms.put(JSONObject().apply {
                put("label", l)
                put("expr", e)
            })
        }
        root.put("formulas", forms)

        val fDetails = JSONArray()
        a.formulaDetails.forEach { f ->
            fDetails.put(JSONObject().apply {
                put("label", f.label)
                put("expr", f.expression)
                put("notes", f.variableNotes)
            })
        }
        root.put("formulaDetails", fDetails)

        val secArr = JSONArray()
        a.sections.forEach { s ->
            secArr.put(JSONObject().apply {
                put("heading", s.heading)
                put("bullets", JSONArray(s.bullets))
                put("body", s.body)
            })
        }
        root.put("sections", secArr)

        return root.toString()
    }

    fun parseAnalysisJson(raw: String): TextAnalysisResult {
        return try {
            val root = JSONObject(raw)
            val sumArr = root.optJSONArray("summary") ?: JSONArray()
            val summary = (0 until sumArr.length()).map { sumArr.getString(it) }

            val defArr = root.optJSONArray("definitions") ?: JSONArray()
            val defs = (0 until defArr.length()).map {
                val o = defArr.getJSONObject(it)
                o.optString("term") to o.optString("desc")
            }

            val formArr = root.optJSONArray("formulas") ?: JSONArray()
            val forms = (0 until formArr.length()).map {
                val o = formArr.getJSONObject(it)
                o.optString("label") to o.optString("expr")
            }

            val fDetArr = root.optJSONArray("formulaDetails") ?: JSONArray()
            val fDetails = if (fDetArr.length() > 0) {
                (0 until fDetArr.length()).map {
                    val o = fDetArr.getJSONObject(it)
                    FormulaItem(o.optString("label"), o.optString("expr"), o.optString("notes"))
                }
            } else {
                forms.map { FormulaItem(it.first, it.second, "") }
            }

            val secArr = root.optJSONArray("sections") ?: JSONArray()
            val sections = (0 until secArr.length()).map {
                val o = secArr.getJSONObject(it)
                val bArr = o.optJSONArray("bullets") ?: JSONArray()
                val bullets = (0 until bArr.length()).map { bi -> bArr.getString(bi) }
                StudySection(o.optString("heading"), bullets, o.optString("body"))
            }

            TextAnalysisResult(summary, defs, forms, fDetails, sections)
        } catch (_: Exception) {
            TextAnalysisResult(emptyList(), emptyList(), emptyList())
        }
    }

    // ==================== 3. WHITEBOARD ILLUMINATION NORMALIZATION & SPATIAL OCR ====================

    fun importFileToInternalStorage(context: Context, sourceUri: Uri, subFolder: String, prefix: String, ext: String): File {
        val dir = File(context.filesDir, "kuliahvault/$subFolder").apply { mkdirs() }
        val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(32)
        val outFile = File(dir, "${safePrefix}_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Tidak dapat membuka file sumber")
        return outFile
    }

    fun enhanceWhiteboard(context: Context, sourceUri: Uri): File {
        val dir = File(context.filesDir, "kuliahvault/papan").apply { mkdirs() }
        val outFile = File(dir, "scan_tajam_${System.currentTimeMillis()}.jpg")
        val raw = context.contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Gagal memuat gambar")

        val maxDim = 1920
        val scale = if (raw.width > maxDim || raw.height > maxDim) {
            maxDim.toFloat() / max(raw.width, raw.height).toFloat()
        } else 1f
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
        } else raw

        val normalized = normalizeIlluminationBitmap(bmp)
        FileOutputStream(outFile).use { fos ->
            normalized.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        if (normalized !== bmp) normalized.recycle()
        if (bmp !== raw) bmp.recycle()
        raw.recycle()
        return outFile
    }

    /**
     * Menghapus pantulan lampu kelas & bayangan tidak merata pada papan tulis
     * menggunakan Background Illumination Division (L / B * 248 + S-Curve).
     */
    private fun normalizeIlluminationBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val lum = IntArray(total)
        for (i in 0 until total) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            lum[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        // Buat peta pencahayaan latar (downsampled ~1/16 lalu box-blur 2 pass)
        val ds = 16
        val bw = (w / ds).coerceAtLeast(4)
        val bh = (h / ds).coerceAtLeast(4)
        var bg = IntArray(bw * bh)
        for (by in 0 until bh) {
            val sy = (by * ds + ds / 2).coerceAtMost(h - 1)
            for (bx in 0 until bw) {
                val sx = (bx * ds + ds / 2).coerceAtMost(w - 1)
                bg[by * bw + bx] = lum[sy * w + sx]
            }
        }
        repeat(2) {
            val tmp = IntArray(bw * bh)
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    var sum = 0
                    var cnt = 0
                    for (dy in -2..2) {
                        val ny = y + dy
                        if (ny in 0 until bh) {
                            for (dx in -2..2) {
                                val nx = x + dx
                                if (nx in 0 until bw) {
                                    sum += bg[ny * bw + nx]
                                    cnt++
                                }
                            }
                        }
                    }
                    tmp[y * bw + x] = sum / max(cnt, 1)
                }
            }
            bg = tmp
        }

        // Bagi luminansi piksel dengan peta pencahayaan latar + perkuat kontras tinta spidol
        for (y in 0 until h) {
            val by = (y / ds).coerceIn(0, bh - 1)
            for (x in 0 until w) {
                val bx = (x / ds).coerceIn(0, bw - 1)
                val bgVal = bg[by * bw + bx].coerceAtLeast(45)
                val idx = y * w + x
                val ratio = (lum[idx].toFloat() / bgVal.toFloat()).coerceIn(0f, 1.35f)
                // Kurva S-stretch: tinta spidol (< 0.78) dipekatkan, papan (> 0.90) diputihkan
                val stretched = when {
                    ratio < 0.72f -> (ratio * 0.45f)
                    ratio > 0.91f -> 1.0f
                    else -> ((ratio - 0.72f) / 0.19f).pow(1.35f)
                }
                val v = (stretched * 255f).roundToInt().coerceIn(0, 255)
                pixels[idx] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun recognizeImageText(context: Context, imageUri: Uri, onResult: (Boolean, String) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, imageUri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    recognizer.close()
                    onResult(true, formatSortedVisionText(visionText))
                }
                .addOnFailureListener {
                    recognizer.close()
                    onResult(false, "")
                }
        } catch (_: Exception) {
            onResult(false, "")
        }
    }

    private fun formatSortedVisionText(visionText: Text): String {
        val allLines = mutableListOf<Text.Line>()
        for (block in visionText.textBlocks) {
            allLines.addAll(block.lines)
        }
        if (allLines.isEmpty()) return visionText.text.trim()

        allLines.sortWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))

        val rows = mutableListOf<MutableList<Text.Line>>()
        for (ln in allLines) {
            val box = ln.boundingBox
            if (box == null || rows.isEmpty()) {
                rows.add(mutableListOf(ln))
                continue
            }
            val lastRow = rows.last()
            val refBox = lastRow.first().boundingBox
            if (refBox != null) {
                val refCenterY = (refBox.top + refBox.bottom) / 2
                val curCenterY = (box.top + box.bottom) / 2
                val tolerance = max(refBox.height(), box.height()) * 0.55f
                if (abs(curCenterY - refCenterY) <= tolerance) {
                    lastRow.add(ln)
                    continue
                }
            }
            rows.add(mutableListOf(ln))
        }

        return rows.joinToString("\n") { row ->
            row.sortBy { it.boundingBox?.left ?: 0 }
            row.joinToString("   ") { it.text.trim() }
        }.trim()
    }

    // ==================== 4. SEARCH SNIPPET & DOSSIER PDF EXPORT ====================

    fun makeSnippet(text: String, lowerQuery: String): String {
        val clean = text.replace("[BAB] ", "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return "-"
        val idx = clean.lowercase().indexOf(lowerQuery)
        if (idx < 0) return clean.take(110) + if (clean.length > 110) "..." else ""
        val start = (idx - 38).coerceAtLeast(0)
        val end = (idx + lowerQuery.length + 68).coerceAtMost(clean.length)
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
        val formulas = db.listVaultFormulas(courseKey)

        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas

        val bannerPaint = Paint().apply { color = Color.parseColor("#312E81") }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0E7FF")
            textSize = 10.5f
        }
        val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E1B4B")
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 10f
        }
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#92400E")
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }

        canvas.drawRoundRect(RectF(32f, 32f, (pageWidth - 32).toFloat(), 106f), 14f, 14f, bannerPaint)
        canvas.drawText("DOSSIER PERTEMUAN KULIAH", 48f, 62f, titlePaint)
        canvas.drawText("${course.name} • Tanggal: $dateKey • AbsenGuard × KuliahVault IE", 48f, 84f, subPaint)

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
            val clean = text.replace("[BAB] ", "").replace(Regex("\\s+"), " ").trim()
            var i = 0
            while (i < clean.length) {
                val end = (i + maxChars).coerceAtMost(clean.length)
                val slice = clean.substring(i, end)
                ensureSpace(16f)
                canvas.drawText(slice, indent, y, paint)
                y += 15f
                i = end
            }
        }

        ensureSpace(24f)
        canvas.drawText("1. INTISARI MATERI PDF (${pdfs.size} DOKUMEN)", 36f, y, secPaint)
        y += 18f
        if (pdfs.isEmpty()) {
            drawWrapped("- Tidak ada PDF diunggah pada tanggal ini.", bodyPaint)
        } else {
            pdfs.forEach { p ->
                val fullPdf = db.getVaultPdf(p.id) ?: p
                val analysis = parseAnalysisJson(fullPdf.analysisJson)
                drawWrapped("• ${fullPdf.title} (${fullPdf.pageCount} hal)", secPaint)
                analysis.summary.forEach { s -> drawWrapped("  - $s", bodyPaint, 46f) }
                y += 6f
            }
        }

        y += 12f
        ensureSpace(24f)
        canvas.drawText("2. BANK RUMUS MATA KULIAH (${formulas.size} RUMUS)", 36f, y, secPaint)
        y += 18f
        if (formulas.isEmpty()) {
            drawWrapped("- Belum ada rumus tersimpan.", bodyPaint)
        } else {
            formulas.forEach { f ->
                drawWrapped("• ${f.title}: ${f.expression}", monoPaint)
                if (f.notes.isNotBlank()) drawWrapped("  Ket: ${f.notes}", bodyPaint, 46f)
            }
        }

        y += 12f
        ensureSpace(24f)
        canvas.drawText("3. CATATAN PAPAN TULIS OCR (${boards.size} FOTO)", 36f, y, secPaint)
        y += 18f
        if (boards.isEmpty()) {
            drawWrapped("- Tidak ada scan papan tulis pada tanggal ini.", bodyPaint)
        } else {
            boards.forEachIndexed { idx, b ->
                drawWrapped("• Papan #${idx + 1}: ${b.ocrText.ifBlank { "(Tanpa teks)" }}", bodyPaint)
            }
        }

        y += 12f
        ensureSpace(24f)
        canvas.drawText("4. TRANSKRIP REKAMAN DOSEN (${recs.size} SESI)", 36f, y, secPaint)
        y += 18f
        if (recs.isEmpty()) {
            drawWrapped("- Tidak ada rekaman dosen pada tanggal ini.", bodyPaint)
        } else {
            recs.forEach { r ->
                drawWrapped("• ${r.title}: ${r.transcriptText.ifBlank { "(Mode Audio HD)" }}", bodyPaint)
            }
        }

        pdf.finishPage(page)
        val outDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val outFile = File(outDir, "Dossier_${courseKey}_${dateKey}_$stamp.pdf")
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()
        return outFile
    }
}
