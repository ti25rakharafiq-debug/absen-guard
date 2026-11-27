package com.absenguard.app

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class TranscriptChapter(
    val title: String,
    val startMs: Long,
    val segments: List<RecordingSegment>
)

data class LectureSummaryResult(
    val keyPoints: List<String>,
    val examCues: List<String>,
    val bookmarks: List<RecordingSegment>,
    val chapters: List<TranscriptChapter>
)

object KuliahTranscriptEngine {

    // Kamus koreksi fonetik istilah Teknik Industri & perkuliahan (Indonesia - Inggris)
    private val PHONETIC_CORRECTIONS: List<Pair<Regex, String>> = listOf(
        Regex("\\b(taim|time)\\s*(stadi|studi|study)\\b", RegexOption.IGNORE_CASE) to "time study",
        Regex("\\b(work|wek)\\s*(sempling|sampling)\\b", RegexOption.IGNORE_CASE) to "work sampling",
        Regex("\\b(botol|bottle)\\s*(nek|neck)\\b", RegexOption.IGNORE_CASE) to "bottleneck",
        Regex("\\b(elowen|alowens|allowance|alowen)\\b", RegexOption.IGNORE_CASE) to "allowance",
        Regex("\\b(terblik|terblig|therblik|therblig)\\b", RegexOption.IGNORE_CASE) to "Therblig",
        Regex("\\b(westing\\s*house|westinghouse)\\b", RegexOption.IGNORE_CASE) to "Westinghouse",
        Regex("\\b(antropo\\s*metri|antropometri)\\b", RegexOption.IGNORE_CASE) to "antropometri",
        Regex("\\b(ergo\\s*nomi|ergonomi)\\b", RegexOption.IGNORE_CASE) to "ergonomi",
        Regex("\\b(bio\\s*mekanika|biomekanika)\\b", RegexOption.IGNORE_CASE) to "biomekanika",
        Regex("\\b(simplex|sim\\s*plek|simpleks)\\b", RegexOption.IGNORE_CASE) to "simpleks",
        Regex("\\b(linear|linier)\\s*(programing|programming)\\b", RegexOption.IGNORE_CASE) to "linear programming",
        Regex("\\b(stepping|steping)\\s*(stone|ston)\\b", RegexOption.IGNORE_CASE) to "stepping stone",
        Regex("\\b(north\\s*west|northwest)\\s*(corner|korner)\\b", RegexOption.IGNORE_CASE) to "northwest corner",
        Regex("\\b(shadow|sedo)\\s*(price|prais)\\b", RegexOption.IGNORE_CASE) to "shadow price",
        Regex("\\b(break\\s*event|break\\s*even)\\s*(point|poin)?\\b", RegexOption.IGNORE_CASE) to "Break-Even Point",
        Regex("\\b(over\\s*head|overhead)\\b", RegexOption.IGNORE_CASE) to "overhead",
        Regex("\\b(learning|lerning)\\s*(curve|kurva|kerf)\\b", RegexOption.IGNORE_CASE) to "learning curve",
        Regex("\\b(life\\s*cycle|laif\\s*saikel)\\s*(cost|kos)\\b", RegexOption.IGNORE_CASE) to "life cycle cost",
        Regex("\\b(marketing|market\\s*ing)\\s*(mix|miks)\\b", RegexOption.IGNORE_CASE) to "marketing mix",
        Regex("\\b(segmentasi|segmen\\s*tasi)\\b", RegexOption.IGNORE_CASE) to "segmentasi",
        Regex("\\b(positioning|posisi\\s*oning)\\b", RegexOption.IGNORE_CASE) to "positioning",
        Regex("\\b(flow\\s*chart|flocat)\\b", RegexOption.IGNORE_CASE) to "flowchart",
        Regex("\\b(pseudo\\s*code|sudo\\s*code|sudokod)\\b", RegexOption.IGNORE_CASE) to "pseudocode",
        Regex("\\b(for\\s*loop|forlup)\\b", RegexOption.IGNORE_CASE) to "for loop",
        Regex("\\b(if\\s*else|if\\s*els)\\b", RegexOption.IGNORE_CASE) to "if-else",
        Regex("\\b(bayes|bayes)\\b", RegexOption.IGNORE_CASE) to "Bayes",
        Regex("\\b(poisson|poison|puason)\\b", RegexOption.IGNORE_CASE) to "Poisson",
        Regex("\\b(bernoulli|bernuli)\\b", RegexOption.IGNORE_CASE) to "Bernoulli",
        Regex("\\b(binomial|bi\\s*nomial)\\b", RegexOption.IGNORE_CASE) to "binomial",
        Regex("\\b(eksponensial|eksponen\\s*sial)\\b", RegexOption.IGNORE_CASE) to "eksponensial",
        Regex("\\b(diagram\\s*fasa|diagram\\s*fase)\\b", RegexOption.IGNORE_CASE) to "diagram fasa",
        Regex("\\b(perlakuan\\s*panas|heat\\s*treatment)\\b", RegexOption.IGNORE_CASE) to "perlakuan panas",
        Regex("\\b(bhinneka\\s*tunggal\\s*ika|bineka\\s*tunggal\\s*ika)\\b", RegexOption.IGNORE_CASE) to "Bhinneka Tunggal Ika",
        Regex("\\b(uud\\s*45|undang\\s*undang\\s*dasar\\s*1945)\\b", RegexOption.IGNORE_CASE) to "UUD 1945"
    )

