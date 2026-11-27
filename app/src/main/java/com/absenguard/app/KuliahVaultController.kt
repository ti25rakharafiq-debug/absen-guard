package com.absenguard.app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.MediaRecorder
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

    // Recording state
    private var recordCourseKey: String = "umum"
    private var isRecording = false
    private var recordModeStt = true
    private var recordStartMs = 0L
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
    private var recordActionBtn: Button? = null

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
            container.addView(sectionHeader("Materi PDF Kuliah", "Otomatis diklasifikasikan ke folder mata kuliah dari isi teksnya"))
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
                        text = "${analysis.definitions.size} definisi • ${analysis.formulas.size} rumus"
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
            .setTitle("Memproses PDF")
            .setMessage("Membaca teks, merangkum materi, dan mendeteksi mata kuliah...")
            .setCancelable(false)
            .show()

        Thread {
            try {
                val rawName = queryDisplayName(uri) ?: "Materi_${System.currentTimeMillis()}.pdf"
                val file = KuliahVaultEngine.importFileToInternalStorage(activity, uri, "pdf", rawName.removeSuffix(".pdf"), "pdf")
                val extraction = KuliahVaultEngine.extractPdfInfo(file)
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
                val analysis = if (extraction.scanned) {
                    TextAnalysisResult(emptyList(), emptyList(), emptyList())
                } else {
                    KuliahVaultEngine.analyzeText(extraction.text)
                }

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
            val scanWarn = if (extraction.scanned) "\n⚠️ PDF hasil pindai gambar (teks minim)." else ""
            text = "Deteksi Otomatis: ${cls.label} ($pct%)$kw$scanWarn"
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
        val opts = arrayOf("Buka Pembaca & Ringkasan", "Buka File PDF Asli", "Pindahkan ke Folder Lain", "Ganti Nama", "Hapus PDF")
        AlertDialog.Builder(activity)
            .setTitle(pdf.title)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showPdfReaderDialog(pdf.id)
                    1 -> openExternalPdf(pdf.fileUri)
                    2 -> showMoveCourseDialog(pdf.courseKey) { newKey ->
                        db.moveVaultPdf(pdf.id, newKey)
                        renderPdfTab(container)
                    }
                    3 -> showRenameDialog("Ganti Nama PDF", pdf.title) { newTitle ->
                        db.renameVaultPdf(pdf.id, newTitle)
                        renderPdfTab(container)
                    }
                    4 -> {
                        File(pdf.fileUri).delete()
                        db.deleteVaultPdf(pdf.id)
                        renderPdfTab(container)
                    }
                }
            }
            .show()
    }

    fun showPdfReaderDialog(pdfId: Long, highlightQuery: String = "") {
        val pdf = db.getVaultPdf(pdfId) ?: return
        val analysis = KuliahVaultEngine.parseAnalysisJson(pdf.analysisJson)

        val scroll = ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        box.addView(TextView(activity).apply {
            text = "${KuliahVaultEngine.courseByKey(pdf.courseKey).name} • ${pdf.pageCount} halaman"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0284C7"))
        })
        box.addView(space(10))

        val openRawBtn = Button(activity).apply {
            text = "Buka Dokumen PDF Asli"
            isAllCaps = false
            setOnClickListener { openExternalPdf(pdf.fileUri) }
        }
        box.addView(openRawBtn)
        box.addView(space(12))

        box.addView(boldLabel("Ringkasan Otomatis (${analysis.summary.size})"))
        if (analysis.summary.isEmpty()) {
            box.addView(mutedLabel("Ringkasan tidak tersedia untuk dokumen ini."))
        } else {
            analysis.summary.forEach { s ->
                box.addView(TextView(activity).apply {
                    text = "• $s"
                    textSize = 13f
                    setPadding(0, dp(4), 0, dp(4))
                })
            }
        }
        box.addView(space(12))

        box.addView(boldLabel("Definisi Penting (${analysis.definitions.size})"))
        if (analysis.definitions.isEmpty()) {
            box.addView(mutedLabel("Tidak ada pola definisi terdeteksi."))
        } else {
            analysis.definitions.forEach { (term, desc) ->
                box.addView(TextView(activity).apply {
                    text = "▸ $term: $desc"
                    textSize = 13f
                    setPadding(0, dp(4), 0, dp(4))
                })
            }
        }
        box.addView(space(12))

        box.addView(boldLabel("Rumus Terdeteksi (${analysis.formulas.size})"))
        if (analysis.formulas.isEmpty()) {
            box.addView(mutedLabel("Tidak ada rumus terdeteksi."))
        } else {
            analysis.formulas.forEach { (label, expr) ->
                val fRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setBackgroundColor(Color.parseColor("#FEF3C7"))
                }
                fRow.addView(TextView(activity).apply {
                    text = expr
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                    setTextColor(Color.parseColor("#0F172A"))
                })
                val saveFBtn = Button(activity).apply {
                    text = "+ Simpan ke Bank Rumus"
                    isAllCaps = false
                    textSize = 11f
                    setOnClickListener {
                        db.insertVaultFormula(pdf.courseKey, label, expr, "Dari PDF: ${pdf.title}", "pdf")
                        Toast.makeText(activity, "Rumus disimpan ke Bank Rumus", Toast.LENGTH_SHORT).show()
                    }
                }
                fRow.addView(saveFBtn)
                box.addView(fRow, matchWrapBottom(8))
            }
        }
        box.addView(space(12))

        box.addView(boldLabel("Kutipan Teks PDF"))
        val previewText = if (highlightQuery.isNotBlank()) {
            KuliahVaultEngine.makeSnippet(pdf.bodyText, highlightQuery.lowercase())
        } else {
            pdf.bodyText.take(2500).ifBlank { "(PDF hasil scan tanpa lapisan teks)" }
        }
        box.addView(TextView(activity).apply {
            text = previewText
            textSize = 12f
            setTextIsSelectable(true)
        })

        scroll.addView(box)
        AlertDialog.Builder(activity)
            .setTitle(pdf.title)
            .setView(scroll)
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

    // ==================== 2. TAB REKAM DOSEN & TRANSKRIP ====================

    fun renderRecordTab(container: LinearLayout) {
        activeTabRenderer = { renderRecordTab(container) }
        container.removeAllViews()

        container.addView(sectionHeader("Rekam Dosen & Transkrip", "Rekam penjelasan dosen dengan penanda waktu otomatis"))
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
            text = if (recordModeStt) "Mode: Transkrip Suara Otomatis (ID)" else "Mode: Rekam Audio HD (.m4a)"
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
        recCard.addView(space(8))

        timerTextView = TextView(activity).apply {
            text = if (isRecording) formatClock(System.currentTimeMillis() - recordStartMs) else "00:00"
            textSize = 36f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor())
            gravity = Gravity.CENTER
        }
        recCard.addView(timerTextView)
        recCard.addView(space(10))

        recordActionBtn = glassActionButton(
            if (isRecording) "■ Berhenti & Simpan Rekaman" else "● Mulai Rekam Dosen",
            if (isRecording) "#DC2626" else "#4F46E5"
        ) {
            if (isRecording) {
                stopRecordingAndSave(container)
            } else {
                startRecordingFlow(container)
            }
        }
        recCard.addView(recordActionBtn, matchWrap())

        if (isRecording || liveSegments.isNotEmpty()) {
            recCard.addView(space(10))
            liveTranscriptTextView = TextView(activity).apply {
                text = buildLiveTranscriptPreview()
                textSize = 13f
                setTextColor(textColor())
                setPadding(dp(10), dp(10), dp(10), dp(10))
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
                    val segInfo = if (rec.segments.isNotEmpty()) "${rec.segments.size} kalimat transkrip" else "Audio HD"
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
        recordStartMs = System.currentTimeMillis()
        isRecording = true

        if (recordModeStt && SpeechRecognizer.isRecognitionAvailable(activity)) {
            startSpeechSession()
        } else {
            recordModeStt = false
            startAudioFileRecorder()
        }
        startTimerTicker()
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
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96000)
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

    private fun startSpeechSession() {
        if (!isRecording) return
        try {
            speechRecognizer?.destroy()
            val sr = SpeechRecognizer.createSpeechRecognizer(activity)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {
                    if (currentSegStartMs < 0L) {
                        currentSegStartMs = (System.currentTimeMillis() - recordStartMs).coerceAtLeast(lastSegmentEndMs)
                    }
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (isRecording) {
                        mainHandler.postDelayed({ startSpeechSession() }, 350L)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    val nowRel = System.currentTimeMillis() - recordStartMs
                    if (text.isNotEmpty()) {
                        val start = if (currentSegStartMs >= 0L) currentSegStartMs else (nowRel - 1500L).coerceAtLeast(lastSegmentEndMs)
                        liveSegments.add(RecordingSegment(text, start, nowRel))
                        lastSegmentEndMs = nowRel
                    }
                    currentSegStartMs = -1L
                    interimTranscript = ""
                    liveTranscriptTextView?.text = buildLiveTranscriptPreview()
                    if (isRecording) {
                        mainHandler.postDelayed({ startSpeechSession() }, 250L)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (currentSegStartMs < 0L) {
                            currentSegStartMs = (System.currentTimeMillis() - recordStartMs).coerceAtLeast(lastSegmentEndMs)
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
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            sr.startListening(intent)
            speechRecognizer = sr
        } catch (e: Exception) {
            recordModeStt = false
            startAudioFileRecorder()
        }
    }

    private fun stopRecordingAndSave(container: LinearLayout) {
        isRecording = false
        val totalDuration = (System.currentTimeMillis() - recordStartMs).coerceAtLeast(1000L)

        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        if (interimTranscript.isNotBlank()) {
            val start = if (currentSegStartMs >= 0L) currentSegStartMs else lastSegmentEndMs
            liveSegments.add(RecordingSegment(interimTranscript.trim(), start, totalDuration))
            interimTranscript = ""
        }

        try {
            mediaRecorder?.stop()
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
            val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(recordStartMs))
            db.insertVaultRecording(
                courseKey = recordCourseKey,
                title = "${c.name} - $stamp",
                parts = recordedParts.toList(),
                durationMs = totalDuration,
                segments = liveSegments.toList()
            )
            Toast.makeText(activity, "Rekaman tersimpan di ${c.name}", Toast.LENGTH_SHORT).show()
        }
        liveSegments.clear()
        recordedParts.clear()
        renderRecordTab(container)
    }

    private fun startTimerTicker() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRecording) return
                timerTextView?.text = formatClock(System.currentTimeMillis() - recordStartMs)
                mainHandler.postDelayed(this, 500L)
            }
        }, 500L)
    }

    private fun buildLiveTranscriptPreview(): String {
        val sb = StringBuilder()
        liveSegments.takeLast(8).forEach { seg ->
            sb.append("[${formatClock(seg.startMs)}] ${seg.text}\n")
        }
        if (interimTranscript.isNotBlank()) {
            sb.append("... $interimTranscript")
        }
        return sb.toString().trim().ifEmpty { "Mendengarkan suara dosen..." }
    }

    private fun showRecordingOptionsDialog(rec: VaultRecording, container: LinearLayout) {
        val opts = arrayOf("Putar & Baca Transkrip", "Ganti Nama", "Pindahkan ke Mata Kuliah Lain", "Hapus Rekaman")
        AlertDialog.Builder(activity)
            .setTitle(rec.title)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showRecordingPlayerDialog(rec.id)
                    1 -> showRenameDialog("Ganti Nama Rekaman", rec.title) { newTitle ->
                        db.renameVaultRecording(rec.id, newTitle)
                        renderRecordTab(container)
                    }
                    2 -> showMoveCourseDialog(rec.courseKey) { newKey ->
                        db.moveVaultRecording(rec.id, newKey)
                        renderRecordTab(container)
                    }
                    3 -> {
                        rec.parts.forEach { File(it.uri).delete() }
                        db.deleteVaultRecording(rec.id)
                        renderRecordTab(container)
                    }
                }
            }
            .show()
    }

    fun showRecordingPlayerDialog(recordingId: Long) {
        val rec = db.getVaultRecording(recordingId) ?: return
        var player: MediaPlayer? = null
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

        val scroll = ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        box.addView(TextView(activity).apply {
            text = "${KuliahVaultEngine.courseByKey(rec.courseKey).name} • Durasi ${formatClock(rec.durationMs)}"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4F46E5"))
        })
        box.addView(space(10))

        if (player != null) {
            val playBtn = Button(activity).apply {
                text = "▶ Putar Audio"
                isAllCaps = false
                setOnClickListener {
                    if (player.isPlaying) {
                        player.pause()
                        text = "▶ Lanjutkan Audio"
                    } else {
                        player.start()
                        text = "⏸ Jeda Audio"
                    }
                }
            }
            box.addView(playBtn)
            box.addView(space(10))
        }

        box.addView(boldLabel("Transkrip & Penanda Waktu (${rec.segments.size})"))
        if (rec.segments.isEmpty()) {
            box.addView(mutedLabel("Sesi ini direkam dalam mode audio tanpa transkrip teks."))
        } else {
            rec.segments.forEach { seg ->
                val segView = TextView(activity).apply {
                    text = "[${formatClock(seg.startMs)}]  ${seg.text}"
                    textSize = 13f
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    if (player != null) {
                        setOnClickListener {
                            player.seekTo(seg.startMs.toInt().coerceIn(0, player.duration))
                            if (!player.isPlaying) player.start()
                            Toast.makeText(activity, "Melompat ke ${formatClock(seg.startMs)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                box.addView(segView)
            }
        }

        scroll.addView(box)
        AlertDialog.Builder(activity)
            .setTitle(rec.title)
            .setView(scroll)
            .setOnDismissListener {
                try {
                    player?.stop()
                    player?.release()
                } catch (_: Exception) {
                }
            }
            .setPositiveButton("Tutup", null)
            .show()
    }

    // ==================== 3. TAB PAPAN TULIS & OCR ====================

    fun renderBoardTab(container: LinearLayout) {
        activeTabRenderer = { renderBoardTab(container) }
        container.removeAllViews()

        container.addView(sectionHeader("Scan Papan Tulis & OCR", "Konfirmasi mata kuliah, foto papan tulis, baca teks & rumus offline"))
        container.addView(space(10))

        // Step 1 Card
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

        // Step 2 Card
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

        // Saved Boards
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
                    text = "${formatDateTime(b.createdAt)}${if (b.enhanced) " • Scan Tajam" else ""}"
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
        var useSharp = false
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
            text = "Mode Scan Tajam: NONAKTIF (Ketuk untuk Aktifkan)"
            isAllCaps = false
            setOnClickListener {
                useSharp = !useSharp
                if (useSharp) {
                    text = "Memproses Kontras Tinggi..."
                    isEnabled = false
                    Thread {
                        try {
                            val out = enhancedFile ?: KuliahVaultEngine.enhanceWhiteboard(activity, sourceUri)
                            enhancedFile = out
                            mainHandler.post {
                                previewImage.setImageBitmap(BitmapFactory.decodeFile(out.absolutePath))
                                text = "Mode Scan Tajam: AKTIF ✓"
                                isEnabled = true
                            }
                        } catch (_: Exception) {
                            mainHandler.post {
                                useSharp = false
                                text = "Mode Scan Tajam Gagal (Pakai Asli)"
                                isEnabled = true
                            }
                        }
                    }.start()
                } else {
                    previewImage.setImageURI(sourceUri)
                    text = "Mode Scan Tajam: NONAKTIF"
                }
            }
        }
        box.addView(sharpToggleBtn)

        AlertDialog.Builder(activity)
            .setTitle("Pratinjau Scan Papan Tulis")
            .setView(box)
            .setPositiveButton("Simpan & Baca Teks (OCR)") { _, _ ->
                val loading = AlertDialog.Builder(activity)
                    .setTitle("Membaca Teks Papan Tulis")
                    .setMessage("Menjalankan OCR offline...")
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
        val scroll = ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        box.addView(boldLabel("Teks Terdeteksi OCR"))
        box.addView(TextView(activity).apply {
            text = ocrText.ifBlank { "Tidak ada teks terdeteksi pada gambar ini." }
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, dp(12))
        })

        if (formulas.isNotEmpty()) {
            box.addView(boldLabel("Rumus Terdeteksi (${formulas.size})"))
            formulas.forEach { (label, expr) ->
                val fBox = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setBackgroundColor(Color.parseColor("#FEF3C7"))
                }
                fBox.addView(TextView(activity).apply {
                    text = expr
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
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

        // Global Vault Search Box
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

        // Vault Stats Row
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

        // Study Tools per Course
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
            analysis.summary.forEachIndexed { idx, s ->
                cards.add("Poin Ringkasan #${idx + 1} (${p.title})" to s)
            }
        }
        if (cards.isEmpty()) {
            Toast.makeText(activity, "Impor PDF di ${course.name} terlebih dahulu untuk membuat Flashcard otomatis.", Toast.LENGTH_LONG).show()
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
            textSize = 16f
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
                    setBackgroundColor(Color.parseColor("#FEF3C7"))
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
