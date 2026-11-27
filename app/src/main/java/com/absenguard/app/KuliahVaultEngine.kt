package com.absenguard.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Inflater
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

data class TextAnalysisResult(
    val summary: List<String>,
    val definitions: List<Pair<String, String>>,
    val formulas: List<Pair<String, String>>
)

object KuliahVaultEngine {

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
                while (idx >= 0 && count < 8) {
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

    fun analyzeText(text: String): TextAnalysisResult {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 20) return TextAnalysisResult(emptyList(), emptyList(), emptyList())

        val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length in 35..260 }

        val summary = sentences.take(5)

        val definitions = mutableListOf<Pair<String, String>>()
        val defRegex = Regex("([A-Z][A-Za-z0-9\\s\\-()]{2,45})\\s+(adalah|merupakan|yaitu|ialah)\\s+([^.!?]{15,180}[.!?])")
        for (m in defRegex.findAll(clean)) {
            val term = m.groupValues[1].trim()
            val desc = m.groupValues[3].trim()
            if (definitions.none { it.first.equals(term, ignoreCase = true) }) {
                definitions.add(term to desc)
            }
            if (definitions.size >= 12) break
        }

        val formulas = extractFormulas(text)
        return TextAnalysisResult(summary, definitions, formulas)
    }

    fun extractFormulas(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val lines = text.split("\n", ";")
        for (raw in lines) {
            val line = raw.trim()
            if (line.length in 5..90 && line.contains("=") && line.any { it.isDigit() || it in listOf('+', '-', '*', '/', '^', '∑', '√', 'μ', 'σ', 'λ') }) {
                val left = line.substringBefore("=").trim()
                val label = if (left.length in 1..30) "Rumus $left" else "Rumus"
                if (out.none { it.second == line }) {
                    out.add(label to line)
                }
                if (out.size >= 15) break
            }
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
            TextAnalysisResult(summary, defs, forms)
        } catch (_: Exception) {
            TextAnalysisResult(emptyList(), emptyList(), emptyList())
        }
    }

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

    fun extractPdfInfo(file: File): PdfExtractionResult {
        return try {
            var pageCount = 0
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount
                }
            }
            val bytes = file.readBytes()
            val extracted = extractRawPdfText(bytes)
            val scanned = extracted.replace(Regex("\\s+"), "").length < 40
            PdfExtractionResult(ok = true, pageCount = pageCount, text = extracted, scanned = scanned)
        } catch (e: Exception) {
            PdfExtractionResult(ok = false, pageCount = 0, text = "", scanned = false, error = e.message ?: "Gagal membaca PDF")
        }
    }

    private fun extractRawPdfText(bytes: ByteArray): String {
        val latin = String(bytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        val streamRegex = Regex("stream\\r?\\n([\\s\\S]*?)\\r?\\nendstream")
        for (match in streamRegex.findAll(latin)) {
            val rawStream = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
            val decoded = tryInflate(rawStream) ?: rawStream
            val chunk = String(decoded, Charsets.ISO_8859_1)
            if (chunk.contains("BT") && chunk.contains("ET")) {
                val textParen = Regex("\\(([^()\\\\]{1,200})\\)\\s*(?:Tj|'|\\\")")
                for (tm in textParen.findAll(chunk)) {
                    sb.append(tm.groupValues[1]).append(' ')
                }
                val tjArr = Regex("\\[(.*?)\\]\\s*TJ", RegexOption.DOT_MATCHES_ALL)
                for (am in tjArr.findAll(chunk)) {
                    val inner = Regex("\\(([^()\\\\]{1,200})\\)")
                    for (im in inner.findAll(am.groupValues[1])) {
                        sb.append(im.groupValues[1])
                    }
                    sb.append(' ')
                }
                sb.append('\n')
            }
        }
        return sb.toString().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun tryInflate(data: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0) break
                out.write(buf, 0, count)
            }
            inflater.end()
            if (out.size() > 0) out.toByteArray() else null
        } catch (_: Exception) {
            null
        }
    }

    fun enhanceWhiteboard(context: Context, sourceUri: Uri): File {
        val dir = File(context.filesDir, "kuliahvault/papan").apply { mkdirs() }
        val outFile = File(dir, "scan_tajam_${System.currentTimeMillis()}.jpg")
        val raw = context.contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Gagal memuat gambar")

        val maxDim = 1800
        val scale = if (raw.width > maxDim || raw.height > maxDim) {
            maxDim.toFloat() / maxOf(raw.width, raw.height).toFloat()
        } else 1f
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
        } else raw

        val result = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val contrast = 1.65f
        val translate = (-0.5f * contrast + 0.5f) * 255f + 18f
        val cm = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, translate,
                        0f, contrast, 0f, 0f, translate,
                        0f, 0f, contrast, 0f, translate,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        FileOutputStream(outFile).use { result.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return outFile
    }

    fun recognizeImageText(context: Context, imageUri: Uri, onComplete: (Boolean, String) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, imageUri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    onComplete(true, visionText.text.trim())
                }
                .addOnFailureListener { e ->
                    onComplete(false, e.message ?: "OCR gagal")
                }
        } catch (e: Exception) {
            onComplete(false, e.message ?: "Gambar tidak dapat diproses")
        }
    }

    fun exportMeetingDossierPdf(
        context: Context,
        db: ScheduleDbHelper,
        courseKey: String,
        dateKey: String
    ): File {
        val course = courseByKey(courseKey)
        val (pdfs, recs, boards) = db.getVaultMeetingMaterial(courseKey, dateKey)
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = 48f

        val titlePaint = Paint().apply { color = Color.parseColor("#0F172A"); textSize = 18f; isFakeBoldText = true }
        val h2Paint = Paint().apply { color = Color.parseColor("#1E3A8A"); textSize = 13f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { color = Color.parseColor("#1E293B"); textSize = 10.5f }
        val mutedPaint = Paint().apply { color = Color.parseColor("#64748B"); textSize = 9.5f }

        fun ensureSpace(needed: Float) {
            if (y + needed > pageHeight - 48f) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                y = 48f
            }
        }

        fun drawWrapped(text: String, paint: Paint, indent: Float = 40f) {
            val maxW = pageWidth - indent - 40f
            for (paragraph in text.split("\n")) {
                val words = paragraph.split(" ")
                var line = ""
                for (w in words) {
                    val test = if (line.isEmpty()) w else "$line $w"
                    if (paint.measureText(test) > maxW && line.isNotEmpty()) {
                        ensureSpace(16f)
                        canvas.drawText(line, indent, y, paint)
                        y += 15f
                        line = w
                    } else {
                        line = test
                    }
                }
                if (line.isNotEmpty()) {
                    ensureSpace(16f)
                    canvas.drawText(line, indent, y, paint)
                    y += 15f
                }
            }
        }

        canvas.drawText("DOSSIER PERTEMUAN KULIAH", 40f, y, titlePaint)
        y += 22f
        canvas.drawText("${course.name}  •  Tanggal: $dateKey", 40f, y, h2Paint)
        y += 16f
        canvas.drawText("Diekspor oleh AbsenGuard × KuliahVault IE", 40f, y, mutedPaint)
        y += 26f

        canvas.drawText("1. Materi PDF (${pdfs.size})", 40f, y, h2Paint)
        y += 18f
        if (pdfs.isEmpty()) {
            drawWrapped("- Tidak ada PDF pada pertemuan ini.", mutedPaint)
        } else {
            pdfs.forEach { p ->
                drawWrapped("• ${p.title} (${p.pageCount} hal.)", bodyPaint)
                val analysis = parseAnalysisJson(p.analysisJson)
                analysis.summary.take(2).forEach { s -> drawWrapped("  Ringkasan: $s", mutedPaint, 52f) }
            }
        }
        y += 14f

        ensureSpace(40f)
        canvas.drawText("2. Rekaman & Transkrip Dosen (${recs.size})", 40f, y, h2Paint)
        y += 18f
        if (recs.isEmpty()) {
            drawWrapped("- Tidak ada rekaman pada pertemuan ini.", mutedPaint)
        } else {
            recs.forEach { r ->
                drawWrapped("• ${r.title}", bodyPaint)
                if (r.transcriptText.isNotBlank()) {
                    drawWrapped(r.transcriptText.take(1200), mutedPaint, 52f)
                }
            }
        }
        y += 14f

        ensureSpace(40f)
        canvas.drawText("3. Catatan Papan Tulis OCR (${boards.size})", 40f, y, h2Paint)
        y += 18f
        if (boards.isEmpty()) {
            drawWrapped("- Tidak ada scan papan tulis pada pertemuan ini.", mutedPaint)
        } else {
            boards.forEachIndexed { idx, b ->
                val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(b.createdAt))
                drawWrapped("• Papan Tulis #${idx + 1} ($timeStr)", bodyPaint)
                drawWrapped(b.ocrText.ifBlank { "(Tanpa teks terdeteksi)" }.take(1200), mutedPaint, 52f)
            }
        }

        doc.finishPage(page)
        val outDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val outFile = File(outDir, "Dossier_${courseKey}_$dateKey.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    fun makeSnippet(text: String, queryLower: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val idx = clean.lowercase().indexOf(queryLower)
        if (idx < 0) return clean.take(120)
        val start = maxOf(0, idx - 40)
        val end = minOf(clean.length, idx + queryLower.length + 70)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < clean.length) "..." else ""
        return prefix + clean.substring(start, end) + suffix
    }
}