    private val ACRONYMS_UPPER = setOf(
        "eoq", "rop", "oee", "npv", "irr", "bep", "hpp", "bop", "rab", "sop", "k3", "apd",
        "ubp", "uts", "uas", "mtm", "wip", "mtbf", "mttr", "swot", "b2b", "b2c", "bod", "cod", "amdal"
    )

    private val EXAM_CUE_PATTERNS = listOf(
        Regex("\\b(keluar di ujian|keluar di uts|keluar di uas|soal uts|soal uas|kisi[- ]?kisi)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(catat baik[- ]?baik|tolong dicatat|wajib dicatat|jangan sampai lupa|jangan lupa)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(perhatikan rumus|rumus penting|poin penting|kuncinya adalah|inti dari)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(sering ditanyakan|sering muncul|pasti keluar|tugas minggu depan|quiz|kuis)\\b", RegexOption.IGNORE_CASE)
    )

    private val TOPIC_TRANSITION_PATTERNS = listOf(
        Regex("^(selanjutnya|berikutnya|sekarang kita masuk|kita lanjut ke|materi berikutnya|bab berikutnya)\\b", RegexOption.IGNORE_CASE),
        Regex("^(yang pertama|yang kedua|yang ketiga|poin pertama|poin kedua|langkah pertama)\\b", RegexOption.IGNORE_CASE),
        Regex("^(nah sekarang|oke sekarang|baik sekarang kita bahas)\\b", RegexOption.IGNORE_CASE)
    )

    private val QUESTION_STARTERS = listOf(
        "apa ", "apakah ", "kenapa ", "mengapa ", "bagaimana ", "gimana ",
        "berapa ", "kapan ", "siapa ", "mana ", "ada pertanyaan", "sudah paham", "jelas ya"
    )

