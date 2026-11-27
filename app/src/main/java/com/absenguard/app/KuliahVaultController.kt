package com.absenguard.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KuliahVaultController(
    private val activity: MainActivity,
    private val db: ScheduleDbHelper,
    private val isDarkProvider: () -> Boolean,
    private val onRequestPickPdf: () -> Unit,
    private val onRequestCapturePhoto: (Uri) -> Unit,
    private val onRequestPickGallery: () -> Unit,
    private val onRequestAudioPermission: () -> Unit,
    private val onRequestCameraPermission: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // PDF navigation state
    private var selectedPdfFolder: String? = null

    // Recording state (supports Pause/Resume, Live Bookmarks, IE Phonetic & Punctuation Engine)
    private var recordCourseKey: String = "umum"
    private var isRecording = false
    private var isPaused = false
    private var recordModeStt = true
    private var sessionCreatedEpochMs = 0L
    private var accumulatedRecordMs = 0L
    private var activeSegmentStartEpochMs = 0L
    private var lastSegmentEndMs = 0L
    private var currentSegStartMs = -1L
    private var interimTranscript = ""
    private val liveSegments = mutableListOf<RecordingSegment>()
    private val recordedParts = mutableListOf<RecordingPart>()
    private var currentAudioFile: File? = null
    private var mediaRecorder: MediaRecorder? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var timerTextView: TextView? = null
    private var liveTranscriptTextView: TextView? = null

    // Board state
    private var boardCourseKey: String = "umum"
    private var boardCourseConfirmed = false
    private var pendingCameraFile: File? = null

    // Study & Search state
    private var studyCourseKey: String = "umum"
    private var searchQuery: String = ""

    private var activeTabRenderer: (() -> Unit)? = null

    fun syncDefaultCourseFromSchedule() {
        val next = AlarmScheduler.getNextUpcomingSchedule(activity)
        if (next != null) {
            val mapped = KuliahVaultEngine.mapScheduleNameToCourseKey(next.first.courseName)
            if (mapped != "umum") {
                if (!boardCourseConfirmed) boardCourseKey = mapped
                if (!isRecording) recordCourseKey = mapped
                if (studyCourseKey == "umum") studyCourseKey = mapped
            }
        }
    }

    // ==================== 1. TAB MATERI PDF ====================

    fun renderPdfTab(container: LinearLayout) {
        activeTabRenderer = { renderPdfTab(container) }
        container.removeAllViews()

        val folder = selectedPdfFolder
        if (folder == null) {
            container.addView(sectionHeader("Materi PDF Kuliah", "Layout-aware parser: otomatis pisah bab, poin inti & rumus TI"))
            container.addView(space(12))

            val importBtn = glassActionButton("+ Impor PDF Baru", "#4F46E5") {
                onRequestPickPdf()
            }
            container.addView(importBtn, matchWrap())
            container.addView(space(14))

            val counts = db.getVaultCourseCounts()
            for (course in KuliahVaultEngine.VAULT_COURSES) {
                val count = counts[course.key]?.pdf ?: 0
                val isUmum = course.key == "umum"
                val card = glassCard(if (isUmum) (if (isDark()) "#33D97706" else "#24F59E0B") else null).apply {
                    setOnClickListener {
                        selectedPdfFolder = course.key
                        renderPdfTab(container)
                    }
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val badge = TextView(activity).apply {
                    text = if (isUmum) "UMUM" else "PDF"
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = LiquidGlassKit.pillGlassSurface(
                        activity,
                        if (isUmum) "#D97706" else "#0284C7",
                        "#80F8FAFC",
                        12f
                    )
                }
                val info = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(12)
                    }
                }
                info.addView(TextView(activity).apply {
                    text = course.name
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textColor())
                })
                info.addView(TextView(activity).apply {
                    text = "$count dokumen tersimpan"
                    textSize = 12f
                    setTextColor(subTextColor())
                })
                val arrow = TextView(activity).apply {
                    text = "→"
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accentTextColor())
                }
                row.addView(badge)
                row.addView(info)
                row.addView(arrow)
                card.addView(row)
                container.addView(card, matchWrapBottom(10))
            }
        } else {
            val course = KuliahVaultEngine.courseByKey(folder)
            val pdfs = db.listVaultPdfs(folder)

            val topRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val backBtn = Button(activity).apply {
                text = "← Folder"
                isAllCaps = false
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor())
                background = LiquidGlassKit.glassSurface(activity, isDark(), 16f)
                setOnClickListener {
                    selectedPdfFolder = null
                    renderPdfTab(container)
                }
                LiquidGlassKit.attachLiquidTouch(this)
            }
            val titleCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
            }
            titleCol.addView(TextView(activity).apply {
                text = course.name
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor())
            })
            titleCol.addView(TextView(activity).apply {
                text = "${pdfs.size} PDF di folder ini"
                textSize = 12f
                setTextColor(subTextColor())
            })
            topRow.addView(backBtn)
            topRow.addView(titleCol)
            container.addView(topRow)
            container.addView(space(12))

            val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val importHereBtn = glassActionButton("+ Impor PDF", "#0284C7") {
                onRequestPickPdf()
            }
            val exportDossierBtn = glassActionButton("Export Dossier Pertemuan", "#2E8B57") {
                showDossierDatePicker(folder)
            }
            actionRow.addView(importHereBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
            actionRow.addView(exportDossierBtn, LinearLayout.LayoutParams(0, dp(42), 1.35f).apply { marginStart = dp(6) })
            container.addView(actionRow)
            container.addView(space(14))

            if (pdfs.isEmpty()) {
                container.addView(emptyGlassBox("Folder ini masih kosong. Tekan '+ Impor PDF' untuk menambahkan materi."))
            } else {
                for (pdf in pdfs) {
                    val analysis = KuliahVaultEngine.parseAnalysisJson(pdf.analysisJson)
                    val card = glassCard().apply {
                        setOnClickListener { showPdfReaderDialog(pdf.id) }
                    }
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val info = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    info.addView(TextView(activity).apply {
                        text = pdf.title
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(textColor())
                    })
                    info.addView(TextView(activity).apply {
                        text = "${pdf.pageCount} halaman • ${formatDate(pdf.createdAt)}"
                        textSize = 12f
                        setTextColor(subTextColor())
                    })
                    info.addView(TextView(activity).apply {
                        text = "${analysis.sections.size} topik • ${analysis.definitions.size} istilah • ${analysis.formulaDetails.size} rumus"
                        textSize = 11f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(accentTextColor())
                    })
                    val menuBtn = TextView(activity).apply {
                        text = "⋮"
                        textSize = 20f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(textColor())
                        setPadding(dp(12), dp(6), dp(8), dp(6))
                        setOnClickListener { showPdfOptionsDialog(pdf, container) }
                    }
                    row.addView(info)
                    row.addView(menuBtn)
                    card.addView(row)
                    container.addView(card, matchWrapBottom(10))
                }
            }
        }
    }

    fun handlePickedPdfUri(uri: Uri) {
        val loadingDialog = AlertDialog.Builder(activity)
            .setTitle("Memproses Tata Letak PDF")
            .setMessage("Membaca struktur bab, merangkum poin inti & mendeteksi rumus...")
            .setCancelable(false)
            .show()

        Thread {
            try {
                val rawName = queryDisplayName(uri) ?: "Materi_${System.currentTimeMillis()}.pdf"
                val file = KuliahVaultEngine.importFileToInternalStorage(activity, uri, "pdf", rawName.removeSuffix(".pdf"), "pdf")
                val extraction = KuliahVaultEngine.extractPdfInfo(activity, file)
                if (!extraction.ok) {
                    mainHandler.post {
                        loadingDialog.dismiss()
                        file.delete()
                        AlertDialog.Builder(activity)
                            .setTitle("PDF Tidak Dapat Dibaca")
                            .setMessage(extraction.error)
                            .setPositiveButton("Tutup", null)
                            .show()
                    }
                    return@Thread
                }
                val cls = KuliahVaultEngine.classifyText(extraction.text)
                val analysis = KuliahVaultEngine.analyzeText(extraction.text)

                mainHandler.post {
                    loadingDialog.dismiss()
                    showPdfClassificationDialog(
                        defaultTitle = rawName.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), ""),
                        file = file,
                        extraction = extraction,
                        cls = cls,
                        analysis = analysis
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    loadingDialog.dismiss()
                    Toast.makeText(activity, "Gagal membaca PDF: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showPdfClassificationDialog(
        defaultTitle: String,
        file: File,
        extraction: PdfExtractionResult,
        cls: ClassificationResult,
        analysis: TextAnalysisResult
    ) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        val titleInput = EditText(activity).apply {
            hint = "Judul Materi PDF"
            setText(defaultTitle)
        }
        val detectInfo = TextView(activity).apply {
            val pct = (cls.share * 100).toInt()
            val kw = if (cls.matchedKeywords.isNotEmpty()) "\nKata kunci: ${cls.matchedKeywords.joinToString(", ")}" else ""
            val ocrNote = if (extraction.scanned) "\n✓ PDF hasil scan berhasil dibaca via OCR otomatis." else ""
            text = "Deteksi Otomatis: ${cls.label} ($pct%)\nTerdeteksi: ${analysis.sections.size} topik • ${analysis.formulaDetails.size} rumus • ${analysis.definitions.size} istilah$kw$ocrNote"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4338CA"))
            setPadding(0, dp(8), 0, dp(10))
        }
        val courseNames = KuliahVaultEngine.VAULT_COURSES.map { it.name }
        val defaultIdx = KuliahVaultEngine.VAULT_COURSES.indexOfFirst { it.key == cls.suggestedCourseKey }.coerceAtLeast(0)
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, courseNames)
            setSelection(defaultIdx)
        }
        box.addView(titleInput)
        box.addView(detectInfo)
        box.addView(TextView(activity).apply {
            text = "Simpan ke Folder Mata Kuliah:"
            textSize = 12f
        })
        box.addView(spinner)

        AlertDialog.Builder(activity)
            .setTitle("Klasifikasi Materi PDF")
            .setView(box)
            .setPositiveButton("Simpan PDF") { _, _ ->
                val chosenCourse = KuliahVaultEngine.VAULT_COURSES[spinner.selectedItemPosition]
                val finalTitle = titleInput.text.toString().trim().ifEmpty { defaultTitle }
                db.insertVaultPdf(
                    courseKey = chosenCourse.key,
                    title = finalTitle,
                    fileUri = file.absolutePath,
                    pageCount = extraction.pageCount,
                    bodyText = extraction.text,
                    analysisJson = KuliahVaultEngine.analysisToJson(analysis),
                    suggestedBase = cls.suggestedBase,
                    confidence = cls.share
                )
                selectedPdfFolder = chosenCourse.key
                Toast.makeText(activity, "Tersimpan di ${chosenCourse.name}", Toast.LENGTH_SHORT).show()
                activeTabRenderer?.invoke()
            }
            .setNegativeButton("Batal") { _, _ ->
                file.delete()
            }
            .show()
    }

    private fun showPdfOptionsDialog(pdf: VaultPdf, container: LinearLayout) {
        val opts = arrayOf("Buka Pembaca Terstruktur", "Analisis Ulang Tata Letak PDF", "Buka File PDF Asli", "Pindahkan ke Folder Lain", "Ganti Nama", "Hapus PDF")
        AlertDialog.Builder(activity)
            .setTitle(pdf.title)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showPdfReaderDialog(pdf.id)
                    1 -> reanalyzeStoredPdf(pdf.id, container)
                    2 -> openExternalPdf(pdf.fileUri)
                    3 -> showMoveCourseDialog(pdf.courseKey) { newKey ->
                        db.moveVaultPdf(pdf.id, newKey)
                        renderPdfTab(container)
                    }
                    4 -> showRenameDialog("Ganti Nama PDF", pdf.title) { newTitle ->
                        db.renameVaultPdf(pdf.id, newTitle)
                        renderPdfTab(container)
                    }
                    5 -> {
                        File(pdf.fileUri).delete()
                        db.deleteVaultPdf(pdf.id)
                        renderPdfTab(container)
                    }
                }
            }
            .show()
    }

    private fun reanalyzeStoredPdf(pdfId: Long, container: LinearLayout) {
        val pdf = db.getVaultPdf(pdfId) ?: return
        val file = File(pdf.fileUri)
        if (!file.exists()) {
            Toast.makeText(activity, "File PDF tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }
        val loading = AlertDialog.Builder(activity)
            .setTitle("Memperbarui Ekstraksi PDF")
            .setMessage("Membangun ulang struktur bab & deteksi rumus...")
            .setCancelable(false)
            .show()

        Thread {
            val ext = KuliahVaultEngine.extractPdfInfo(activity, file)
            val analysis = KuliahVaultEngine.analyzeText(ext.text)
            db.deleteVaultPdf(pdf.id)
            db.insertVaultPdf(
                courseKey = pdf.courseKey,
                title = pdf.title,
                fileUri = pdf.fileUri,
                pageCount = ext.pageCount,
                bodyText = ext.text,
                analysisJson = KuliahVaultEngine.analysisToJson(analysis),
                suggestedBase = pdf.suggestedBase,
                confidence = pdf.confidence
            )
            mainHandler.post {
                loading.dismiss()
                Toast.makeText(activity, "Materi PDF berhasil diperbarui!", Toast.LENGTH_SHORT).show()
                renderPdfTab(container)
            }
        }.start()
    }

    fun showPdfReaderDialog(pdfId: Long, highlightQuery: String = "") {
        val pdf = db.getVaultPdf(pdfId) ?: return
        val parsed = KuliahVaultEngine.parseAnalysisJson(pdf.analysisJson)
        val analysis = if (parsed.sections.isEmpty() && pdf.bodyText.isNotBlank()) {
            KuliahVaultEngine.analyzeText(pdf.bodyText)
        } else {
            parsed
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        val metaRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(TextView(activity).apply {
            text = "${KuliahVaultEngine.courseByKey(pdf.courseKey).name} • ${pdf.pageCount} hal"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0284C7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        metaRow.addView(Button(activity).apply {
            text = "PDF Asli"
            isAllCaps = false
            textSize = 11f
            setOnClickListener { openExternalPdf(pdf.fileUri) }
        })
        root.addView(metaRow)
        root.addView(space(8))

        var activeSubTab = if (highlightQuery.isNotBlank()) 3 else 0
        val tabLabels = listOf(
            "Intisari (${analysis.summary.size})",
            "Rumus (${analysis.formulaDetails.size})",
            "Glosarium (${analysis.definitions.size})",
            "Materi Bab (${analysis.sections.size})"
        )
        val tabScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val tabRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val tabViews = mutableListOf<TextView>()

        val bodyScroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(420))
        }
        val bodyBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        bodyScroll.addView(bodyBox)

        fun renderReaderBody() {
            tabViews.forEachIndexed { i, tv ->
                val sel = i == activeSubTab
                tv.setTextColor(if (sel) Color.WHITE else Color.parseColor("#1E293B"))
                tv.background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (sel) "#4F46E5" else "#E2E8F0"))
                    cornerRadius = dp(16).toFloat()
                }
            }
            bodyBox.removeAllViews()

            when (activeSubTab) {
                0 -> {
                    if (analysis.summary.isEmpty()) {
                        bodyBox.addView(readerCard("#FFFFFF", "#CBD5E1", "Ringkasan Otomatis", "Tidak ada kalimat ringkasan yang terdeteksi dari dokumen ini."))
                    } else {
                        analysis.summary.forEachIndexed { idx, point ->
                            bodyBox.addView(
                                readerCard(
                                    bgHex = "#EEF2FF",
                                    borderHex = "#C7D2FE",
                                    badgeTitle = "POIN INTI #${idx + 1}",
                                    content = point,
                                    accentHex = "#4338CA"
                                ),
                                matchWrapBottom(8)
                            )
                        }
                    }
                }

                1 -> {
                    if (analysis.formulaDetails.isEmpty()) {
                        bodyBox.addView(readerCard("#FFFFFF", "#CBD5E1", "Deteksi Rumus", "Tidak ditemukan persamaan atau simbol rumus pada dokumen ini."))
                    } else {
                        analysis.formulaDetails.forEach { f ->
                            val card = LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                background = GradientDrawable().apply {
                                    setColor(Color.parseColor("#FFFBEB"))
                                    setStroke(dp(1), Color.parseColor("#FCD34D"))
                                    cornerRadius = dp(14).toFloat()
                                }
                            }
                            card.addView(TextView(activity).apply {
                                text = f.label
                                textSize = 11f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor("#B45309"))
                            })
                            card.addView(TextView(activity).apply {
                                text = f.expression
                                typeface = Typeface.MONOSPACE
                                textSize = 14f
                                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                                setTextColor(Color.parseColor("#0F172A"))
                                setTextIsSelectable(true)
                                setPadding(0, dp(4), 0, dp(4))
                            })
                            if (f.variableNotes.isNotBlank()) {
                                card.addView(TextView(activity).apply {
                                    text = "Keterangan: ${f.variableNotes}"
                                    textSize = 12f
                                    setTextColor(Color.parseColor("#475569"))
                                    setPadding(0, 0, 0, dp(6))
                                })
                            }
                            card.addView(Button(activity).apply {
                                text = "+ Simpan ke Bank Rumus"
                                isAllCaps = false
                                textSize = 11f
                                setOnClickListener {
                                    db.insertVaultFormula(
                                        pdf.courseKey,
                                        f.label,
                                        f.expression,
                                        f.variableNotes.ifBlank { "Dari PDF: ${pdf.title}" },
                                        "pdf"
                                    )
                                    Toast.makeText(activity, "Rumus disimpan ke Bank Rumus", Toast.LENGTH_SHORT).show()
                                }
                            })
                            bodyBox.addView(card, matchWrapBottom(10))
                        }
                    }
                }

                2 -> {
                    if (analysis.definitions.isEmpty()) {
                        bodyBox.addView(readerCard("#FFFFFF", "#CBD5E1", "Glosarium Istilah", "Tidak ditemukan pola definisi istilah di dokumen ini."))
                    } else {
                        analysis.definitions.forEach { (term, desc) ->
                            bodyBox.addView(
                                readerCard(
                                    bgHex = "#ECFDF5",
                                    borderHex = "#A7F3D0",
                                    badgeTitle = term.uppercase(Locale.getDefault()),
                                    content = desc,
                                    accentHex = "#047857"
                                ),
                                matchWrapBottom(8)
                            )
                        }
                    }
                }

                3 -> {
                    if (highlightQuery.isNotBlank()) {
                        bodyBox.addView(
                            readerCard(
                                bgHex = "#FEF3C7",
                                borderHex = "#F59E0B",
                                badgeTitle = "HASIL PENCARIAN: \"$highlightQuery\"",
                                content = KuliahVaultEngine.makeSnippet(pdf.bodyText, highlightQuery.lowercase()),
                                accentHex = "#B45309"
                            ),
                            matchWrapBottom(10)
                        )
                    }
                    if (analysis.sections.isEmpty()) {
                        bodyBox.addView(
                            readerCard(
                                bgHex = "#FFFFFF",
                                borderHex = "#CBD5E1",
                                badgeTitle = "TEKS DOKUMEN",
                                content = pdf.bodyText.take(4000).ifBlank { "(Dokumen kosong)" }
                            )
                        )
                    } else {
                        analysis.sections.forEach { sec ->
                            val secCard = LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                background = GradientDrawable().apply {
                                    setColor(Color.WHITE)
                                    setStroke(dp(1), Color.parseColor("#CBD5E1"))
                                    cornerRadius = dp(14).toFloat()
                                }
                            }
                            secCard.addView(TextView(activity).apply {
                                text = sec.heading
                                textSize = 14f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor("#1E1B4B"))
                            })
                            if (sec.bullets.isNotEmpty()) {
                                secCard.addView(space(4))
                                sec.bullets.forEach { b ->
                                    secCard.addView(TextView(activity).apply {
                                        text = b
                                        textSize = 13f
                                        setTextColor(Color.parseColor("#1E293B"))
                                        setTextIsSelectable(true)
                                        setPadding(dp(4), dp(2), 0, dp(2))
                                    })
                                }
                            }
                            if (sec.body.isNotBlank()) {
                                secCard.addView(space(4))
                                secCard.addView(TextView(activity).apply {
                                    text = sec.body
                                    textSize = 13f
                                    setTextColor(Color.parseColor("#334155"))
                                    setTextIsSelectable(true)
                                })
                            }
                            bodyBox.addView(secCard, matchWrapBottom(10))
                        }
                    }
                }
            }
        }

        tabLabels.forEachIndexed { idx, label ->
            val tv = TextView(activity).apply {
                text = label
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener {
                    activeSubTab = idx
                    renderReaderBody()
                }
            }
            tabViews.add(tv)
            tabRow.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }

        tabScroll.addView(tabRow)
        root.addView(tabScroll)
        root.addView(space(6))
        root.addView(bodyScroll)
        renderReaderBody()

        AlertDialog.Builder(activity)
            .setTitle(pdf.title)
            .setView(root)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun openExternalPdf(filePath: String) {
        try {
            val file = File(filePath)
            val cacheCopy = File(File(activity.cacheDir, "pdfs").apply { mkdirs() }, file.name)
            file.copyTo(cacheCopy, overwrite = true)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", cacheCopy)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Buka PDF"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Tidak ada aplikasi pembaca PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDossierDatePicker(courseKey: String) {
        val dates = db.listVaultMeetingDates(courseKey)
        if (dates.isEmpty()) {
            Toast.makeText(activity, "Belum ada materi PDF, rekaman, atau papan tulis di mata kuliah ini.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Pilih Tanggal Pertemuan")
            .setItems(dates.toTypedArray()) { _, idx ->
                val dateKey = dates[idx]
                try {
                    val outFile = KuliahVaultEngine.exportMeetingDossierPdf(activity, db, courseKey, dateKey)
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", outFile)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Dossier Kuliah $dateKey")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(share, "Bagikan Dossier Pertemuan PDF"))
                } catch (e: Exception) {
                    Toast.makeText(activity, "Gagal mengekspor Dossier: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ==================== 2. TAB REKAM DOSEN & TRANSKRIP PRO ====================

    private fun currentElapsedMs(): Long {
        if (!isRecording) return 0L
        return if (isPaused) accumulatedRecordMs else accumulatedRecordMs + (System.currentTimeMillis() - activeSegmentStartEpochMs)
    }

    fun renderRecordTab(container: LinearLayout) {
        activeTabRenderer = { renderRecordTab(container) }
        container.removeAllViews()

        container.addView(sectionHeader("Rekam Dosen & Transkrip AI", "Korektor istilah Teknik Industri, tanda baca otomatis & penanda waktu"))
        container.addView(space(10))

        container.addView(courseHorizontalPicker(recordCourseKey, enabled = !isRecording) { chosen ->
            recordCourseKey = chosen
            renderRecordTab(container)
        })
        container.addView(space(10))

        // Recorder Card
        val recCard = glassCard()
        val modeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val modeLabel = TextView(activity).apply {
            text = if (recordModeStt) "Mode: Transkrip Cerdas + Korektor TI (ID/EN)" else "Mode: Rekam Audio HD (.m4a)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentTextColor())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchModeBtn = Button(activity).apply {
            text = "Ganti Mode"
            isAllCaps = false
            textSize = 11f
            isEnabled = !isRecording
            setOnClickListener {
                recordModeStt = !recordModeStt
                renderRecordTab(container)
            }
        }
        modeRow.addView(modeLabel)
        modeRow.addView(switchModeBtn)
        recCard.addView(modeRow)
        recCard.addView(space(6))

        timerTextView = TextView(activity).apply {
            val statusTag = when {
                !isRecording -> ""
                isPaused -> " (JEDA)"
                else -> " ●"
            }
            text = "${formatClock(currentElapsedMs())}$statusTag"
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isPaused) Color.parseColor("#D97706") else textColor())
            gravity = Gravity.CENTER
        }
        recCard.addView(timerTextView)
        recCard.addView(space(10))

        if (!isRecording) {
            val startBtn = glassActionButton("● Mulai Rekam Penjelasan Dosen", "#4F46E5") {
                startRecordingFlow(container)
            }
            recCard.addView(startBtn, matchWrap())
        } else {
            val ctrlRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val pauseResumeBtn = glassActionButton(
                if (isPaused) "▶ Lanjut" else "⏸ Jeda",
                if (isPaused) "#0284C7" else "#D97706"
            ) {
                if (isPaused) resumeRecordingFlow(container) else pauseRecordingFlow(container)
            }
            val stopSaveBtn = glassActionButton("■ Simpan", "#DC2626") {
                stopRecordingAndSave(container)
            }
            ctrlRow.addView(pauseResumeBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
            ctrlRow.addView(stopSaveBtn, LinearLayout.LayoutParams(0, dp(42), 1.2f).apply { marginStart = dp(5) })
            recCard.addView(ctrlRow)

            if (recordModeStt) {
                recCard.addView(space(8))
                val quickToolRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                val bookmarkBtn = glassActionButton("⭐ Tandai Momen Penting", "#2E8B57") {
                    val nowRel = currentElapsedMs()
                    liveSegments.add(RecordingSegment("⭐ Poin penting ditandai mahasiswa", nowRel, nowRel))
                    lastSegmentEndMs = nowRel
                    liveTranscriptTextView?.text = buildLiveTranscriptPreview()
                    Toast.makeText(activity, "Ditandai di ${formatClock(nowRel)}", Toast.LENGTH_SHORT).show()
                }
                val noteStampBtn = glassActionButton("+ Catatan Cepat", "#334155") {
                    showQuickNoteDuringRecordingDialog()
                }
                quickToolRow.addView(bookmarkBtn, LinearLayout.LayoutParams(0, dp(38), 1.2f).apply { marginEnd = dp(5) })
                quickToolRow.addView(noteStampBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(5) })
                recCard.addView(quickToolRow)
            }
        }

        if (isRecording || liveSegments.isNotEmpty()) {
            recCard.addView(space(10))
            liveTranscriptTextView = TextView(activity).apply {
                text = buildLiveTranscriptPreview()
                textSize = 13f
                setTextColor(textColor())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = LiquidGlassKit.glassSurface(activity, isDark(), 14f, strongRim = false)
            }
            recCard.addView(liveTranscriptTextView)
        }
        container.addView(recCard, matchWrapBottom(14))

        // Saved Recordings List
        val course = KuliahVaultEngine.courseByKey(recordCourseKey)
        val recordings = db.listVaultRecordings(recordCourseKey)
        container.addView(TextView(activity).apply {
            text = "Rekaman Tersimpan • ${course.name} (${recordings.size})"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor())
        })
        container.addView(space(8))

        if (recordings.isEmpty()) {
            container.addView(emptyGlassBox("Belum ada rekaman dosen untuk mata kuliah ini."))
        } else {
            for (rec in recordings) {
                val analysis = KuliahTranscriptEngine.analyzeLectureTranscript(rec.segments, rec.courseKey)
                val card = glassCard().apply {
                    setOnClickListener { showRecordingPlayerDialog(rec.id) }
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val info = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(TextView(activity).apply {
                    text = rec.title
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textColor())
                })
                info.addView(TextView(activity).apply {
                    val segInfo = if (rec.segments.isNotEmpty()) {
                        "${rec.segments.size} segmen • ${analysis.chapters.size} bagian • ${analysis.examCues.size} kisi-kisi"
                    } else {
                        "Audio HD"
                    }
                    text = "${formatClock(rec.durationMs)} • $segInfo"
                    textSize = 12f
                    setTextColor(subTextColor())
                })
                val menuBtn = TextView(activity).apply {
                    text = "⋮"
                    textSize = 20f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textColor())
                    setPadding(dp(12), dp(6), dp(8), dp(6))
                    setOnClickListener { showRecordingOptionsDialog(rec, container) }
                }
                row.addView(info)
                row.addView(menuBtn)
                card.addView(row)
                container.addView(card, matchWrapBottom(10))
            }
        }
    }

    private fun showQuickNoteDuringRecordingDialog() {
        val stampMs = currentElapsedMs()
        val input = EditText(activity).apply {
            hint = "Ketik rumus / istilah penting dosen saat ini..."
        }
        AlertDialog.Builder(activity)
            .setTitle("Catatan Cepat [${formatClock(stampMs)}]")
            .setView(input)
            .setPositiveButton("Tempel ke Transkrip") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    liveSegments.add(RecordingSegment("[CATATAN] $v", stampMs, stampMs))
                    lastSegmentEndMs = stampMs
                    liveTranscriptTextView?.text = buildLiveTranscriptPreview()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun startRecordingFlow(container: LinearLayout) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onRequestAudioPermission()
            return
        }
        liveSegments.clear()
        recordedParts.clear()
        interimTranscript = ""
        lastSegmentEndMs = 0L
        currentSegStartMs = -1L
        sessionCreatedEpochMs = System.currentTimeMillis()
        accumulatedRecordMs = 0L
        activeSegmentStartEpochMs = sessionCreatedEpochMs
        isRecording = true
        isPaused = false

        if (recordModeStt && SpeechRecognizer.isRecognitionAvailable(activity)) {
            startSpeechSession()
        } else {
            recordModeStt = false
            startAudioFileRecorder()
        }
        startTimerTicker()
        renderRecordTab(container)
    }

    private fun pauseRecordingFlow(container: LinearLayout) {
        if (!isRecording || isPaused) return
        accumulatedRecordMs += (System.currentTimeMillis() - activeSegmentStartEpochMs)
        isPaused = true

        if (recordModeStt) {
            flushInterimUtterance(accumulatedRecordMs)
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
            speechRecognizer = null
        } else {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.pause()
                }
            } catch (_: Exception) {
            }
        }
        renderRecordTab(container)
    }

    private fun resumeRecordingFlow(container: LinearLayout) {
        if (!isRecording || !isPaused) return
        activeSegmentStartEpochMs = System.currentTimeMillis()
        isPaused = false
        currentSegStartMs = -1L

        if (recordModeStt) {
            startSpeechSession()
        } else {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.resume()
                }
            } catch (_: Exception) {
            }
        }
        renderRecordTab(container)
    }

    private fun startAudioFileRecorder() {
        try {
            val dir = File(activity.filesDir, "kuliahvault/rekaman").apply { mkdirs() }
            val outFile = File(dir, "rec_${recordCourseKey}_${System.currentTimeMillis()}.m4a")
            currentAudioFile = outFile
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(activity)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()
            mediaRecorder = mr
        } catch (e: Exception) {
            isRecording = false
            Toast.makeText(activity, "Gagal memulai mikrofon: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun flushInterimUtterance(endMs: Long) {
        if (interimTranscript.isNotBlank()) {
            val cleaned = KuliahTranscriptEngine.cleanAndFormatUtterance(interimTranscript, recordCourseKey)
            if (cleaned.isNotEmpty()) {
                val start = if (currentSegStartMs >= 0L) currentSegStartMs else lastSegmentEndMs
                liveSegments.add(RecordingSegment(cleaned, start, endMs))
                lastSegmentEndMs = endMs
            }
            interimTranscript = ""
            currentSegStartMs = -1L
        }
    }

    private fun startSpeechSession() {
        if (!isRecording || isPaused) return
        try {
            speechRecognizer?.destroy()
            val sr = SpeechRecognizer.createSpeechRecognizer(activity)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {
                    if (currentSegStartMs < 0L) {
                        currentSegStartMs = currentElapsedMs().coerceAtLeast(lastSegmentEndMs)
                    }
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (isRecording && !isPaused) {
                        val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 500L else 220L
                        mainHandler.postDelayed({ startSpeechSession() }, delay)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val rawText = matches?.firstOrNull()?.trim().orEmpty()
                    val nowRel = currentElapsedMs()
                    if (rawText.isNotEmpty()) {
                        val formatted = KuliahTranscriptEngine.cleanAndFormatUtterance(rawText, recordCourseKey)
                        if (formatted.isNotEmpty()) {
                            val start = if (currentSegStartMs >= 0L) currentSegStartMs else (nowRel - 1500L).coerceAtLeast(lastSegmentEndMs)
                            liveSegments.add(RecordingSegment(formatted, start, nowRel))
                            lastSegmentEndMs = nowRel
                        }
                    }
                    currentSegStartMs = -1L
                    interimTranscript = ""
                    liveTranscriptTextView?.text = buildLiveTranscriptPreview()
                    if (isRecording && !isPaused) {
                        mainHandler.postDelayed({ startSpeechSession() }, 180L)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (currentSegStartMs < 0L) {
                            currentSegStartMs = currentElapsedMs().coerceAtLeast(lastSegmentEndMs)
                        }
                        interimTranscript = text
                        liveTranscriptTextView?.text = buildLiveTranscriptPreview()
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            }
            sr.startListening(intent)
            speechRecognizer = sr
        } catch (_: Exception) {
            recordModeStt = false
            startAudioFileRecorder()
        }
    }

    private fun stopRecordingAndSave(container: LinearLayout) {
        val totalDuration = currentElapsedMs().coerceAtLeast(1000L)
        isRecording = false
        isPaused = false

        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        flushInterimUtterance(totalDuration)

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null

        currentAudioFile?.let { file ->
            if (file.exists() && file.length() > 0L) {
                recordedParts.add(RecordingPart(file.absolutePath, 0L, totalDuration))
            }
        }
        currentAudioFile = null

        if (recordedParts.isEmpty() && liveSegments.isEmpty()) {
            Toast.makeText(activity, "Tidak ada suara atau transkrip yang terekam.", Toast.LENGTH_SHORT).show()
        } else {
            val c = KuliahVaultEngine.courseByKey(recordCourseKey)
            val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(sessionCreatedEpochMs))
            val savedId = db.insertVaultRecording(
                courseKey = recordCourseKey,
                title = "${c.name} - $stamp",
                parts = recordedParts.toList(),
                durationMs = totalDuration,
                segments = liveSegments.toList()
            )
            Toast.makeText(activity, "Transkrip kuliah tersimpan!", Toast.LENGTH_SHORT).show()
            liveSegments.clear()
            recordedParts.clear()
            renderRecordTab(container)
            showRecordingPlayerDialog(savedId)
            return
        }
        liveSegments.clear()
        recordedParts.clear()
        renderRecordTab(container)
    }

    private fun startTimerTicker() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRecording) return
                val tag = if (isPaused) " (JEDA)" else " ●"
                timerTextView?.text = "${formatClock(currentElapsedMs())}$tag"
                mainHandler.postDelayed(this, 500L)
            }
        }, 500L)
    }

    private fun buildLiveTranscriptPreview(): String {
        val sb = StringBuilder()
        liveSegments.takeLast(7).forEach { seg ->
            sb.append("[${formatClock(seg.startMs)}] ${seg.text}\n\n")
        }
        if (interimTranscript.isNotBlank()) {
            sb.append("... $interimTranscript")
        }
        return sb.toString().trim().ifEmpty {
            if (isPaused) "Rekaman dijeda. Tekan 'Lanjut' saat dosen mulai menjelaskan lagi."
            else "Mendengarkan & merapikan istilah kuliah secara otomatis..."
        }
    }

    private fun showRecordingOptionsDialog(rec: VaultRecording, container: LinearLayout) {
        val opts = arrayOf(
            "Buka Pembaca & Ringkasan Kuliah",
            "Ekspor Transkrip ke PDF",
            "Bagikan Ringkasan ke WhatsApp",
            "Ganti Nama",
            "Pindahkan ke Mata Kuliah Lain",
            "Hapus Rekaman"
        )
        AlertDialog.Builder(activity)
            .setTitle(rec.title)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showRecordingPlayerDialog(rec.id)
                    1 -> exportRecordingToPdf(rec)
                    2 -> shareRecordingToWhatsApp(rec)
                    3 -> showRenameDialog("Ganti Nama Rekaman", rec.title) { newTitle ->
                        db.renameVaultRecording(rec.id, newTitle)
                        renderRecordTab(container)
                    }
                    4 -> showMoveCourseDialog(rec.courseKey) { newKey ->
                        db.moveVaultRecording(rec.id, newKey)
                        renderRecordTab(container)
                    }
                    5 -> {
                        rec.parts.forEach { File(it.uri).delete() }
                        db.deleteVaultRecording(rec.id)
                        renderRecordTab(container)
                    }
                }
            }
            .show()
    }

    private fun exportRecordingToPdf(rec: VaultRecording) {
        try {
            val courseName = KuliahVaultEngine.courseByKey(rec.courseKey).name
            val outFile = KuliahTranscriptEngine.exportTranscriptPdf(activity, rec, courseName)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", outFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Transkrip ${rec.title}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Simpan atau Bagikan PDF Transkrip"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Gagal mengekspor PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareRecordingToWhatsApp(rec: VaultRecording) {
        val analysis = KuliahTranscriptEngine.analyzeLectureTranscript(rec.segments, rec.courseKey)
        val sb = StringBuilder()
        sb.append("*RANGKUMAN KULIAH: ${rec.title}*\n\n")
        if (analysis.keyPoints.isNotEmpty()) {
            sb.append("*Poin Inti:*\n")
            analysis.keyPoints.forEach { sb.append("• $it\n") }
            sb.append("\n")
        }
        if (analysis.examCues.isNotEmpty()) {
            sb.append("*Kisi-Kisi / Penting:*\n")
            analysis.examCues.forEach { sb.append("★ $it\n") }
            sb.append("\n")
        }
        if (rec.segments.isNotEmpty()) {
            sb.append("*Catatan Transkrip:*\n")
            rec.segments.take(15).forEach { seg ->
                sb.append("[${formatClock(seg.startMs)}] ${seg.text}\n")
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString().trim())
        }
        activity.startActivity(Intent.createChooser(intent, "Bagikan Catatan Kuliah"))
    }

    fun showRecordingPlayerDialog(recordingId: Long) {
        val rec = db.getVaultRecording(recordingId) ?: return
        val analysis = KuliahTranscriptEngine.analyzeLectureTranscript(rec.segments, rec.courseKey)

        var player: MediaPlayer? = null
        var seekTicker: Runnable? = null
        var speedIdx = 0
        val speeds = listOf(1.0f, 1.25f, 1.5f)

        val audioPart = rec.parts.firstOrNull { File(it.uri).exists() }
        if (audioPart != null) {
            try {
                player = MediaPlayer().apply {
                    setDataSource(audioPart.uri)
                    prepare()
                }
            } catch (_: Exception) {
                player = null
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(TextView(activity).apply {
            text = "${KuliahVaultEngine.courseByKey(rec.courseKey).name} • ${formatClock(rec.durationMs)}"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4F46E5"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topBar.addView(Button(activity).apply {
            text = "PDF"
            isAllCaps = false
            textSize = 11f
            setOnClickListener { exportRecordingToPdf(rec) }
        })
        topBar.addView(Button(activity).apply {
            text = "Share"
            isAllCaps = false
            textSize = 11f
            setOnClickListener { shareRecordingToWhatsApp(rec) }
        })
        root.addView(topBar)

        if (player != null) {
            val audioBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EEF2FF"))
                    cornerRadius = dp(12).toFloat()
                }
            }
            val timePosLabel = TextView(activity).apply {
                text = "00:00 / ${formatClock(player.duration.toLong())}"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#312E81"))
            }
            val seekBar = SeekBar(activity).apply {
                max = player.duration.coerceAtLeast(100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            player.seekTo(progress)
                            timePosLabel.text = "${formatClock(progress.toLong())} / ${formatClock(player.duration.toLong())}"
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            val pControls = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val playBtn = Button(activity).apply {
                text = "▶ Putar"
                isAllCaps = false
                textSize = 11f
                setOnClickListener {
                    if (player.isPlaying) {
                        player.pause()
                        text = "▶ Lanjut"
                    } else {
                        player.start()
                        text = "⏸ Jeda"
                    }
                }
            }
            val speedBtn = Button(activity).apply {
                text = "1.0x"
                isAllCaps = false
                textSize = 11f
                setOnClickListener {
                    speedIdx = (speedIdx + 1) % speeds.size
                    val sp = speeds[speedIdx]
                    text = "${sp}x"
                    try {
                        player.playbackParams = PlaybackParams().setSpeed(sp)
                    } catch (_: Exception) {
                    }
                }
            }
            pControls.addView(playBtn)
            pControls.addView(speedBtn)
            audioBox.addView(timePosLabel)
            audioBox.addView(seekBar)
            audioBox.addView(pControls)
            root.addView(audioBox, matchWrapBottom(8))

            seekTicker = object : Runnable {
                override fun run() {
                    try {
                        if (player.isPlaying) {
                            val cur = player.currentPosition
                            seekBar.progress = cur
                            timePosLabel.text = "${formatClock(cur.toLong())} / ${formatClock(player.duration.toLong())}"
                        }
                    } catch (_: Exception) {
                    }
                    mainHandler.postDelayed(this, 400L)
                }
            }
            mainHandler.post(seekTicker)
        }

        var activeSubTab = 0
        val tabLabels = listOf(
            "Ringkasan (${analysis.keyPoints.size + analysis.examCues.size})",
            "Transkrip (${analysis.chapters.size} Bagian)",
            "Edit Teks"
        )
        val tabScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val tabRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val tabViews = mutableListOf<TextView>()

        val bodyScroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(380))
        }
        val bodyBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }
        bodyScroll.addView(bodyBox)

        fun renderTranscriptBody() {
            tabViews.forEachIndexed { i, tv ->
                val sel = i == activeSubTab
                tv.setTextColor(if (sel) Color.WHITE else Color.parseColor("#1E293B"))
                tv.background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (sel) "#4F46E5" else "#E2E8F0"))
                    cornerRadius = dp(16).toFloat()
                }
            }
            bodyBox.removeAllViews()

            when (activeSubTab) {
                0 -> {
                    if (analysis.examCues.isNotEmpty()) {
                        bodyBox.addView(boldLabel("Kisi-Kisi & Penekanan Dosen (${analysis.examCues.size})"))
                        bodyBox.addView(space(6))
                        analysis.examCues.forEach { cue ->
                            bodyBox.addView(
                                readerCard("#FEF3C7", "#F59E0B", "PENTING / KISI-KISI", cue, "#B45309"),
                                matchWrapBottom(8)
                            )
                        }
                    }
                    if (analysis.bookmarks.isNotEmpty()) {
                        bodyBox.addView(boldLabel("Momen Ditandai Mahasiswa (${analysis.bookmarks.size})"))
                        bodyBox.addView(space(6))
                        analysis.bookmarks.forEach { bm ->
                            bodyBox.addView(
                                readerCard("#ECFDF5", "#6EE7B7", "PENANDA [${formatClock(bm.startMs)}]", bm.text, "#047857"),
                                matchWrapBottom(8)
                            )
                        }
                    }
                    if (analysis.keyPoints.isNotEmpty()) {
                        bodyBox.addView(boldLabel("Intisari Penjelasan Dosen"))
                        bodyBox.addView(space(6))
                        analysis.keyPoints.forEachIndexed { idx, kp ->
                            bodyBox.addView(
                                readerCard("#EEF2FF", "#C7D2FE", "POIN INTI #${idx + 1}", kp, "#4338CA"),
                                matchWrapBottom(8)
                            )
                        }
                    }
                    if (analysis.examCues.isEmpty() && analysis.bookmarks.isEmpty() && analysis.keyPoints.isEmpty()) {
                        bodyBox.addView(readerCard("#FFFFFF", "#CBD5E1", "Ringkasan Kuliah", "Belum ada transkrip teks yang cukup untuk diringkas."))
                    }
                }

                1 -> {
                    if (analysis.chapters.isEmpty()) {
                        bodyBox.addView(readerCard("#FFFFFF", "#CBD5E1", "Transkrip Kuliah", "Sesi ini direkam dalam mode Audio HD tanpa transkrip otomatis."))
                    } else {
                        analysis.chapters.forEach { ch ->
                            val chCard = LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                background = GradientDrawable().apply {
                                    setColor(Color.WHITE)
                                    setStroke(dp(1), Color.parseColor("#CBD5E1"))
                                    cornerRadius = dp(14).toFloat()
                                }
                            }
                            chCard.addView(TextView(activity).apply {
                                text = "[${formatClock(ch.startMs)}] ${ch.title}"
                                textSize = 13f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor("#1E1B4B"))
                            })
                            chCard.addView(space(6))
                            ch.segments.forEach { seg ->
                                val isHighlight = seg.text.startsWith("[KISI-KISI]") || seg.text.startsWith("⭐") || seg.text.startsWith("[CATATAN]")
                                val segView = TextView(activity).apply {
                                    text = "[${formatClock(seg.startMs)}]  ${seg.text}"
                                    textSize = 13f
                                    setTextColor(Color.parseColor(if (isHighlight) "#B45309" else "#1E293B"))
                                    if (isHighlight) setTypeface(null, Typeface.BOLD)
                                    setPadding(dp(4), dp(4), dp(4), dp(4))
                                    setTextIsSelectable(true)
                                    if (player != null) {
                                        setOnClickListener {
                                            player.seekTo(seg.startMs.toInt().coerceIn(0, player.duration))
                                            if (!player.isPlaying) player.start()
                                            Toast.makeText(activity, "Melompat ke ${formatClock(seg.startMs)}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                chCard.addView(segView)
                            }
                            bodyBox.addView(chCard, matchWrapBottom(10))
                        }
                    }
                }

                2 -> {
                    val editors = mutableListOf<Pair<RecordingSegment, EditText>>()
                    if (rec.segments.isEmpty()) {
                        val manualIn = EditText(activity).apply {
                            hint = "Tulis catatan transkrip manual untuk rekaman ini..."
                            minLines = 5
                        }
                        bodyBox.addView(manualIn)
                        bodyBox.addView(Button(activity).apply {
                            text = "Simpan Catatan Manual"
                            isAllCaps = false
                            setOnClickListener {
                                val lines = manualIn.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                                val newSegs = lines.mapIndexed { idx, ln ->
                                    RecordingSegment(KuliahTranscriptEngine.cleanAndFormatUtterance(ln, rec.courseKey), idx * 5000L, (idx + 1) * 5000L)
                                }
                                db.updateVaultRecordingTranscript(rec.id, newSegs)
                                Toast.makeText(activity, "Transkrip disimpan!", Toast.LENGTH_SHORT).show()
                                activeTabRenderer?.invoke()
                            }
                        })
                    } else {
                        rec.segments.forEach { seg ->
                            val row = LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, dp(4), 0, dp(6))
                            }
                            row.addView(TextView(activity).apply {
                                text = "[${formatClock(seg.startMs)}]"
                                textSize = 11f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor("#4F46E5"))
                            })
                            val ed = EditText(activity).apply {
                                setText(seg.text)
                                textSize = 13f
                            }
                            editors.add(seg to ed)
                            row.addView(ed)
                            bodyBox.addView(row)
                        }
                        bodyBox.addView(Button(activity).apply {
                            text = "Simpan Koreksi Transkrip"
                            isAllCaps = false
                            setOnClickListener {
                                val updated = editors.map { (orig, ed) ->
                                    orig.copy(text = ed.text.toString().trim().ifEmpty { orig.text })
                                }
                                db.updateVaultRecordingTranscript(rec.id, updated)
                                Toast.makeText(activity, "Perubahan transkrip tersimpan!", Toast.LENGTH_SHORT).show()
                                activeTabRenderer?.invoke()
                            }
                        })
                    }
                }
            }
        }

        tabLabels.forEachIndexed { idx, label ->
            val tv = TextView(activity).apply {
                text = label
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener {
                    activeSubTab = idx
                    renderTranscriptBody()
                }
            }
            tabViews.add(tv)
            tabRow.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }

        tabScroll.addView(tabRow)
        root.addView(tabScroll)
        root.addView(space(6))
        root.addView(bodyScroll)
        renderTranscriptBody()

        AlertDialog.Builder(activity)
            .setTitle(rec.title)
            .setView(root)
            .setOnDismissListener {
                seekTicker?.let { mainHandler.removeCallbacks(it) }
                try {
                    player?.stop()
                    player?.release()
                } catch (_: Exception) {
                }
            }
            .setPositiveButton("Tutup", null)
            .show()
    }

    // ==================== 3. TAB PAPAN TULIS & OCR ANTI-SILAU ====================

    fun renderBoardTab(container: LinearLayout) {
        activeTabRenderer = { renderBoardTab(container) }
        container.removeAllViews()

        container.addView(sectionHeader("Scan Papan Tulis & OCR", "Hapus kilau lampu kelas, baca teks & pisahkan rumus papan tulis"))
        container.addView(space(10))

        val step1 = glassCard()
        step1.addView(TextView(activity).apply {
            text = "Langkah 1 • Konfirmasi Mata Kuliah"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor())
        })
        step1.addView(space(8))
        step1.addView(courseHorizontalPicker(boardCourseKey, enabled = true) { chosen ->
            boardCourseKey = chosen
            boardCourseConfirmed = false
            renderBoardTab(container)
        })
        step1.addView(space(8))
        val confirmBtn = glassActionButton(
            if (boardCourseConfirmed) "✓ Mata Kuliah Terkonfirmasi" else "Konfirmasi Mata Kuliah Ini",
            if (boardCourseConfirmed) "#2E8B57" else "#4F46E5"
        ) {
            boardCourseConfirmed = true
            renderBoardTab(container)
        }
        step1.addView(confirmBtn, matchWrap())
        container.addView(step1, matchWrapBottom(12))

        val step2 = glassCard().apply {
            alpha = if (boardCourseConfirmed) 1f else 0.55f
        }
        step2.addView(TextView(activity).apply {
            text = "Langkah 2 • Ambil Foto Papan Tulis"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor())
        })
        step2.addView(space(8))
        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val camBtn = glassActionButton("Kamera", "#0284C7") {
            if (!boardCourseConfirmed) {
                Toast.makeText(activity, "Konfirmasi mata kuliah di Langkah 1 terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@glassActionButton
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                onRequestCameraPermission()
                return@glassActionButton
            }
            val dir = File(activity.cacheDir, "pdfs").apply { mkdirs() }
            val photoFile = File(dir, "board_raw_${System.currentTimeMillis()}.jpg")
            pendingCameraFile = photoFile
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", photoFile)
            onRequestCapturePhoto(uri)
        }
        val galBtn = glassActionButton("Galeri", "#334155") {
            if (!boardCourseConfirmed) {
                Toast.makeText(activity, "Konfirmasi mata kuliah di Langkah 1 terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@glassActionButton
            }
            onRequestPickGallery()
        }
        btnRow.addView(camBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        btnRow.addView(galBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        step2.addView(btnRow)
        container.addView(step2, matchWrapBottom(14))

        val course = KuliahVaultEngine.courseByKey(boardCourseKey)
        val boards = db.listVaultBoards(boardCourseKey)
        container.addView(TextView(activity).apply {
            text = "Scan Papan Tulis • ${course.name} (${boards.size})"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor())
        })
        container.addView(space(8))

        if (boards.isEmpty()) {
            container.addView(emptyGlassBox("Belum ada foto papan tulis di mata kuliah ini."))
        } else {
            for (b in boards) {
                val card = glassCard().apply {
                    setOnClickListener { showBoardDetailDialog(b.id, container) }
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val thumb = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val f = File(b.imageUri)
                    if (f.exists()) setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
                }
                val info = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(12)
                    }
                }
                info.addView(TextView(activity).apply {
                    text = "${formatDateTime(b.createdAt)}${if (b.enhanced) " • Anti-Silau Aktif" else ""}"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accentTextColor())
                })
                info.addView(TextView(activity).apply {
                    text = b.ocrText.replace(Regex("\\s+"), " ").ifBlank { "Tanpa teks terdeteksi" }
                    textSize = 13f
                    maxLines = 2
                    setTextColor(textColor())
                })
                row.addView(thumb, LinearLayout.LayoutParams(dp(64), dp(64)))
                row.addView(info)
                card.addView(row)
                container.addView(card, matchWrapBottom(10))
            }
        }
    }

    fun handleCapturedBoardPhoto() {
        val file = pendingCameraFile ?: return
        if (file.exists() && file.length() > 0L) {
            showBoardPreviewDialog(Uri.fromFile(file))
        }
    }

    fun handlePickedBoardImage(uri: Uri) {
        showBoardPreviewDialog(uri)
    }

    private fun showBoardPreviewDialog(sourceUri: Uri) {
        var useSharp = true
        var enhancedFile: File? = null

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        val previewImage = ImageView(activity).apply {
            adjustViewBounds = true
            maxHeight = dp(260)
            setImageURI(sourceUri)
        }
        box.addView(previewImage, matchWrapBottom(12))

        val sharpToggleBtn = Button(activity).apply {
            text = "Memproses Filter Anti-Silau..."
            isAllCaps = false
            isEnabled = false
        }
        box.addView(sharpToggleBtn)

        Thread {
            try {
                val out = KuliahVaultEngine.enhanceWhiteboard(activity, sourceUri)
                enhancedFile = out
                mainHandler.post {
                    previewImage.setImageBitmap(BitmapFactory.decodeFile(out.absolutePath))
                    sharpToggleBtn.text = "Mode Anti-Silau Papan Tulis: AKTIF ✓ (Ketuk untuk Foto Asli)"
                    sharpToggleBtn.isEnabled = true
                }
            } catch (_: Exception) {
                mainHandler.post {
                    useSharp = false
                    sharpToggleBtn.text = "Mode Foto Asli"
                    sharpToggleBtn.isEnabled = true
                }
            }
        }.start()

        sharpToggleBtn.setOnClickListener {
            useSharp = !useSharp
            if (useSharp && enhancedFile != null) {
                previewImage.setImageBitmap(BitmapFactory.decodeFile(enhancedFile!!.absolutePath))
                sharpToggleBtn.text = "Mode Anti-Silau Papan Tulis: AKTIF ✓"
            } else {
                previewImage.setImageURI(sourceUri)
                sharpToggleBtn.text = "Mode Foto Asli (Ketuk untuk Aktifkan Anti-Silau)"
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Pratinjau Scan Papan Tulis")
            .setView(box)
            .setPositiveButton("Simpan & Baca Teks (OCR)") { _, _ ->
                val loading = AlertDialog.Builder(activity)
                    .setTitle("Membaca Teks Papan Tulis")
                    .setMessage("Menjalankan OCR spasial offline...")
                    .setCancelable(false)
                    .show()

                Thread {
                    try {
                        val savedFile = if (useSharp && enhancedFile != null) {
                            enhancedFile!!
                        } else {
                            KuliahVaultEngine.importFileToInternalStorage(activity, sourceUri, "papan", "papan_$boardCourseKey", "jpg")
                        }
                        val savedUri = Uri.fromFile(savedFile)
                        mainHandler.post {
                            KuliahVaultEngine.recognizeImageText(activity, savedUri) { _, ocrText ->
                                loading.dismiss()
                                val id = db.insertVaultBoard(boardCourseKey, savedFile.absolutePath, ocrText, useSharp)
                                activeTabRenderer?.invoke()
                                showOcrResultDialog(id, ocrText)
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            loading.dismiss()
                            Toast.makeText(activity, "Gagal menyimpan foto: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Batal") { _, _ ->
                enhancedFile?.delete()
            }
            .show()
    }

    private fun showOcrResultDialog(boardId: Long, ocrText: String) {
        val formulas = KuliahVaultEngine.extractFormulas(ocrText)
        val nonFormulaLines = ocrText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !KuliahVaultEngine.looksLikeFormula(it) }
            .joinToString("\n")

        val scroll = ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        box.addView(
            readerCard(
                bgHex = "#F8FAFC",
                borderHex = "#CBD5E1",
                badgeTitle = "CATATAN TEORI PAPAN TULIS",
                content = nonFormulaLines.ifBlank { ocrText.ifBlank { "Tidak ada teks terdeteksi pada gambar ini." } }
            ),
            matchWrapBottom(12)
        )

        if (formulas.isNotEmpty()) {
            box.addView(boldLabel("Rumus Terdeteksi (${formulas.size})"))
            box.addView(space(6))
            formulas.forEach { (label, expr) ->
                val fBox = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FFFBEB"))
                        setStroke(dp(1), Color.parseColor("#FCD34D"))
                        cornerRadius = dp(12).toFloat()
                    }
                }
                fBox.addView(TextView(activity).apply {
                    text = expr
                    typeface = Typeface.MONOSPACE
                    textSize = 14f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                fBox.addView(Button(activity).apply {
                    text = "+ Simpan ke Bank Rumus"
                    isAllCaps = false
                    textSize = 11f
                    setOnClickListener {
                        db.insertVaultFormula(boardCourseKey, label, expr, "Dari Papan Tulis #$boardId", "papan")
                        Toast.makeText(activity, "Rumus disimpan ke Bank Rumus", Toast.LENGTH_SHORT).show()
                    }
                })
                box.addView(fBox, matchWrapBottom(8))
            }
        }
        scroll.addView(box)
        AlertDialog.Builder(activity)
            .setTitle("Hasil Scan Papan Tulis")
            .setView(scroll)
            .setPositiveButton("Selesai", null)
            .show()
    }

    fun showBoardDetailDialog(boardId: Long, container: LinearLayout? = null) {
        val board = db.getVaultBoard(boardId) ?: return
        val scroll = ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val f = File(board.imageUri)
        if (f.exists()) {
            box.addView(ImageView(activity).apply {
                adjustViewBounds = true
                maxHeight = dp(240)
                setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
            }, matchWrapBottom(10))
        }
        val ocrInput = EditText(activity).apply {
            setText(board.ocrText)
            hint = "Catatan / hasil OCR papan tulis"
        }
        box.addView(boldLabel("Edit Teks OCR / Catatan"))
        box.addView(ocrInput)
        scroll.addView(box)

        AlertDialog.Builder(activity)
            .setTitle("${KuliahVaultEngine.courseByKey(board.courseKey).name} • ${formatDateTime(board.createdAt)}")
            .setView(scroll)
            .setPositiveButton("Simpan Perubahan") { _, _ ->
                db.updateVaultBoardText(board.id, ocrInput.text.toString())
                container?.let { renderBoardTab(it) }
            }
            .setNeutralButton("Hapus") { _, _ ->
                File(board.imageUri).delete()
                db.deleteVaultBoard(board.id)
                container?.let { renderBoardTab(it) }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // ==================== 4. TAB BELAJAR & PENCARIAN LINTAS MATERI ====================

    fun renderStudyTab(container: LinearLayout) {
        activeTabRenderer = { renderStudyTab(container) }
        container.removeAllViews()

        container.addView(sectionHeader("Belajar & Pencarian Materi", "Cari di PDF, transkrip dosen, papan tulis, flashcard & bank rumus"))
        container.addView(space(10))

        val searchCard = glassCard()
        val searchInput = EditText(activity).apply {
            hint = "Cari kata kunci di semua PDF, transkrip, & papan tulis..."
            setText(searchQuery)
            textSize = 14f
            setTextColor(textColor())
            setHintTextColor(subTextColor())
            background = null
        }
        val searchResultsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun updateSearchHits(q: String) {
            searchQuery = q
            searchResultsBox.removeAllViews()
            if (q.trim().length < 2) return
            val hits = db.searchVaultAll(q)
            searchResultsBox.addView(space(8))
            if (hits.isEmpty()) {
                searchResultsBox.addView(TextView(activity).apply {
                    text = "Tidak ditemukan hasil untuk \"$q\"."
                    textSize = 12f
                    setTextColor(subTextColor())
                })
            } else {
                searchResultsBox.addView(TextView(activity).apply {
                    text = "${hits.size} hasil ditemukan:"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accentTextColor())
                })
                hits.forEach { hit ->
                    val hitRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = LiquidGlassKit.glassSurface(activity, isDark(), 14f, strongRim = false)
                        setOnClickListener {
                            when (hit.type) {
                                "pdf" -> showPdfReaderDialog(hit.id, q.trim())
                                "recording" -> showRecordingPlayerDialog(hit.id)
                                else -> showBoardDetailDialog(hit.id)
                            }
                        }
                    }
                    val badge = when (hit.type) {
                        "pdf" -> "PDF"
                        "recording" -> "REKAMAN"
                        else -> "PAPAN TULIS"
                    }
                    hitRow.addView(TextView(activity).apply {
                        text = "[$badge] ${hit.title} • ${KuliahVaultEngine.courseByKey(hit.courseKey).name}"
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(textColor())
                    })
                    hitRow.addView(TextView(activity).apply {
                        text = hit.snippet
                        textSize = 12f
                        setTextColor(subTextColor())
                    })
                    searchResultsBox.addView(hitRow, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) })
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSearchHits(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        searchCard.addView(searchInput)
        searchCard.addView(searchResultsBox)
        container.addView(searchCard, matchWrapBottom(14))
        if (searchQuery.trim().length >= 2) updateSearchHits(searchQuery)

        val counts = db.getVaultCourseCounts().values
        val totalPdf = counts.sumOf { it.pdf }
        val totalRec = counts.sumOf { it.recording }
        val totalBoard = counts.sumOf { it.board }
        val totalFormula = counts.sumOf { it.formula }

        val statRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        statRow.addView(statTile("$totalPdf", "PDF", "#0284C7"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        statRow.addView(statTile("$totalRec", "Rekaman", "#4F46E5"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        statRow.addView(statTile("$totalBoard", "Papan", "#2E8B57"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        statRow.addView(statTile("$totalFormula", "Rumus", "#D97706"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        container.addView(statRow)
        container.addView(space(14))

        container.addView(TextView(activity).apply {
            text = "Alat Belajar Cepat per Mata Kuliah"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor())
        })
        container.addView(space(8))
        container.addView(courseHorizontalPicker(studyCourseKey, enabled = true) { chosen ->
            studyCourseKey = chosen
            renderStudyTab(container)
        })
        container.addView(space(10))

        val toolsRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val flashBtn = glassActionButton("Flashcard Materi", "#4F46E5") {
            showFlashcardDialog(studyCourseKey)
        }
        val bankBtn = glassActionButton("Bank Rumus", "#D97706") {
            showFormulaBankDialog(studyCourseKey)
        }
        toolsRow.addView(flashBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        toolsRow.addView(bankBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        container.addView(toolsRow)
    }

    private fun showFlashcardDialog(courseKey: String) {
        val course = KuliahVaultEngine.courseByKey(courseKey)
        val pdfs = db.listVaultPdfs(courseKey)
        val cards = mutableListOf<Pair<String, String>>()
        for (p in pdfs) {
            val analysis = KuliahVaultEngine.parseAnalysisJson(p.analysisJson)
            analysis.definitions.forEach { (term, desc) ->
                cards.add("Apa yang dimaksud dengan $term?" to desc)
            }
            analysis.formulaDetails.forEach { f ->
                cards.add("Sebutkan ${f.label}" to "${f.expression}${if (f.variableNotes.isNotBlank()) "\n(${f.variableNotes})" else ""}")
            }
            analysis.summary.forEachIndexed { idx, s ->
                cards.add("Intisari #${idx + 1} (${p.title})" to s)
            }
        }
        for (r in db.listVaultRecordings(courseKey)) {
            val lec = KuliahTranscriptEngine.analyzeLectureTranscript(r.segments, courseKey)
            lec.examCues.forEach { cue ->
                cards.add("Kisi-Kisi Dosen (${r.title})" to cue)
            }
        }
        if (cards.isEmpty()) {
            Toast.makeText(activity, "Impor PDF atau rekam kuliah di ${course.name} terlebih dahulu untuk membuat Flashcard otomatis.", Toast.LENGTH_LONG).show()
            return
        }

        var index = 0
        var flipped = false
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        val counterText = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4F46E5"))
        }
        val cardContent = TextView(activity).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(28), dp(16), dp(28))
            setBackgroundColor(Color.parseColor("#EEF2FF"))
            setTextColor(Color.parseColor("#0F172A"))
        }
        fun refreshCard() {
            val item = cards[index]
            counterText.text = "Kartu ${index + 1} dari ${cards.size} • ${if (flipped) "JAWABAN" else "PERTANYAAN (Ketuk kartu untuk balik)"}"
            cardContent.text = if (flipped) item.second else item.first
        }
        cardContent.setOnClickListener {
            flipped = !flipped
            refreshCard()
        }
        val navRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val prevBtn = Button(activity).apply {
            text = "← Sebelumnya"
            isAllCaps = false
            setOnClickListener {
                index = if (index > 0) index - 1 else cards.lastIndex
                flipped = false
                refreshCard()
            }
        }
        val nextBtn = Button(activity).apply {
            text = "Berikutnya →"
            isAllCaps = false
            setOnClickListener {
                index = (index + 1) % cards.size
                flipped = false
                refreshCard()
            }
        }
        navRow.addView(prevBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        navRow.addView(nextBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        box.addView(counterText)
        box.addView(space(10))
        box.addView(cardContent, matchWrapBottom(12))
        box.addView(navRow)
        refreshCard()

        AlertDialog.Builder(activity)
            .setTitle("Flashcard • ${course.name}")
            .setView(box)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun showFormulaBankDialog(courseKey: String) {
        val course = KuliahVaultEngine.courseByKey(courseKey)
        val formulas = db.listVaultFormulas(courseKey)
        val scroll = ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        if (formulas.isEmpty()) {
            box.addView(mutedLabel("Belum ada rumus tersimpan di ${course.name}."))
        } else {
            formulas.forEach { f ->
                val fCard = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FFFBEB"))
                        setStroke(dp(1), Color.parseColor("#FCD34D"))
                        cornerRadius = dp(12).toFloat()
                    }
                }
                fCard.addView(TextView(activity).apply {
                    text = f.title
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#92400E"))
                })
                fCard.addView(TextView(activity).apply {
                    text = f.expression
                    typeface = Typeface.MONOSPACE
                    textSize = 14f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    setPadding(0, dp(4), 0, dp(4))
                })
                if (f.notes.isNotBlank()) {
                    fCard.addView(TextView(activity).apply {
                        text = f.notes
                        textSize = 11f
                        setTextColor(Color.parseColor("#475569"))
                    })
                }
                fCard.addView(Button(activity).apply {
                    text = "Hapus Rumus"
                    isAllCaps = false
                    textSize = 11f
                    setOnClickListener {
                        db.deleteVaultFormula(f.id)
                        Toast.makeText(activity, "Rumus dihapus", Toast.LENGTH_SHORT).show()
                        activeTabRenderer?.invoke()
                    }
                })
                box.addView(fCard, matchWrapBottom(10))
            }
        }
        scroll.addView(box)

        AlertDialog.Builder(activity)
            .setTitle("Bank Rumus • ${course.name}")
            .setView(scroll)
            .setNeutralButton("+ Tambah Rumus Manual") { _, _ ->
                showAddFormulaDialog(courseKey)
            }
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun showAddFormulaDialog(courseKey: String) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        val titleIn = EditText(activity).apply { hint = "Nama Rumus (misal: Waktu Baku)" }
        val exprIn = EditText(activity).apply { hint = "Persamaan (misal: Wb = Wn x (1 + Allowance))" }
        val notesIn = EditText(activity).apply { hint = "Catatan variabel (opsional)" }
        box.addView(titleIn)
        box.addView(exprIn)
        box.addView(notesIn)

        AlertDialog.Builder(activity)
            .setTitle("Tambah Rumus Baru")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val t = titleIn.text.toString().trim()
                val e = exprIn.text.toString().trim()
                if (t.isNotEmpty() && e.isNotEmpty()) {
                    db.insertVaultFormula(courseKey, t, e, notesIn.text.toString().trim(), "manual")
                    Toast.makeText(activity, "Rumus ditambahkan", Toast.LENGTH_SHORT).show()
                    activeTabRenderer?.invoke()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ==================== SHARED UI HELPERS ====================

    private fun showRenameDialog(dialogTitle: String, currentTitle: String, onSave: (String) -> Unit) {
        val input = EditText(activity).apply { setText(currentTitle) }
        AlertDialog.Builder(activity)
            .setTitle(dialogTitle)
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) onSave(v)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showMoveCourseDialog(currentKey: String, onMoved: (String) -> Unit) {
        val names = KuliahVaultEngine.VAULT_COURSES.map { it.name }.toTypedArray()
        val currentIdx = KuliahVaultEngine.VAULT_COURSES.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("Pindahkan ke Mata Kuliah")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                dialog.dismiss()
                onMoved(KuliahVaultEngine.VAULT_COURSES[which].key)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun courseHorizontalPicker(selectedKey: String, enabled: Boolean, onSelect: (String) -> Unit): HorizontalScrollView {
        val scroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in KuliahVaultEngine.VAULT_COURSES) {
            val active = c.key == selectedKey
            val chip = TextView(activity).apply {
                text = c.name
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (active) Color.WHITE else textColor())
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = if (active) {
                    LiquidGlassKit.pillGlassSurface(activity, "#4F46E5", "#CCF8FAFC", 18f)
                } else {
                    LiquidGlassKit.glassSurface(activity, isDark(), 18f, strongRim = false)
                }
                if (enabled) {
                    setOnClickListener { onSelect(c.key) }
                    LiquidGlassKit.attachLiquidTouch(this, 0.95f)
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }
        scroll.addView(row)
        return scroll
    }

    private fun sectionHeader(title: String, subtitle: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor())
            })
            addView(TextView(activity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(subTextColor())
            })
        }
    }

    private fun glassCard(tintHex: String? = null): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = LiquidGlassKit.glassSurface(activity, isDark(), 22f, tintHex)
            LiquidGlassKit.applyGlassElevation(this, 5f, isDark())
            LiquidGlassKit.attachLiquidTouch(this, 0.985f)
        }
    }

    private fun glassActionButton(label: String, fillHex: String, onClick: () -> Unit): Button {
        return Button(activity).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = LiquidGlassKit.pillGlassSurface(activity, fillHex, "#99F8FAFC", 16f)
            setOnClickListener { onClick() }
            LiquidGlassKit.attachLiquidTouch(this)
        }
    }

    private fun statTile(value: String, label: String, accentHex: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = LiquidGlassKit.glassSurface(activity, isDark(), 18f)
            addView(TextView(activity).apply {
                text = value
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(accentHex))
            })
            addView(TextView(activity).apply {
                text = label
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor())
            })
        }
    }

    private fun emptyGlassBox(msg: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = LiquidGlassKit.glassSurface(activity, isDark(), 20f, strongRim = false)
            addView(TextView(activity).apply {
                text = msg
                textSize = 13f
                setTextColor(subTextColor())
                gravity = Gravity.CENTER
            })
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatClock(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun formatDate(ts: Long): String =
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ts))

    private fun formatDateTime(ts: Long): String =
        SimpleDateFormat("dd MMM • HH:mm", Locale.getDefault()).format(Date(ts))

    private fun readerCard(
        bgHex: String,
        borderHex: String,
        badgeTitle: String,
        content: String,
        accentHex: String = "#334155"
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                setStroke(dp(1), Color.parseColor(borderHex))
                cornerRadius = dp(14).toFloat()
            }
            addView(TextView(activity).apply {
                text = badgeTitle
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(accentHex))
            })
            addView(TextView(activity).apply {
                text = content
                textSize = 13f
                setTextColor(Color.parseColor("#0F172A"))
                setTextIsSelectable(true)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun boldLabel(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
    }

    private fun mutedLabel(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#64748B"))
    }

    private fun isDark() = isDarkProvider()
    private fun textColor() = Color.parseColor(if (isDark()) "#F1F5F9" else "#0F172A")
    private fun subTextColor() = Color.parseColor(if (isDark()) "#94A3B8" else "#475569")
    private fun accentTextColor() = Color.parseColor(if (isDark()) "#93C5FD" else "#4338CA")
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun space(hDp: Int) = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(hDp))
    }
    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
    private fun matchWrapBottom(bDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bDp) }
}