    /**
     * Membersihkan gumaman, memperbaiki istilah Teknik Industri, kapitalisasi, & tanda baca otomatis.
     */
    fun cleanAndFormatUtterance(raw: String, courseKey: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        // Biarkan bookmark & catatan manual mahasiswa tetap utuh
        if (text.startsWith("⭐") || text.startsWith("[CATATAN]")) return text

        // 1. Bersihkan kata gumaman berulang di awal/tengah kalimat
        text = text
            .replace(Regex("^(eee+|emm+|hmm+|anu+|nah\\s+jadi|oke\\s+baik|ya\\s+jadi)[,\\s]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(eee+|emm+|hmm+|anu+)\\s+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        if (text.isEmpty()) return ""

        // 2. Terapkan kamus fonetik istilah Teknik Industri
        for ((pattern, replacement) in PHONETIC_CORRECTIONS) {
            text = pattern.replace(text, replacement)
        }

        // 3. Kapitalisasi singkatan teknis (EOQ, OEE, BEP, HPP, UBP, UTS, UAS, dll.)
        text = text.split(" ").joinToString(" ") { token ->
            val bare = token.trim(',', '.', '?', '!', ':', ';').lowercase()
            if (bare in ACRONYMS_UPPER) {
                token.uppercase(Locale.getDefault())
            } else {
                token
            }
        }

        // 4. Sisipkan koma sebelum kata penghubung klausa panjang
        text = text.replace(
            Regex("\\s+(sehingga|sedangkan|tetapi|namun|yaitu|misalnya|contohnya)\\s+", RegexOption.IGNORE_CASE)
        ) { m ->
            ", ${m.groupValues[1].lowercase()} "
        }

        // 5. Kapitalisasi huruf pertama kalimat
        text = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        // 6. Tambahkan tanda tanya (?) atau titik (.) otomatis di akhir kalimat
        val lastChar = text.last()
        if (lastChar !in charArrayOf('.', '?', '!', ':')) {
            val lower = text.lowercase()
            val isQuestion = QUESTION_STARTERS.any { lower.startsWith(it) || lower.endsWith(it.trim()) } ||
                lower.contains(" berapa ") || lower.contains(" kenapa ") || lower.contains(" bagaimana ")
            text += if (isQuestion) "?" else "."
        }

        // 7. Tandai otomatis jika dosen menyebut kisi-kisi ujian / poin krusial
        if (isExamOrImportantCue(text) && !text.startsWith("[KISI-KISI]")) {
            text = "[KISI-KISI] $text"
        }

        return text
    }

    fun isExamOrImportantCue(text: String): Boolean {
        return EXAM_CUE_PATTERNS.any { it.containsMatchIn(text) }
    }

    fun isTopicTransition(text: String): Boolean {
        val clean = text.removePrefix("[KISI-KISI] ").trim()
        return TOPIC_TRANSITION_PATTERNS.any { it.containsMatchIn(clean) }
    }

    /**
     * Menganalisis seluruh segmen transkrip kuliah menjadi:
     * - Ringkasan Poin Inti Kuliah
     * - Daftar Kisi-Kisi / Penekanan Dosen
     * - Daftar Bookmark Mahasiswa
     * - Pembagian Bab Topik Otomatis
     */
    fun analyzeLectureTranscript(segments: List<RecordingSegment>, courseKey: String): LectureSummaryResult {
        if (segments.isEmpty()) {
            return LectureSummaryResult(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val bookmarks = segments.filter { it.text.startsWith("⭐") || it.text.startsWith("[CATATAN]") }
        val examCues = segments
            .filter { it.text.startsWith("[KISI-KISI]") || isExamOrImportantCue(it.text) }
            .map { it.text.removePrefix("[KISI-KISI] ").trim() }
            .distinct()
            .take(10)

        // Bagi transkrip menjadi bab topik berdasarkan jeda waktu > 12 detik atau frasa transisi
        val chapters = mutableListOf<TranscriptChapter>()
        var currentSegs = mutableListOf<RecordingSegment>()
        var chapterStart = segments.first().startMs
        var chapterIndex = 1

        for (i in segments.indices) {
            val seg = segments[i]
            val prev = segments.getOrNull(i - 1)
            val gapMs = if (prev != null) (seg.startMs - prev.endMs) else 0L
            val splitHere = i > 0 && currentSegs.size >= 3 && (gapMs >= 12_000L || isTopicTransition(seg.text))

            if (splitHere) {
                chapters.add(
                    TranscriptChapter(
                        title = buildChapterTitle(chapterIndex, currentSegs),
                        startMs = chapterStart,
                        segments = currentSegs.toList()
                    )
                )
                chapterIndex++
                currentSegs = mutableListOf()
                chapterStart = seg.startMs
            }
            currentSegs.add(seg)
        }
        if (currentSegs.isNotEmpty()) {
            chapters.add(
                TranscriptChapter(
                    title = buildChapterTitle(chapterIndex, currentSegs),
                    startMs = chapterStart,
                    segments = currentSegs.toList()
                )
            )
        }

        // Ekstrak poin inti terpenting dari penjelasan dosen
        val domainKeywords = (KuliahVaultEngine.COURSE_KEYWORDS[KuliahVaultEngine.courseByKey(courseKey).base]
            ?: KuliahVaultEngine.COURSE_KEYWORDS.values.flatten())
            .map { it.first }
            .toSet()

        val scored = segments
            .filter { !it.text.startsWith("⭐") && !it.text.startsWith("[CATATAN]") }
            .mapIndexed { idx, seg ->
                val clean = seg.text.removePrefix("[KISI-KISI] ").trim()
                val lower = clean.lowercase()
                var score = (clean.length.coerceIn(20, 180) / 35.0)
                if (domainKeywords.any { lower.contains(it) }) score += 3.0
                if (seg.text.startsWith("[KISI-KISI]")) score += 3.5
                if (lower.contains("adalah") || lower.contains("merupakan") || lower.contains("rumus") || lower.contains("tujuan") || lower.contains("langkah")) {
                    score += 1.8
                }
                Triple(idx, clean, score)
            }
            .filter { it.second.length >= 28 }
            .sortedByDescending { it.third }
            .distinctBy { it.second.lowercase().take(40) }
            .take(6)
            .sortedBy { it.first }
            .map { it.second }

        return LectureSummaryResult(
            keyPoints = scored,
            examCues = examCues,
            bookmarks = bookmarks,
            chapters = chapters
        )
    }

    private fun buildChapterTitle(index: Int, segs: List<RecordingSegment>): String {
        val sample = segs.firstOrNull { !it.text.startsWith("⭐") && !it.text.startsWith("[CATATAN]") }
            ?.text
            ?.removePrefix("[KISI-KISI] ")
            ?.trim()
            .orEmpty()
        val shortTopic = sample.take(44).trimEnd('.', ',', '?')
        return if (shortTopic.length >= 12) "Bagian $index • $shortTopic..." else "Bagian $index • Pembahasan Kuliah"
    }

    /**
     * Mengekspor transkrip kuliah lengkap beserta ringkasan & penanda waktu ke file PDF siap cetak/bagikan.
     */
    fun exportTranscriptPdf(context: Context, rec: VaultRecording, courseName: String): File {
        val analysis = analyzeLectureTranscript(rec.segments, rec.courseKey)
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas

        val bannerPaint = Paint().apply { color = Color.parseColor("#312E81") }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0E7FF")
            textSize = 10f
        }
        val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E1B4B")
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 10f
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4338CA")
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
        }

        val dateStr = SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(rec.createdAt))
        canvas.drawRoundRect(RectF(32f, 32f, (pageWidth - 32).toFloat(), 104f), 14f, 14f, bannerPaint)
        canvas.drawText("TRANSKRIP & RINGKASAN KULIAH", 48f, 62f, titlePaint)
        canvas.drawText("$courseName • $dateStr • AbsenGuard × KuliahVault IE", 48f, 84f, subPaint)

        var y = 130f
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

        if (analysis.keyPoints.isNotEmpty()) {
            ensureSpace(24f)
            canvas.drawText("1. INTISARI PENJELASAN DOSEN", 36f, y, secPaint)
            y += 18f
            analysis.keyPoints.forEach { kp ->
                drawWrapped("• $kp", bodyPaint)
            }
            y += 10f
        }

        if (analysis.examCues.isNotEmpty()) {
            ensureSpace(24f)
            canvas.drawText("2. KISI-KISI & PENEKANAN PENTING", 36f, y, secPaint)
            y += 18f
            analysis.examCues.forEach { cue ->
                drawWrapped("★ $cue", bodyPaint)
            }
            y += 10f
        }

        ensureSpace(24f)
        canvas.drawText("3. TRANSKRIP LENGKAP BERSTEMPEL WAKTU", 36f, y, secPaint)
        y += 18f
        if (rec.segments.isEmpty()) {
            drawWrapped("(Sesi ini direkam dalam mode Audio HD tanpa transkrip otomatis)", bodyPaint)
        } else {
            rec.segments.forEach { seg ->
                val totalSec = max(0L, seg.startMs / 1000L)
                val stamp = String.format(Locale.US, "[%02d:%02d]", totalSec / 60L, totalSec % 60L)
                ensureSpace(16f)
                canvas.drawText(stamp, 36f, y, timePaint)
                drawWrapped(seg.text, bodyPaint, 86f)
                y += 3f
            }
        }

        pdf.finishPage(page)
        val outDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val outFile = File(outDir, "Transkrip_${rec.courseKey}_$fileStamp.pdf")
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()
        return outFile
    }
}
