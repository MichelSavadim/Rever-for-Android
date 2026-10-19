package com.rever.reader

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.util.JsonReader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONArray
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.security.MessageDigest
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private data class Book(
        val id: String, val title: String, val pageCount: Int, val folder: File,
        val addedAt: Long, val lastReadAt: Long
    ) {
        val pdf: File get() = File(folder, "document.pdf")
    }

    private val titleParts = Regex("\\d+|\\D+")
    private val titleCollator = Collator.getInstance(Locale("pt", "BR")).apply { strength = Collator.PRIMARY }
    private val librarySortKeys = arrayOf(
        "name_asc", "name_desc", "added_desc", "added_asc",
        "read_desc", "read_asc", "pages_desc", "pages_asc"
    )
    private val librarySortLabels = arrayOf(
        "Nome (A–Z)", "Nome (Z–A)", "Adicionado recentemente", "Adicionado há mais tempo",
        "Lido recentemente", "Lido há mais tempo", "Mais páginas", "Menos páginas"
    )

    private val importRequest = 1001
    private val saveExportRequest = 1002
    private val chooseStorageRequest = 1103
    private val createBackupRequest = 1104
    private val restoreBackupRequest = 1105
    private val io = Executors.newSingleThreadExecutor()
    private val searchWorker = Executors.newSingleThreadExecutor()
    private val searchHandler = Handler(Looper.getMainLooper())
    @Volatile private var searchToken = 0
    private var pendingSearch: Runnable? = null
    private val backup by lazy { ReverBackup(this) }
    private val location by lazy { LibraryLocation(this) }
    private val storageHandler = Handler(Looper.getMainLooper())
    private val syncStorage = Runnable {
        if (!io.isShutdown) io.execute {
            try { location.sync() }
            catch (error: Exception) { android.util.Log.e("ReverStorage", "Falha ao sincronizar a pasta", error) }
        }
    }
    private fun queueStorageSync() {
        if (location.selectedUri() == null) return
        storageHandler.removeCallbacks(syncStorage)
        storageHandler.postDelayed(syncStorage, 3_000)
    }
    private val readingStats by lazy { ReadingStats(this) }
    private val statsHandler = Handler(Looper.getMainLooper())
    private var readingTickAt = 0L
    private var pendingReadingMs = 0L
    private var inForeground = false
    private fun flushReadingTime() {
        if (pendingReadingMs > 0) activeBook?.let {
            readingStats.addDuration(it.id, "reading_ms", pendingReadingMs)
        }
        pendingReadingMs = 0L
    }
    private val readingTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val reading = inForeground && hasWindowFocus() && screenMode == "reader" &&
                activeBook != null && mediaController?.isPlaying != true &&
                (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
            if (reading && readingTickAt > 0L) {
                pendingReadingMs += (now - readingTickAt).coerceAtMost(2_000)
                if (pendingReadingMs >= 15_000) flushReadingTime()
            } else flushReadingTime()
            readingTickAt = if (reading) now else 0L
            statsHandler.postDelayed(this, 1_000)
        }
    }
    private lateinit var root: FrameLayout
    private lateinit var viewer: PdfCanvasView
    private lateinit var title: TextView
    private lateinit var page: TextView
    private lateinit var pageControls: LinearLayout
    private lateinit var playerBar: LinearLayout
    private lateinit var playButton: Button
    private lateinit var speedButton: Button
    private lateinit var audioTime: TextView
    private lateinit var searchRow: LinearLayout
    private lateinit var readerSearchInput: EditText
    private lateinit var readerSearchStatus: TextView
    private lateinit var readerHeader: LinearLayout
    private var screenMode = "reader"
    private var settingsReturn = "library"
    private var librarySelection: Book? = null
    private val librarySelections = linkedSetOf<String>()
    private val categoryStore: CategoryStore by lazy { CategoryStore(filesDir, library) }
    private var categoriesReturn = "settings"
    private val categoryAutoAssignIds = linkedSetOf<String>()
    private var pendingImportCategory: String? = null
    private val updateManager by lazy { UpdateManager(this) }
    private var latestRelease: ReverRelease? = null
    private var updateCheckRunning = false
    private var lastUpdateCheckElapsed = 0L
    private var wasStopped = false
    private var pendingInstallApk: File? = null
    private var messagesReturn = "settings"
    private var panelContent: View? = null
    private var panelOnLeft = true
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureHadMultiplePointers = false
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playbackSpeed = 1f
    private var audioParagraphIndex = -1
    private var activeBook: Book? = null
    private var timeline: DurationTimeline? = null
    private var bookLoadToken = 0
    private var restoringReaderPage = false
    private var paragraphs: List<Paragraph> = emptyList()
    private var ignoredTexts: Set<String> = emptySet()
    private var selectedParagraph: Paragraph? = null
    private var centerSelectionAfterRotation = false
    private val hidePageControls = Runnable { pageControls.visibility = View.GONE }
    private var searchActive = false
    private var currentPage = 1
    private var panel: View? = null
    private var latestExport: File? = null
    private var pendingExportSave: File? = null
    private var exporting = false
    private val library: File by lazy { File(filesDir, "library").apply { mkdirs() } }
    private val density: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backup.recoverIfNeeded()
        root = FrameLayout(this)
        root.setBackgroundColor(backgroundColor())
        viewer = PdfCanvasView(this).apply {
            setDarkMode(darkMode())
            onVisiblePageChanged = { number ->
                currentPage = number
                updatePageLabel()
                if (!restoringReaderPage) {
                    activeBook?.let { readingStats.markPage(it.id, number, it.pageCount) }
                    saveReaderState()
                }
            }
            onViewportMoved = { showPageControls() }
            onPageTap = { number, x, y ->
                val hit = paragraphs.firstOrNull { paragraph ->
                    paragraph.page == number && RectF(paragraph.box).apply {
                        inset(-4f, -4f)
                    }.contains(x, y)
                }
                if (hit != null) {
                    selectedParagraph = if (selectedParagraph?.page == hit.page &&
                        selectedParagraph?.index == hit.index) null else hit
                    refreshHighlight()
                    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        selectedParagraph?.let(::followParagraph)
                    saveReaderState()
                }
            }
        }
        root.addView(viewer, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = dp(56)
            bottomMargin = dp(94)
        })

        readerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor())
            elevation = dp(3).toFloat()
        }
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val searchInput = EditText(this).apply {
            hint = "Pesquisar termo no PDF"
            isSingleLine = true
            setTextColor(textColor())
            setHintTextColor(if (darkMode()) 0xFFB0BEC5.toInt() else Color.GRAY)
        }
        val searchStatus = TextView(this).apply { textSize = 12f; setTextColor(textColor()) }
        readerSearchInput = searchInput
        readerSearchStatus = searchStatus
        var searchMatches: List<Int> = emptyList()
        var searchIndex = -1
        val moveMatch: (Int) -> Unit = { delta ->
            if (searchMatches.isNotEmpty()) {
                searchIndex = (searchIndex + delta + searchMatches.size) % searchMatches.size
                val match = searchMatches[searchIndex]
                viewer.showPage(match)
                searchStatus.text = "${searchIndex + 1}/${searchMatches.size}"
            }
        }
        fun updateReaderSearch(queryText: String) {
            val query = queryText.trim()
            val term = query.replace(Regex("\\s+"), " ")
            searchActive = query.isNotEmpty()
            searchIndex = -1
            searchMatches = emptyList()
            viewer.setSearchBoxes(emptyMap())
            refreshHighlight()
            if (term.isEmpty()) {
                cancelSearch()
                searchStatus.text = "0"
                return
            }
            searchStatus.text = "Pesquisando…"
            queueSearch(term) { result ->
                if (searchRow.visibility != View.VISIBLE || searchInput.text.toString().trim() != query) return@queueSearch
                if (result == null) {
                    searchStatus.text = "Falha na pesquisa"
                    return@queueSearch
                }
                searchMatches = result.pages
                viewer.setSearchBoxes(result.boxes)
                searchStatus.text = "${searchMatches.size}"
                if (searchMatches.isNotEmpty()) moveMatch(1)
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateReaderSearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        searchRow.addView(searchStatus)
        searchRow.addView(button("‹") { moveMatch(-1) })
        searchRow.addView(button("›") { moveMatch(1) })
        searchRow.addView(button("×") { toggleReaderSearch() })
        readerHeader.addView(searchRow, LinearLayout.LayoutParams(-1, dp(48)))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val libraryButton = button("☰") { showLibrary() }
        libraryButton.contentDescription = "Biblioteca"
        header.addView(libraryButton, LinearLayout.LayoutParams(dp(52), -1))
        title = TextView(this).apply {
            text = "Rever"
            textSize = 18f
            setTextColor(textColor())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("⌕") { toggleReaderSearch() }.apply {
            contentDescription = "Pesquisar no PDF"
        }, LinearLayout.LayoutParams(dp(48), -1))
        header.addView(button("⋮") { showSettings() }.apply {
            contentDescription = "Configurações"
        }, LinearLayout.LayoutParams(dp(48), -1))
        readerHeader.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(readerHeader, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
        playerBar = makePlayerBar()
        root.addView(playerBar, FrameLayout.LayoutParams(-1, dp(94), Gravity.BOTTOM))
        page = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xDD263746.toInt())
            }
            elevation = dp(5).toFloat()
            contentDescription = "Ir para uma página"
            setOnClickListener { promptPage() }
        }
        val pageHandle = TextView(this).apply {
            text = "⌃\n⌄"
            textSize = 20f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setLineSpacing(-dp(11).toFloat(), 1f)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD263746.toInt())
            }
            elevation = dp(5).toFloat()
            contentDescription = "Arrastar para navegar pelas páginas"
        }
        pageControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(page, LinearLayout.LayoutParams(dp(86), dp(44)).apply { rightMargin = dp(4) })
            addView(pageHandle, LinearLayout.LayoutParams(dp(52), dp(52)))
        }
        var pageDown = 0f
        var pageAtDown = 1
        pageHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pageDown = event.rawY
                    pageAtDown = currentPage
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    activeBook?.let { book ->
                        if (book.pageCount > 1) {
                            val fraction = ((pageAtDown - 1).toFloat() / (book.pageCount - 1) +
                                (event.rawY - pageDown) / pageControlTravel().coerceAtLeast(1)).coerceIn(0f, 1f)
                            val target = 1 + (fraction * (book.pageCount - 1)).toInt()
                            if (target != currentPage) {
                                currentPage = target
                                viewer.showPage(target)
                                updatePageLabel()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        root.addView(pageControls, FrameLayout.LayoutParams(dp(142), dp(52), Gravity.END or Gravity.TOP).apply {
            topMargin = dp(64)
            rightMargin = -dp(14)
        })
        setContentView(root)
        connectPlayer()
        backup.schedule()
        val books = loadBooks()
        val last = getPreferences(MODE_PRIVATE).getString("last_book_id", null)
        val startup = getPreferences(MODE_PRIVATE).getString("startup_screen", "library")
        getPreferences(MODE_PRIVATE).edit().remove("startup_screen").apply()
        when (startup) {
            "settings" -> {
                val returnTo = getPreferences(MODE_PRIVATE).getString("settings_return", "library") ?: "library"
                if (returnTo == "reader") books.firstOrNull { it.id == last }?.let(::showBook)
                showSettings()
                settingsReturn = returnTo
            }
            "library" -> showLibrary()
            else -> showLibrary()
        }
        updatePageLabel()
        if (location.selectedUri() != null) io.execute {
            try {
                location.sync()
                runOnUiThread { if (screenMode == "library") showLibrary() }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this,
                    "Pasta de armazenamento indisponível: ${error.message}", Toast.LENGTH_LONG).show() }
            }
        }
        latestRelease = getPreferences(MODE_PRIVATE).getString("latest_release", null)?.let {
            try { ReverRelease.fromJson(JSONObject(it)) } catch (_: Exception) { null }
        }
        if (intent.getBooleanExtra("open_messages", false)) showMessages()
        showStartupChangelog()
    }

    private fun makePlayerBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(surfaceColor())
            elevation = dp(4).toFloat()
        }
        audioTime = TextView(this).apply {
            text = "00:00:00 / ≈ 00:00:00"
            textSize = 13f
            setTextColor(textColor())
            maxLines = 1
        }
        bar.addView(audioTime)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addControl(control: Button) {
            row.addView(control, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                leftMargin = dp(1)
                rightMargin = dp(1)
            })
        }
        addControl(playerButton("⏮", "Página anterior") { jumpPage(-1) })
        addControl(playerButton("❮", "Parágrafo anterior") { jumpParagraph(-1) })
        addControl(playerButton("↶", "Voltar 10 segundos") { seekBy(-10_000) })
        playButton = playerButton("▶", "Reproduzir ou pausar", primary = true) { togglePlayback() }
        addControl(playButton)
        addControl(playerButton("↷", "Avançar 10 segundos") { seekBy(10_000) })
        addControl(playerButton("❯", "Próximo parágrafo") { jumpParagraph(1) })
        addControl(playerButton("⏭", "Próxima página") { jumpPage(1) })
        addControl(playerButton("#", "Reproduzir página específica") { promptPageAudio() })
        speedButton = playerButton("1×", "Velocidade de reprodução", smallText = true) { cycleSpeed() }
        addControl(speedButton)
        bar.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
        return bar
    }

    private fun playerButton(
        symbol: String, description: String, primary: Boolean = false,
        smallText: Boolean = false, click: () -> Unit
    ): Button = button(symbol, click).apply {
        contentDescription = description
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        gravity = Gravity.CENTER
        textSize = if (smallText) 12f else 24f
        stylePlayerButton(this, primary)
    }

    private fun stylePlayerButton(control: Button, primary: Boolean) {
        control.setTextColor(if (primary) Color.WHITE else textColor())
        val shape = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(if (primary) 0xFF315C7D.toInt() else if (darkMode()) 0xFF33414F.toInt()
                else 0xFFD9E1EA.toInt())
        }
        control.background = RippleDrawable(ColorStateList.valueOf(0x5588AACC), shape, null)
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                mediaController = controller
                playbackSpeed = controller.playbackParameters.speed
                speedButton.text = speedLabel()
                controller.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playButton.text = if (isPlaying) "❚❚" else "▶"
                        refreshHighlight()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val index = mediaItem?.mediaId?.toIntOrNull() ?: return
                        if (index in paragraphs.indices) {
                            audioParagraphIndex = index
                            selectedParagraph = paragraphs[index]
                            refreshHighlight()
                            followParagraph(paragraphs[index])
                            saveReaderState()
                        }
                    }
                })
                controller.currentMediaItem?.mediaId?.toIntOrNull()?.let { index ->
                    if (index in paragraphs.indices) {
                        audioParagraphIndex = index
                        selectedParagraph = paragraphs[index]
                        refreshHighlight()
                    }
                }
                updateAudioTime()
            } catch (error: Exception) {
                Toast.makeText(this, "Player indisponível: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }, mainExecutor)
    }

    private fun updateAudioTime() {
        renderAudioTime()
        audioTime.postDelayed({ if (!isFinishing && !isDestroyed) updateAudioTime() }, 1000)
    }

    private fun renderAudioTime() {
        val controller = mediaController
        val currentTimeline = timeline
        if (currentTimeline == null) {
            audioTime.text = "00:00:00 / ≈ 00:00:00"
            return
        }
        val index = controller?.currentMediaItem?.mediaId?.toIntOrNull() ?: -1
        val duration = controller?.duration ?: 0L
        if (duration > 0L && currentTimeline.recordDuration(index, duration)) {
            val snapshot = currentTimeline.snapshot()
            val file = currentTimeline.file
            if (!io.isShutdown) io.execute { writeTextAtomic(file, snapshot) }
        }
        val speed = controller?.playbackParameters?.speed ?: playbackSpeed
        val position = currentTimeline.positionMs(index, controller?.currentPosition ?: 0L, speed)
        val total = currentTimeline.totalMs(speed)
        audioTime.text = "${clock(position)} / ${if (currentTimeline.isExact) "" else "≈ "}${clock(total)}"
    }

    private fun clock(ms: Long): String {
        val seconds = ms / 1000
        return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun togglePlayback() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
            return
        }
        val selected = selectedParagraph
        val selectedIndex = if (selected == null) -1 else paragraphs.indexOfFirst {
            it.page == selected.page && it.index == selected.index
        }
        val currentIndex = controller.currentMediaItem?.mediaId?.toIntOrNull() ?: -1
        if (currentIndex >= 0 && (selectedIndex < 0 || selectedIndex == currentIndex)) {
            controller.play()
            return
        }
        val start = if (selectedIndex >= 0) selectedIndex else paragraphs.indexOfFirst { it.page >= currentPage }
        if (start >= 0) startParagraph(start, manual = selectedIndex >= 0)
    }

    private fun startParagraph(index: Int, manual: Boolean = false) {
        val book = activeBook ?: return
        val paragraph = paragraphs.getOrNull(index) ?: return
        if (!NarrationFiles.hasSpeech(paragraph.text) || (!manual && paragraph.text in ignoredTexts)) {
            val next = ((index + 1) until paragraphs.size).firstOrNull {
                NarrationFiles.automaticSpeech(paragraphs[it], ignoredTexts)
            }
            if (next != null) startParagraph(next)
            return
        }
        playButton.isEnabled = false
        playButton.text = "…"
        val bookId = book.id
        val voice = currentVoice()
        CrashReporter.markNarration(this, bookId, paragraph, "initial_tts")
        io.execute {
            try {
                val path = NarrationFiles.playable(book.folder, voice, paragraph)
                    ?: NarrationFiles.temporary(book.folder, voice, paragraph).also {
                        AndroidTts.generate(this, paragraph, it, voice)
                    }
                runOnUiThread {
                    if (activeBook?.id == bookId) {
                        startService(Intent(this, PlaybackService::class.java).apply {
                            action = PlaybackService.ACTION_CONFIGURE
                            putExtra(PlaybackService.EXTRA_BOOK_ID, bookId)
                            putExtra(PlaybackService.EXTRA_VOICE, voice)
                        })
                        val item = MediaItem.Builder()
                            .setMediaId(index.toString())
                            .setUri(Uri.fromFile(path))
                            .setMediaMetadata(MediaMetadata.Builder()
                                .setTitle("${book.title} — página ${paragraph.page}, parágrafo ${paragraph.index}")
                                .setArtist("Rever")
                                .build())
                            .build()
                        mediaController?.run {
                            setMediaItem(item)
                            prepare()
                            setPlaybackParameters(PlaybackParameters(playbackSpeed))
                            play()
                        }
                        selectedParagraph = paragraph
                        audioParagraphIndex = index
                        refreshHighlight()
                        followParagraph(paragraph)
                        saveReaderState()
                    }
                    playButton.isEnabled = true
                    playButton.text = "❚❚"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    playButton.isEnabled = true
                    playButton.text = "▶"
                    reportNarrationError(error)
                }
            }
        }
    }

    private fun seekBy(offset: Long) {
        mediaController?.let { it.seekTo((it.currentPosition + offset).coerceAtLeast(0)) }
    }

    private fun jumpParagraph(delta: Int) {
        if (paragraphs.isEmpty()) return
        val current = mediaController?.currentMediaItem?.mediaId?.toIntOrNull()
            ?: paragraphs.indexOfFirst { it == selectedParagraph }.takeIf { it >= 0 }
            ?: paragraphs.indexOfFirst { it.page >= currentPage }
        val target = (if (delta >= 0) ((current + 1)..paragraphs.lastIndex).firstOrNull {
            NarrationFiles.automaticSpeech(paragraphs[it], ignoredTexts)
        } else if (current > 0) (current - 1 downTo 0).firstOrNull {
            NarrationFiles.automaticSpeech(paragraphs[it], ignoredTexts)
        } else null) ?: return
        if (target in paragraphs.indices) startParagraph(target)
    }

    private fun jumpPage(delta: Int) {
        val current = mediaController?.currentMediaItem?.mediaId?.toIntOrNull()
            ?.let { paragraphs.getOrNull(it)?.page } ?: currentPage
        val targetPage = current + delta
        val target = if (delta >= 0) paragraphs.indexOfFirst {
            it.page >= targetPage && NarrationFiles.automaticSpeech(it, ignoredTexts)
        } else paragraphs.indexOfLast {
            it.page <= targetPage && NarrationFiles.automaticSpeech(it, ignoredTexts)
        }
        if (target >= 0) startParagraph(target)
    }

    private fun cycleSpeed() {
        val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val index = speeds.indexOfFirst { it == playbackSpeed }
        playbackSpeed = speeds[(index + 1) % speeds.size]
        mediaController?.setPlaybackParameters(PlaybackParameters(playbackSpeed))
        speedButton.text = speedLabel()
        renderAudioTime()
    }

    private fun speedLabel(): String = "${playbackSpeed.toString().removeSuffix(".0")}×"

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = event.rawX
                gestureDownY = event.rawY
                gestureHadMultiplePointers = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> gestureHadMultiplePointers = true
            MotionEvent.ACTION_UP -> {
                if (screenMode == "reader" && !gestureHadMultiplePointers) {
                    val dx = event.rawX - gestureDownX
                    val dy = event.rawY - gestureDownY
                    val origin = IntArray(2)
                    root.getLocationOnScreen(origin)
                    val insideViewer = gestureDownY >= origin[1] + readerHeader.height &&
                        gestureDownY <= origin[1] + root.height - dp(94)
                    if ((panel != null || (insideViewer && !viewer.isZoomed)) &&
                        kotlin.math.abs(dx) > dp(72) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.45f) {
                        super.dispatchTouchEvent(event)
                        if (panel == null) openPanel(dx > 0)
                        else if ((panelOnLeft && dx < 0) || (!panelOnLeft && dx > 0)) closePanel()
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun openPanel(left: Boolean) {
        closePanel(immediate = true)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            alpha = 0f
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(surfaceColor())
            isClickable = true
            elevation = dp(8).toFloat()
        }
        val heading = TextView(this).apply {
            text = if (left) "Biblioteca" else "Opções"
            textSize = 22f
            setTextColor(textColor())
            setPadding(0, 0, 0, dp(12))
        }
        content.addView(heading)
        if (left) {
            content.addView(button("Adicionar PDF") { closePanel(); choosePdf() })
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (book in loadBooks()) {
                list.addView(bookRow(book))
            }
            content.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val book = activeBook
            if (book != null) {
                content.addView(button("Gerar áudio completo") {
                    closePanel(); startExport(book, 1, book.pageCount)
                })
                content.addView(button("Gerar áudio por páginas selecionadas") { showExportDialog() })
                content.addView(button("Abrir pasta do PDF") { closePanel(); showBookFiles(book) })
            }
        }
        val width = minOf(dp(330), (resources.displayMetrics.widthPixels * .84f).toInt())
        overlay.addView(content, FrameLayout.LayoutParams(width, -1, if (left) Gravity.START else Gravity.END))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        panel = overlay
        panelContent = content
        panelOnLeft = left
        content.translationX = if (left) -width.toFloat() else width.toFloat()
        overlay.animate().alpha(1f).setDuration(220).start()
        content.animate().translationX(0f).setDuration(220).start()
    }

    private fun currentVoice(): String = getPreferences(MODE_PRIVATE)
        .getString("tts_voice", AndroidTts.DEFAULT_LANGUAGE) ?: AndroidTts.DEFAULT_LANGUAGE

    private fun currentLanguage(): String = getPreferences(MODE_PRIVATE)
        .getString("tts_language", AndroidTts.DEFAULT_LANGUAGE) ?: AndroidTts.DEFAULT_LANGUAGE

    private fun darkMode() = getPreferences(MODE_PRIVATE).getBoolean("dark_mode", false)
    private fun surfaceColor() = if (darkMode()) 0xFF202833.toInt() else Color.WHITE
    private fun backgroundColor() = if (darkMode()) 0xFF121922.toInt() else 0xFFE7EAEF.toInt()
    private fun textColor() = if (darkMode()) Color.WHITE else 0xFF192026.toInt()

    private fun toggleReaderSearch() {
        if (activeBook == null) return
        val showing = searchRow.visibility != View.VISIBLE
        searchRow.visibility = if (showing) View.VISIBLE else View.GONE
        val height = dp(if (showing) 104 else 56)
        (readerHeader.layoutParams as FrameLayout.LayoutParams).height = height
        readerHeader.requestLayout()
        (viewer.layoutParams as FrameLayout.LayoutParams).topMargin = height
        viewer.requestLayout()
        if (!showing) {
            cancelSearch()
            searchActive = false
            viewer.setSearchBoxes(emptyMap())
            refreshHighlight()
        }
    }

    private fun showLibrary() {
        flushReadingTime()
        readingTickAt = 0L
        bookLoadToken++
        restoringReaderPage = false
        closePanel()
        if (screenMode == "reader") {
            librarySelection = null
            librarySelections.clear()
        }
        if (activeBook != null) {
            saveReaderState()
            timeline?.let { current ->
                val snapshot = current.snapshot()
                if (!io.isShutdown) io.execute { writeTextAtomic(current.file, snapshot) }
            }
            mediaController?.pause()
            mediaController?.clearMediaItems()
            startService(Intent(this, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_CLEAR
            })
            viewer.closeDocument()
            activeBook = null
            paragraphs = emptyList()
            ignoredTexts = emptySet()
            timeline = null
            selectedParagraph = null
            cancelSearch()
            searchActive = false
            audioParagraphIndex = -1
        }
        renderAudioTime()
        screenMode = "library"
        queueStorageSync()
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
            setBackgroundColor(backgroundColor())
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply {
            text = "Biblioteca"
            textSize = 24f
            setTextColor(textColor())
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        val searchField = EditText(this).apply {
            hint = "Buscar PDF"
            isSingleLine = true
            visibility = View.GONE
            setTextColor(textColor())
            setHintTextColor(if (darkMode()) 0xFFB0BEC5.toInt() else Color.GRAY)
        }
        heading.addView(iconButton("⌕", "Buscar PDF") {
            searchField.visibility = if (searchField.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        heading.addView(iconButton("⋮", "Configurações") { showSettings() })
        screen.addView(heading)
        screen.addView(searchField)
        val preferences = getPreferences(MODE_PRIVATE)
        val categories = categoryStore.all()
        val savedCategory = preferences.getString("library_category", "default") ?: "default"
        val activeCategory = savedCategory.takeIf { value ->
            value == "default" || categories.any { it.id == value }
        } ?: "default"
        if (categories.isNotEmpty()) {
            val categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun addTab(id: String, label: String) {
                categoryRow.addView(button(label) {
                    preferences.edit().putString("library_category", id).apply()
                    librarySelections.clear()
                    librarySelection = null
                    showLibrary()
                }.apply {
                    textSize = 13f
                    alpha = if (id == activeCategory) 1f else .65f
                }, LinearLayout.LayoutParams(-2, dp(44)))
            }
            addTab("default", "Padrão")
            categories.forEach { addTab(it.id, it.name) }
            screen.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(categoryRow)
            })
        }
        var gridView = preferences.getBoolean("library_grid", true)
        var sortKey = preferences.getString("library_sort", "name_asc") ?: "name_asc"
        if (sortKey !in librarySortKeys) sortKey = "name_asc"
        lateinit var refreshLibrary: (String) -> Unit
        lateinit var viewButton: Button
        viewButton = button(if (gridView) "▦ Grade" else "☷ Lista") {
            gridView = !gridView
            preferences.edit().putBoolean("library_grid", gridView).apply()
            viewButton.text = if (gridView) "▦ Grade" else "☷ Lista"
            viewButton.contentDescription = if (gridView) "Alternar para lista" else "Alternar para grade"
            refreshLibrary(searchField.text.toString())
        }.apply {
            contentDescription = if (gridView) "Alternar para lista" else "Alternar para grade"
        }
        lateinit var sortButton: Button
        sortButton = button("Ordenar: ${librarySortLabels[librarySortKeys.indexOf(sortKey)]}") {
            AlertDialog.Builder(this).setTitle("Ordenar biblioteca")
                .setSingleChoiceItems(librarySortLabels, librarySortKeys.indexOf(sortKey)) { dialog, position ->
                    sortKey = librarySortKeys[position]
                    preferences.edit().putString("library_sort", sortKey).apply()
                    sortButton.text = "Ordenar: ${librarySortLabels[position]}"
                    refreshLibrary(searchField.text.toString())
                    dialog.dismiss()
                }.setNegativeButton("Cancelar", null).show()
        }.apply {
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        screen.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(viewButton, LinearLayout.LayoutParams(0, dp(48), 0.37f))
            addView(sortButton, LinearLayout.LayoutParams(0, dp(48), 0.63f))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        screen.addView(HorizontalScrollView(this).apply { addView(actions) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        screen.addView(button("+ Adicionar PDF") {
            pendingImportCategory = activeCategory.takeIf { categories.any { category -> category.id == it } }
            choosePdf()
        })
        fun refresh(query: String) {
            list.removeAllViews()
            val visibleBooks = sortBooks(loadBooks().filter { book ->
                if (!book.title.contains(query, ignoreCase = true)) return@filter false
                if (categories.isEmpty()) return@filter true
                val memberships = categoryStore.membership(book.folder)
                if (activeCategory == "default" || categories.none { it.id == activeCategory }) {
                    memberships.isEmpty()
                } else activeCategory in memberships
            }, sortKey)
            librarySelections.retainAll(visibleBooks.map { it.id }.toSet())
            val selectedBooks = visibleBooks.filter { it.id in librarySelections }
            val selected = selectedBooks.singleOrNull()
            librarySelection = selected
            actions.visibility = if (selectedBooks.isEmpty()) View.GONE else View.VISIBLE
            actions.removeAllViews()
            if (selectedBooks.isNotEmpty()) {
                actions.addView(button("Adicionar categoria") { showCategoryPicker(selectedBooks) })
            }
            if (selected != null) {
                actions.addView(button("Abrir") { showBook(selected) })
                actions.addView(button("Ir para a pasta") { showBookFiles(selected) })
                actions.addView(button("Apagar") { confirmDeleteBook(selected) })
                actions.addView(button("Renomear") { renameBook(selected) })
                actions.addView(button("Compartilhar") { shareFile(selected.pdf) })
            }
            fun bindBook(view: View, book: Book) {
                view.setOnClickListener {
                    if (librarySelections.isEmpty()) showBook(book)
                    else {
                        if (!librarySelections.add(book.id)) librarySelections.remove(book.id)
                        refresh(searchField.text.toString())
                    }
                }
                view.setOnLongClickListener {
                    if (!librarySelections.add(book.id)) librarySelections.remove(book.id)
                    refresh(searchField.text.toString())
                    true
                }
            }
            if (gridView) {
                for (pair in visibleBooks.chunked(2)) {
                    val gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    for (book in pair) {
                        val card = bookCard(book, book.id in librarySelections)
                        bindBook(card, book)
                        gridRow.addView(card, LinearLayout.LayoutParams(0, dp(222), 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        })
                    }
                    if (pair.size == 1) gridRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    list.addView(gridRow)
                }
            } else {
                for (book in visibleBooks) {
                    val row = bookRow(book)
                    row.setBackgroundColor(if (book.id in librarySelections) 0x6677AADD else Color.TRANSPARENT)
                    bindBook(row, book)
                    list.addView(row)
                }
            }
        }
        refreshLibrary = ::refresh
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        list.setOnClickListener {
            if (librarySelections.isNotEmpty()) {
                librarySelection = null
                librarySelections.clear()
                refresh(searchField.text.toString())
            }
        }
        refresh("")
        setContentView(screen)
    }

    private fun showSettings() {
        closePanel()
        if (screenMode == "reader" || screenMode == "library") settingsReturn = screenMode
        screenMode = "settings"
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(backgroundColor())
        }
        val settingsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        settingsHeader.addView(button("←") { leaveSettings() }.apply {
            contentDescription = "Voltar"
            textSize = 23f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        settingsHeader.addView(TextView(this).apply {
            text = "Configurações"
            textSize = 26f
            setTextColor(textColor())
        })
        screen.addView(settingsHeader, LinearLayout.LayoutParams(-1, dp(56)))
        screen.addView(button("Narração") { showNarrationSettings() })
        screen.addView(button("Dados e Armazenamento") { showDataSettings() })
        screen.addView(button("Estatísticas") { showStatistics() })
        screen.addView(button("Modo escuro: ${if (darkMode()) "ligado" else "desligado"}") {
            getPreferences(MODE_PRIVATE).edit()
                .putBoolean("dark_mode", !darkMode()).apply()
            applyReaderTheme()
            showSettings()
        })
        screen.addView(button("Categorias") {
            categoriesReturn = "settings"
            categoryAutoAssignIds.clear()
            showCategories()
        })
        screen.addView(button("Mensagens") { showMessages() })
        screen.addView(button("Relatórios de falha") { showCrashReports() })
        screen.addView(button("Sobre") { showAbout() })
        setContentView(screen)
    }

    private fun subScreen(titleText: String, back: () -> Unit): LinearLayout {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(backgroundColor())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("←", back).apply {
            contentDescription = "Voltar"
            textSize = 23f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        header.addView(TextView(this).apply {
            text = titleText
            textSize = 25f
            setTextColor(textColor())
        })
        screen.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        return screen
    }

    private fun showNarrationSettings() {
        screenMode = "narration_settings"
        val screen = subScreen("Narração") { showSettings() }
        screen.addView(button("Idioma: ${currentLanguage()}") { chooseLanguage() })
        screen.addView(button("Voz: ${currentVoice()}") { chooseVoice() })
        screen.addView(button("Instalar mais vozes no Android") {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (_: Exception) {
                AlertDialog.Builder(this).setTitle("Instalar vozes")
                    .setMessage("Abra as Configurações do celular, depois Acessibilidade → Conversão de texto em voz → Instalar dados de voz. Escolha português do Brasil e instale as vozes desejadas.")
                    .setPositiveButton("Entendi", null).show()
            }
        })
        setContentView(screen)
    }

    private fun showStatistics() {
        flushReadingTime()
        screenMode = "statistics"
        val books = loadBooks()
        val history = readingStats.snapshot(books.map { it.id }.toSet())
        val screen = subScreen("Estatísticas") { showSettings() }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun section(label: String) {
            content.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(textColor())
                setPadding(0, dp(20), 0, dp(12))
            })
        }
        fun cells(vararg entries: Pair<String, String>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((value, caption) in entries) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(3), dp(14), dp(3), dp(14))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(surfaceColor())
                    }
                }
                cell.addView(TextView(this).apply {
                    text = value
                    textSize = 19f
                    gravity = Gravity.CENTER
                    setTextColor(textColor())
                })
                cell.addView(TextView(this).apply {
                    text = caption
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(textColor())
                })
                row.addView(cell, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                    rightMargin = dp(4)
                })
            }
            content.addView(row)
        }
        fun duration(millis: Long): String {
            val minutes = millis / 60_000
            return when {
                minutes >= 60 -> "${minutes / 60}h ${minutes % 60}min"
                minutes > 0 -> "${minutes}min"
                else -> "${millis / 1000}s"
            }
        }
        section("Visão geral")
        cells(books.size.toString() to "Na biblioteca",
            duration(history.readingMs) to "Duração total de leitura",
            duration(history.narrationMs) to "Duração de narração",
            history.completed.toString() to "Itens completos")
        section("Itens")
        cells(history.started.toString() to "Iniciados",
            books.count { book -> File(book.folder, "exports").listFiles()?.any { it.extension.equals("mp3", true) } == true }
                .toString() to "Narração local",
            books.sumOf { it.pageCount }.toString() to "Páginas",
            history.pagesRead.toString() to "Páginas lidas")
        scroll.addView(content)
        screen.addView(scroll)
        setContentView(screen)
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var amount = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
        return if (unit == 0) "${amount.toLong()} B" else String.format(Locale.getDefault(), "%.1f %s", amount, units[unit])
    }

    private fun lastBackupDescription(): String {
        val at = backup.lastBackupAt()
        if (at == 0L) return "Nenhum backup criado ainda"
        val elapsed = (System.currentTimeMillis() - at).coerceAtLeast(0L)
        if (elapsed >= 24 * 60 * 60 * 1000L) return "Último backup: " +
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        val seconds = elapsed / 1000
        return "Último backup há " + when {
            seconds < 60 -> "$seconds segundos"
            seconds < 3600 -> "${seconds / 60} minutos"
            else -> "${seconds / 3600} horas e ${(seconds % 3600) / 60} minutos"
        }
    }

    private fun showDataSettings() {
        screenMode = "data_settings"
        val screen = subScreen("Dados e Armazenamento") { showSettings() }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun description(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(textColor())
            setPadding(dp(8), dp(2), dp(8), dp(12))
        }
        val chooseStorage = {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, chooseStorageRequest)
        }
        content.addView(button("Local de Armazenamento") { chooseStorage() })
        content.addView(description(location.label()).apply { setOnClickListener { chooseStorage() } })
        val backupActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backupActions.addView(button("Criar backup") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "Rever-backup-${System.currentTimeMillis()}.zip")
            }, createBackupRequest)
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(4) })
        backupActions.addView(button("Restaurar backup") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }, restoreBackupRequest)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        content.addView(backupActions)
        val frequencies = listOf(0L to "Desligado", 6L to "A cada 6h", 12L to "A cada 12h",
            24L to "Diariamente", 48L to "A cada 2 dias", 168L to "Semanalmente")
        val current = frequencies.firstOrNull { it.first == backup.frequencyHours() }?.second ?: "A cada 12h"
        content.addView(button("Frequência de backup automático: $current") {
            AlertDialog.Builder(this).setTitle("Frequência de backup automático")
                .setSingleChoiceItems(frequencies.map { it.second }.toTypedArray(),
                    frequencies.indexOfFirst { it.first == backup.frequencyHours() }) { dialog, which ->
                    backup.setFrequency(frequencies[which].first)
                    dialog.dismiss()
                    showDataSettings()
                }.setNegativeButton("Cancelar", null).show()
        })
        content.addView(description(lastBackupDescription() + " • Downloads/Rever/Backups"))
        content.addView(TextView(this).apply {
            text = "Uso de armazenamento"
            textSize = 19f
            setTextColor(textColor())
            setPadding(dp(6), dp(18), 0, dp(8))
        })
        val usageBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(0xFFD9E2E9.toInt()) }
            clipToOutline = true
        }
        content.addView(usageBar, LinearLayout.LayoutParams(-1, dp(18)))
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        content.addView(legend)
        content.addView(button("Limpar o cachê dos PDFs") {
            AlertDialog.Builder(this).setTitle("Limpar o cachê dos PDFs?")
                .setMessage("Os arquivos temporários dos PDFs serão removidos. Eles poderão ser criados novamente quando necessários.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar") { _, _ ->
                    io.execute {
                        val removed = pdfCacheFiles().map { it.deleteRecursively() }.all { it }
                        runOnUiThread {
                            viewer.clearRenderCache()
                            Toast.makeText(this, if (removed) "Cache limpo." else "Parte do cache não pôde ser limpa.",
                                Toast.LENGTH_LONG).show()
                            showDataSettings()
                        }
                    }
                }.show()
        })
        val cacheSize = description("Calculando cache…")
        content.addView(cacheSize)
        scroll.addView(content)
        screen.addView(scroll)
        setContentView(screen)
        io.execute {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes.coerceAtLeast(1L)
            val available = stat.availableBytes.coerceIn(0L, total)
            val external = try { location.primaryDeviceBytes() } catch (_: Exception) { 0L }
            val own = (directorySize(filesDir) + directorySize(cacheDir) + external)
                .coerceIn(0L, total - available)
            val other = (total - available - own).coerceAtLeast(0L)
            val cacheBytes = pdfCacheFiles().sumOf(::directorySize)
            runOnUiThread {
                if (screenMode != "data_settings") return@runOnUiThread
                cacheSize.text = "Em cache: ${formatBytes(cacheBytes)}"
                usageBar.removeAllViews()
                legend.removeAllViews()
                val parts = listOf(Triple(own, "Rever", 0xFF438CE8.toInt()),
                    Triple(other, "Outros dados", 0xFF8793A1.toInt()),
                    Triple(available, "Disponível", 0xFFB6D5BA.toInt()))
                for ((bytes, label, color) in parts) {
                    usageBar.addView(View(this).apply { setBackgroundColor(color) },
                        LinearLayout.LayoutParams(0, -1, bytes.toFloat().coerceAtLeast(0.01f)))
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(View(this).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(color)
                        }
                    }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
                    row.addView(TextView(this).apply {
                        text = "$label  ${formatBytes(bytes)}"
                        textSize = 14f
                        setTextColor(textColor())
                    })
                    legend.addView(row, LinearLayout.LayoutParams(-1, dp(25)))
                }
            }
        }
    }

    private fun directorySize(file: File): Long = if (file.isFile) file.length()
        else file.listFiles()?.sumOf(::directorySize) ?: 0L

    private fun pdfCacheFiles(): List<File> = cacheDir.listFiles()?.filter {
        it.name != "updates" && !it.name.startsWith("restore-")
    } ?: emptyList()

    private fun showCategoryPicker(books: List<Book>) {
        val categories = categoryStore.all()
        if (categories.isEmpty()) {
            categoriesReturn = "library"
            categoryAutoAssignIds.clear()
            categoryAutoAssignIds.addAll(books.map { it.id })
            showCategories()
            return
        }
        val memberships = books.map { categoryStore.membership(it.folder) }
        val checked = BooleanArray(categories.size) { index ->
            memberships.all { categories[index].id in it }
        }
        val changed = BooleanArray(categories.size)
        AlertDialog.Builder(this).setTitle("Adicionar categoria")
            .setMultiChoiceItems(categories.map { it.name }.toTypedArray(), checked) { _, index, enabled ->
                checked[index] = enabled
                changed[index] = true
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                try {
                    books.forEach { book ->
                        val ids = categoryStore.membership(book.folder).toMutableSet()
                        categories.indices.filter { changed[it] }.forEach { index ->
                            if (checked[index]) ids.add(categories[index].id)
                            else ids.remove(categories[index].id)
                        }
                        categoryStore.assign(book.folder, ids)
                    }
                    librarySelections.clear()
                    librarySelection = null
                    val firstAdded = categories.indices.firstOrNull { changed[it] && checked[it] }
                    if (firstAdded != null) {
                        getPreferences(MODE_PRIVATE).edit()
                            .putString("library_category", categories[firstAdded].id).apply()
                    }
                    showLibrary()
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "Falha ao salvar categorias", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun showCategories() {
        queueStorageSync()
        screenMode = "categories"
        val screen = FrameLayout(this).apply { setBackgroundColor(backgroundColor()) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(80))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(button("←") { leaveCategories() }.apply {
            contentDescription = "Voltar"
            textSize = 23f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        heading.addView(TextView(this).apply {
            text = "Editar categorias"
            textSize = 26f
            setTextColor(textColor())
        })
        content.addView(heading, LinearLayout.LayoutParams(-1, dp(56)))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((index, category) in categoryStore.all().withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this).apply {
                text = category.name
                textSize = 18f
                setTextColor(textColor())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            titleRow.addView(iconButton("✎", "Editar ${category.name}") {
                showCategoryDialog(category)
            })
            titleRow.addView(iconButton("🗑", "Excluir ${category.name}") {
                AlertDialog.Builder(this).setTitle("Excluir ${category.name}?")
                    .setMessage("Os PDFs dessa categoria voltarão para Padrão se não pertencerem a outra categoria.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ ->
                        try {
                            categoryStore.delete(category.id)
                            showCategories()
                        } catch (error: Exception) {
                            Toast.makeText(this, error.message ?: "Falha ao excluir categoria", Toast.LENGTH_LONG).show()
                        }
                    }.show()
            })
            row.addView(titleRow)
            val orderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            orderRow.addView(iconButton("↑", "Mover ${category.name} para cima") {
                categoryStore.move(category.id, -1)
                showCategories()
            }.apply { isEnabled = index > 0 })
            orderRow.addView(iconButton("↓", "Mover ${category.name} para baixo") {
                categoryStore.move(category.id, 1)
                showCategories()
            }.apply { isEnabled = index < categoryStore.all().lastIndex })
            row.addView(orderRow)
            rows.addView(row)
        }
        content.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        screen.addView(content, FrameLayout.LayoutParams(-1, -1))
        screen.addView(button("+ Adicionar") { showCategoryDialog(null) },
            FrameLayout.LayoutParams(-2, dp(52), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(18)
                bottomMargin = dp(18)
            })
        setContentView(screen)
    }

    private fun showCategoryDialog(existing: BookCategory?) {
        val input = EditText(this).apply {
            hint = "Nome da categoria"
            isSingleLine = true
            setText(existing?.name.orEmpty())
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Adicionar categoria" else "Editar categoria")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (existing == null) "Adicionar" else "Salvar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    if (existing == null) {
                        val added = categoryStore.add(input.text.toString())
                        for (id in categoryAutoAssignIds) {
                            val folder = File(library, id)
                            if (File(folder, "book.json").isFile) {
                                categoryStore.assign(folder, categoryStore.membership(folder) + added.id)
                            }
                        }
                        if (categoryAutoAssignIds.isNotEmpty()) getPreferences(MODE_PRIVATE).edit()
                            .putString("library_category", added.id).apply()
                    } else categoryStore.rename(existing.id, input.text.toString())
                    dialog.dismiss()
                    showCategories()
                } catch (error: Exception) {
                    input.error = error.message ?: "Nome inválido"
                }
            }
        }
        dialog.show()
    }

    private fun leaveCategories() {
        categoryAutoAssignIds.clear()
        if (categoriesReturn == "settings") {
            screenMode = "settings"
            showSettings()
        } else showLibrary()
    }

    private fun leaveSettings() {
        if (settingsReturn == "reader" && activeBook != null) {
            screenMode = "reader"
            setContentView(root)
            showPageControls()
        } else showLibrary()
    }

    private fun applyReaderTheme() {
        root.setBackgroundColor(backgroundColor())
        viewer.setDarkMode(darkMode())
        readerHeader.setBackgroundColor(surfaceColor())
        title.setTextColor(textColor())
        readerSearchInput.setTextColor(textColor())
        readerSearchInput.setHintTextColor(if (darkMode()) 0xFFB0BEC5.toInt() else Color.GRAY)
        readerSearchStatus.setTextColor(textColor())
        playerBar.setBackgroundColor(surfaceColor())
        audioTime.setTextColor(textColor())
        val controls = playerBar.getChildAt(1) as LinearLayout
        for (index in 0 until controls.childCount) {
            val control = controls.getChildAt(index) as Button
            stylePlayerButton(control, control === playButton)
        }
    }

    private fun showAbout() {
        screenMode = "about"
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            setBackgroundColor(backgroundColor())
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(button("←") {
            screenMode = "settings"
            showSettings()
        }.apply {
            contentDescription = "Voltar"
            textSize = 23f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        heading.addView(TextView(this).apply {
            text = "Sobre"
            textSize = 26f
            setTextColor(textColor())
        })
        screen.addView(heading, LinearLayout.LayoutParams(-1, dp(56)))
        screen.addView(ImageView(this).apply {
            setImageResource(R.drawable.rever)
            contentDescription = "Ícone do Rever"
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { topMargin = dp(20); bottomMargin = dp(18) })
        screen.addView(View(this).apply { setBackgroundColor(textColor()) },
            LinearLayout.LayoutParams(-1, dp(1)))
        screen.addView(TextView(this).apply {
            text = "Versão"
            textSize = 16f
            setTextColor(textColor())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(36)))
        val version = installedVersion()
        val versionText = TextView(this).apply {
            text = "stable $version (ainda não publicada)"
            textSize = 16f
            setTextColor(textColor())
            gravity = Gravity.CENTER
        }
        screen.addView(versionText, LinearLayout.LayoutParams(-1, dp(36)))
        screen.addView(button("Procurar atualizações") { checkForUpdates(manual = true) })
        screen.addView(button("O que há de novo") { showChangelog() })
        screen.addView(button("Ir para repositório") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateManager.repositoryUrl)))
        })
        screen.addView(button("Sobre o Rever") { showReadme() })
        setContentView(screen)
        io.execute {
            val release = try { updateManager.current(version) } catch (_: Exception) { null }
            if (release != null) runOnUiThread {
                if (screenMode == "about") {
                    versionText.text = "stable $version (${releaseDate(release.publishedAt)})"
                    if (release.notes.isNotBlank()) getPreferences(MODE_PRIVATE).edit()
                        .putString("current_changelog_version", version)
                        .putString("current_changelog_body", release.notes).apply()
                }
            }
        }
    }

    private fun showReadme() {
        val readme = try { assets.open("README.md").bufferedReader().use { it.readText() } }
            catch (_: Exception) { "Rever — leitor de PDF com narração do Android." }
        AlertDialog.Builder(this).setTitle("Sobre o Rever")
            .setView(ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = readme
                    textSize = 14f
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                })
            }).setPositiveButton("Fechar", null).show()
    }

    private fun installedVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1.2"

    private fun showCrashReports() {
        val reports = CrashReporter.reports(this)
        if (reports.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Relatórios de falha")
                .setMessage("Nenhuma falha foi registrada neste aparelho.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Relatórios de falha")
            .setItems(reports.map { it.name }.toTypedArray()) { _, index ->
                val report = reports[index]
                AlertDialog.Builder(this).setTitle(report.name)
                    .setItems(arrayOf("Salvar arquivo", "Compartilhar arquivo")) { _, action ->
                        if (action == 0) saveFile(report) else shareFile(report)
                    }.show()
            }.setNegativeButton("Fechar", null).show()
    }

    private fun releaseDate(value: String): String = try {
        Instant.parse(value).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    } catch (_: Exception) { "ainda não publicada" }

    private fun showStartupChangelog() {
        val version = installedVersion()
        val preferences = getPreferences(MODE_PRIVATE)
        if (preferences.getString("seen_changelog_version", null) == version) {
            checkForUpdates(manual = false)
            return
        }
        val pendingVersion = preferences.getString("pending_changelog_version", null)
        val pendingText = preferences.getString("pending_changelog_body", null)
        val notes = if (pendingVersion == version && !pendingText.isNullOrBlank()) pendingText
            else currentChangelog()
        AlertDialog.Builder(this).setTitle("Novidades da versão $version")
            .setMessage(notes).setCancelable(false)
            .setPositiveButton("Confirmar") { _, _ ->
                val edit = preferences.edit().putString("seen_changelog_version", version)
                    .remove("pending_changelog_version").remove("pending_changelog_body")
                if (pendingVersion == version && !pendingText.isNullOrBlank()) edit
                    .putString("current_changelog_version", version)
                    .putString("current_changelog_body", pendingText)
                edit.apply()
                requestNotificationPermission()
                checkForUpdates(manual = false)
            }.show()
    }

    private fun currentChangelog(): String = try {
        assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
    } catch (_: Exception) { "Novidades do Rever ${installedVersion()}." }

    private fun showChangelog() {
        val preferences = getPreferences(MODE_PRIVATE)
        val notes = preferences.getString("current_changelog_body", null)
            ?.takeIf { preferences.getString("current_changelog_version", null) == installedVersion() }
            ?: currentChangelog()
        AlertDialog.Builder(this).setTitle("O que há de novo — ${installedVersion()}")
            .setMessage(notes).setPositiveButton("Fechar", null).show()
    }

    private fun requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateCheckRunning) return
        updateCheckRunning = true
        lastUpdateCheckElapsed = SystemClock.elapsedRealtime()
        io.execute {
            val result = try { Result.success(updateManager.latest()) }
                catch (error: Exception) { Result.failure(error) }
            runOnUiThread {
                updateCheckRunning = false
                result.onSuccess { release ->
                    if (release != null) {
                        latestRelease = release
                        getPreferences(MODE_PRIVATE).edit()
                            .putString("latest_release", release.toJson()).apply()
                    }
                    val newerRelease = release?.takeIf {
                        updateManager.isNewer(it.version, installedVersion())
                    }
                    if (screenMode == "messages") showMessages()
                    when {
                        newerRelease != null -> {
                            val skipped = getPreferences(MODE_PRIVATE)
                                .getString("skipped_update", null) == newerRelease.version
                            if (manual || !skipped) {
                                if (!manual) notifyUpdate(newerRelease)
                                showUpdatePrompt(newerRelease)
                            }
                        }
                        manual -> Toast.makeText(this,
                            if (release == null) "Ainda não há um APK publicado no repositório."
                            else "O Rever já está atualizado.", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { error ->
                    if (manual) Toast.makeText(this,
                        "Não foi possível consultar atualizações: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showUpdatePrompt(release: ReverRelease) {
        AlertDialog.Builder(this).setTitle("Rever ${release.version} disponível")
            .setMessage(release.notes.ifBlank { "Uma nova versão está disponível." })
            .setPositiveButton("Atualizar") { _, _ -> downloadUpdate(release) }
            .setNeutralButton("Pular esta versão") { _, _ ->
                getPreferences(MODE_PRIVATE).edit().putString("skipped_update", release.version).apply()
                getSystemService(NotificationManager::class.java).cancel(1001)
                if (screenMode == "messages") showMessages()
            }
            .setNegativeButton("Mais tarde", null).show()
    }

    private fun notifyUpdate(release: ReverRelease) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("rever_updates", "Atualizações do Rever",
            NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 1001,
            Intent(this, MainActivity::class.java).apply {
                putExtra("open_messages", true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1001, Notification.Builder(this, "rever_updates")
            .setSmallIcon(R.drawable.rever)
            .setContentTitle("Rever ${release.version} disponível")
            .setContentText("Toque para ver a atualização.")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun showMessages() {
        if (screenMode != "messages") messagesReturn = screenMode
        screenMode = "messages"
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            setBackgroundColor(backgroundColor())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("←") { leaveMessages() }.apply {
            contentDescription = "Voltar"
            minWidth = 0
            minimumWidth = 0
            textSize = 23f
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        header.addView(TextView(this).apply {
            text = "Mensagens"
            textSize = 26f
            setTextColor(textColor())
        })
        screen.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        val release = latestRelease?.takeIf { updateManager.isNewer(it.version, installedVersion()) }
        if (release == null) {
            screen.addView(TextView(this).apply {
                text = "Nenhuma atualização disponível."
                textSize = 16f
                setTextColor(textColor())
            })
        } else {
            val skipped = getPreferences(MODE_PRIVATE).getString("skipped_update", null) == release.version
            screen.addView(TextView(this).apply {
                text = "Rever ${release.version}${if (skipped) " — avisos ignorados" else ""}\n\n" +
                    release.notes.ifBlank { "Nova versão disponível." }
                textSize = 16f
                setTextColor(textColor())
            })
            screen.addView(button("Atualizar") { downloadUpdate(release) })
            if (!skipped) screen.addView(button("Pular esta versão") {
                getPreferences(MODE_PRIVATE).edit().putString("skipped_update", release.version).apply()
                getSystemService(NotificationManager::class.java).cancel(1001)
                showMessages()
            })
        }
        screen.addView(button("Procurar atualizações") { checkForUpdates(manual = true) })
        setContentView(screen)
    }

    private fun leaveMessages() {
        when (messagesReturn) {
            "about" -> showAbout()
            "reader" -> {
                screenMode = "reader"
                setContentView(root)
            }
            "settings" -> {
                screenMode = "settings"
                showSettings()
            }
            else -> showLibrary()
        }
    }

    private fun downloadUpdate(release: ReverRelease) {
        val cancelled = AtomicBoolean(false)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val status = TextView(this).apply {
            text = "Iniciando download…"
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(status)
            addView(progress)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Baixando Rever ${release.version}")
            .setView(body).setNegativeButton("Cancelar") { _, _ -> cancelled.set(true) }
            .setCancelable(false).create()
        dialog.show()
        io.execute {
            try {
                val apk = updateManager.download(release, cancelled::get) { received, total ->
                    runOnUiThread {
                        if (!cancelled.get()) {
                            progress.isIndeterminate = false
                            progress.progress = (received * 100 / total).toInt().coerceIn(0, 100)
                            status.text = "${received / 1_048_576} / ${total / 1_048_576} MB"
                        }
                    }
                }
                runOnUiThread {
                    dialog.dismiss()
                    if (!cancelled.get()) {
                        getPreferences(MODE_PRIVATE).edit()
                            .putString("pending_changelog_version", release.version)
                            .putString("pending_changelog_body", release.notes).apply()
                        installUpdate(apk)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    if (!cancelled.get()) Toast.makeText(this,
                        error.message ?: "Falha ao baixar o APK", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installUpdate(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = apk
            AlertDialog.Builder(this).setTitle("Permitir instalação")
                .setMessage("Autorize o Rever a instalar aplicativos desta origem. Ao voltar, o instalador do Android será aberto.")
                .setNegativeButton("Agora não", null)
                .setPositiveButton("Abrir configurações") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")))
                }.show()
            return
        }
        pendingInstallApk = null
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        try {
            startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Não foi possível abrir o instalador.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseLanguage() {
        io.execute {
            try {
                val voices = AndroidTts.voices(this)
                val languages = voices.map { it.language }.distinct()
                runOnUiThread {
                    if (languages.isEmpty()) {
                        reportNarrationError(IllegalStateException("Nenhuma voz offline instalada. Instale uma voz nas configurações de síntese de voz do Android."))
                    } else AlertDialog.Builder(this).setTitle("Idioma das vozes offline")
                        .setItems(languages.toTypedArray()) { _, choice ->
                            val language = languages[choice]
                            val voice = voices.first { it.language == language }
                            getPreferences(MODE_PRIVATE).edit()
                                .putString("tts_language", language).putString("tts_voice", voice.id).apply()
                            mediaController?.pause()
                            mediaController?.clearMediaItems()
                            reloadTimelineForVoice()
                            showNarrationSettings()
                        }.show()
                }
            } catch (error: Exception) { runOnUiThread { reportNarrationError(error) } }
        }
    }

    private fun chooseVoice() {
        io.execute {
            try {
                val choices = AndroidTts.voices(this).filter { it.language == currentLanguage() }
                runOnUiThread {
                    if (choices.isEmpty()) {
                        reportNarrationError(IllegalStateException("Não há vozes offline para ${currentLanguage()} instaladas no Android."))
                    } else {
                        var selected = choices.indexOfFirst { it.id == currentVoice() }.coerceAtLeast(0)
                        val dialog = AlertDialog.Builder(this).setTitle("Voz da narração")
                            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), selected) { _, which ->
                                selected = which
                            }
                            .setNeutralButton("Ouvir amostra", null)
                            .setPositiveButton("Usar esta voz", null)
                            .setNegativeButton("Cancelar", null)
                            .create()
                        dialog.setOnShowListener {
                            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                                val voiceId = choices[selected].id
                                io.execute {
                                    try { AndroidTts.preview(this, voiceId) }
                                    catch (error: Exception) { runOnUiThread { reportNarrationError(error) } }
                                }
                            }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                getPreferences(MODE_PRIVATE).edit()
                                    .putString("tts_voice", choices[selected].id).apply()
                                mediaController?.pause()
                                mediaController?.clearMediaItems()
                                reloadTimelineForVoice()
                                dialog.dismiss()
                                if (screenMode == "narration_settings") showNarrationSettings()
                            }
                        }
                        dialog.show()
                    }
                }
            } catch (error: Exception) { runOnUiThread { reportNarrationError(error) } }
        }
    }

    private fun bookRow(book: Book): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { closePanel(); showBook(book) }
        }
        val cover = File(book.folder, "thumbnail.png")
        row.addView(ImageView(this).apply {
            if (cover.isFile) setImageBitmap(BitmapFactory.decodeFile(cover.absolutePath))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Primeira página de ${book.title}"
        }, LinearLayout.LayoutParams(dp(52), dp(72)))
        row.addView(TextView(this).apply {
            text = book.title
            textSize = 16f
            setTextColor(textColor())
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun bookCard(book: Book, selected: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (selected) {
                    if (darkMode()) 0xFF35516E.toInt() else 0xFFD9E9FF.toInt()
                } else surfaceColor())
                setStroke(dp(1), if (selected) 0xFF5794D5.toInt()
                    else if (darkMode()) 0xFF384757.toInt() else 0xFFD1D9E1.toInt())
            }
        }
        val cover = File(book.folder, "thumbnail.png")
        card.addView(ImageView(this).apply {
            if (cover.isFile) setImageBitmap(BitmapFactory.decodeFile(cover.absolutePath))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Primeira página de ${book.title}"
        }, LinearLayout.LayoutParams(-1, dp(142)))
        card.addView(TextView(this).apply {
            text = book.title
            textSize = 14f
            setTextColor(textColor())
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, dp(43)))
        card.addView(TextView(this).apply {
            text = "${book.pageCount} páginas"
            textSize = 12f
            setTextColor(if (darkMode()) 0xFFB8C6D4.toInt() else 0xFF536371.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(20)))
        return card
    }

    private fun closePanel(immediate: Boolean = false) {
        val overlay = panel
        val content = panelContent
        panel = null
        panelContent = null
        if (overlay == null) return
        if (immediate || content == null) {
            overlay.animate().cancel()
            content?.animate()?.cancel()
            root.removeView(overlay)
            return
        }
        val distance = content.width.takeIf { it > 0 } ?: dp(330)
        content.animate().translationX(if (panelOnLeft) -distance.toFloat() else distance.toFloat())
            .setDuration(200).start()
        overlay.animate().alpha(0f).setDuration(200).withEndAction {
            if (overlay.parent === root) root.removeView(overlay)
        }.start()
    }

    private fun promptPage() {
        val book = activeBook ?: return
        closePanel()
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentPage.toString())
            selectAll()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Ir para página (1–${book.pageCount})")
            .setView(input)
            .setPositiveButton("Ir") { _, _ ->
                val target = input.text.toString().toIntOrNull()
                if (target != null && target in 1..book.pageCount) viewer.showPage(target)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptPageAudio() {
        val book = activeBook ?: return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "1–${book.pageCount}"
        }
        AlertDialog.Builder(this).setTitle("Reproduzir a partir da página")
            .setView(input)
            .setPositiveButton("Reproduzir") { _, _ ->
                val pageNumber = input.text.toString().toIntOrNull()
                if (pageNumber != null && pageNumber in 1..book.pageCount) {
                    val index = paragraphs.indexOfFirst {
                        it.page >= pageNumber && NarrationFiles.automaticSpeech(it, ignoredTexts)
                    }
                    if (index >= 0) startParagraph(index)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openSearch() {
        if (activeBook == null) return
        closePanel()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val input = EditText(this).apply { hint = "Texto a procurar"; isSingleLine = true }
        val status = TextView(this).apply { textSize = 14f; setTextColor(Color.DKGRAY) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var matches: List<Int> = emptyList()
        var pointer = -1
        val showMatch = { index: Int ->
            if (matches.isNotEmpty()) {
                pointer = (index + matches.size) % matches.size
                val match = matches[pointer]
                mediaController?.pause()
                viewer.showPage(match)
                status.text = "${pointer + 1} de ${matches.size} resultados"
            }
        }
        controls.addView(button("Anterior") { showMatch(pointer - 1) })
        controls.addView(button("Próximo") { showMatch(pointer + 1) })
        fun updatePanelSearch(queryText: String) {
            val query = queryText.trim()
            pointer = -1
            matches = emptyList()
            viewer.setSearchBoxes(emptyMap())
            if (query.isEmpty()) {
                cancelSearch()
                status.text = "0 resultado(s)"
                return
            }
            status.text = "Pesquisando…"
            queueSearch(query) { result ->
                if (input.text.toString().trim() != query) return@queueSearch
                if (result == null) {
                    status.text = "Falha na pesquisa"
                    return@queueSearch
                }
                matches = result.pages
                viewer.setSearchBoxes(result.boxes)
                status.text = "${matches.size} resultado(s)"
                if (matches.isNotEmpty()) showMatch(0)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePanelSearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        form.addView(input)
        form.addView(status)
        form.addView(controls)
        AlertDialog.Builder(this).setTitle("Pesquisar no PDF").setView(form)
            .setPositiveButton("Fechar", null).setOnDismissListener { cancelSearch() }.show()
    }

    private fun choosePdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, importRequest)
    }

    @Deprecated("Framework file picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == chooseStorageRequest) {
            val uri = data?.data ?: return
            mediaController?.pause()
            saveReaderState()
            Toast.makeText(this, "Transferindo dados para a pasta selecionada…", Toast.LENGTH_LONG).show()
            io.execute {
                try {
                    location.select(uri)
                    runOnUiThread { showDataSettings(); Toast.makeText(this,
                        "Local de armazenamento atualizado.", Toast.LENGTH_LONG).show() }
                } catch (error: Exception) {
                    runOnUiThread { Toast.makeText(this,
                        "Falha ao mudar o local: ${error.message}", Toast.LENGTH_LONG).show() }
                }
            }
            return
        }
        if (resultCode == RESULT_OK && requestCode == createBackupRequest) {
            val uri = data?.data ?: return
            saveReaderState()
            Toast.makeText(this, "Criando backup…", Toast.LENGTH_SHORT).show()
            io.execute {
                try {
                    location.sync()
                    contentResolver.openOutputStream(uri, "w")!!.use(backup::writeTo)
                    runOnUiThread { showDataSettings(); Toast.makeText(this,
                        "Backup criado.", Toast.LENGTH_LONG).show() }
                } catch (error: Exception) {
                    runOnUiThread { Toast.makeText(this,
                        "Falha no backup: ${error.message}", Toast.LENGTH_LONG).show() }
                }
            }
            return
        }
        if (resultCode == RESULT_OK && requestCode == restoreBackupRequest) {
            val uri = data?.data ?: return
            AlertDialog.Builder(this).setTitle("Restaurar backup?")
                .setMessage("Os dados atuais da biblioteca serão substituídos pelos dados do backup selecionado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar") { _, _ ->
                    showLibrary()
                    Toast.makeText(this, "Restaurando backup…", Toast.LENGTH_SHORT).show()
                    io.execute {
                        try {
                            contentResolver.openInputStream(uri)!!.use(backup::restoreFrom)
                            val syncError = try { location.replaceRemote(); null }
                                catch (error: Exception) { error }
                            runOnUiThread {
                                showDataSettings()
                                Toast.makeText(this,
                                    if (syncError == null) "Backup restaurado."
                                    else "Backup restaurado, mas não foi possível sincronizar a pasta: ${syncError.message}",
                                    Toast.LENGTH_LONG).show()
                            }
                        } catch (error: Exception) {
                            runOnUiThread { Toast.makeText(this,
                                "Falha na restauração: ${error.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                }.show()
            return
        }
        if (requestCode == saveExportRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val source = pendingExportSave ?: return
            io.execute {
                try {
                    contentResolver.openOutputStream(uri, "w").use { output ->
                        requireNotNull(output) { "Não foi possível salvar o MP3." }
                        source.inputStream().use { it.copyTo(output) }
                    }
                    runOnUiThread { Toast.makeText(this, "Arquivo salvo.", Toast.LENGTH_LONG).show() }
                } catch (error: Exception) {
                    runOnUiThread { Toast.makeText(this, error.message ?: "Falha ao salvar MP3", Toast.LENGTH_LONG).show() }
                }
            }
            pendingExportSave = null
            return
        }
        if (requestCode != importRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = displayName(uri)
        val categoryId = pendingImportCategory
        pendingImportCategory = null
        Toast.makeText(this, "Importando PDF…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val book = importBook(uri, name, categoryId)
                runOnUiThread { showBook(book) }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(this, error.message ?: "Falha ao importar PDF", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importBook(uri: Uri, name: String, categoryId: String?): Book {
        val temp = File.createTempFile("rever-import-", ".pdf", cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Não foi possível abrir o PDF." }
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }.take(20)
            val folder = File(library, id)
            val existing = File(folder, "book.json")
            if (existing.isFile) {
                if (categoryId != null) categoryStore.assign(folder,
                    categoryStore.membership(folder) + categoryId)
                return parseBook(folder)
            }
            var thumbnail: ByteArray? = null
            val count = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    if (pdf.pageCount > 0) {
                        pdf.openPage(0).use { page ->
                            val width = 180
                            val height = (width.toLong() * page.height / page.width)
                                .coerceIn(1, 2_000).toInt()
                            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            image.eraseColor(Color.WHITE)
                            val matrix = Matrix().apply {
                                postScale(width.toFloat() / page.width, height.toFloat() / page.height)
                            }
                            page.render(image, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val bytes = java.io.ByteArrayOutputStream()
                            image.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                            thumbnail = bytes.toByteArray()
                            image.recycle()
                        }
                    }
                    pdf.pageCount
                }
            }
            require(count > 0) { "O PDF está vazio." }
            folder.mkdirs()
            val extracted = ParagraphExtractor.extractToFiles(this, temp, folder)
            temp.copyTo(File(folder, "document.pdf"), overwrite = true)
            thumbnail?.let { File(folder, "thumbnail.png").writeBytes(it) }
            val title = name.removeSuffix(".pdf").ifBlank { "PDF sem título" }
            val addedAt = System.currentTimeMillis()
            writeTextAtomic(existing, JSONObject().put("id", id).put("title", title)
                .put("page_count", count).put("paragraph_count", extracted.size)
                .put("paragraph_version", ParagraphExtractor.VERSION)
                .put("added_at", addedAt).put("last_read_at", 0L).toString())
            if (categoryId != null) categoryStore.assign(folder, setOf(categoryId))
            val initialTiming = DurationTimeline.load(folder, extracted, currentVoice(),
                NarrationFiles.ignored(folder, extracted))
            writeTextAtomic(initialTiming.file, initialTiming.snapshot())
            queueStorageSync()
            return Book(id, title, count, folder, addedAt, 0L)
        } finally {
            temp.delete()
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return "documento.pdf"
    }

    private fun loadBooks(): List<Book> = library.listFiles()?.mapNotNull { folder ->
        try { if (folder.isDirectory) parseBook(folder) else null } catch (_: Exception) { null }
    }?.sortedWith { a, b -> compareNaturalTitles(a.title, b.title) } ?: emptyList()

    private fun parseBook(folder: File): Book {
        val data = JSONObject(File(folder, "book.json").readText(Charsets.UTF_8))
        val pdf = File(folder, "document.pdf")
        val state = File(folder, "reader_state.json")
        val book = Book(data.getString("id"), data.getString("title"), data.getInt("page_count"), folder,
            data.optLong("added_at", pdf.lastModified()),
            data.optLong("last_read_at", if (state.isFile) state.lastModified() else 0L))
        require(book.pdf.isFile) { "PDF não encontrado." }
        return book
    }

    private fun compareNaturalTitles(a: String, b: String): Int {
        val left = titleParts.findAll(a).map { it.value }.toList()
        val right = titleParts.findAll(b).map { it.value }.toList()
        for (index in 0 until minOf(left.size, right.size)) {
            val x = left[index]
            val y = right[index]
            val numeric = x[0].isDigit() && y[0].isDigit()
            val difference = if (numeric) {
                val nx = x.trimStart('0').ifEmpty { "0" }
                val ny = y.trimStart('0').ifEmpty { "0" }
                compareValues(nx.length, ny.length).takeIf { it != 0 }
                    ?: nx.compareTo(ny).takeIf { it != 0 }
                    ?: compareValues(x.length, y.length)
            } else titleCollator.compare(x, y)
            if (difference != 0) return difference
        }
        return compareValues(left.size, right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private fun sortBooks(books: List<Book>, key: String): List<Book> = books.sortedWith { a, b ->
        val primary = when (key) {
            "name_desc" -> compareNaturalTitles(b.title, a.title)
            "added_desc" -> compareValues(b.addedAt, a.addedAt)
            "added_asc" -> compareValues(a.addedAt, b.addedAt)
            "read_desc" -> compareValues(b.lastReadAt, a.lastReadAt)
            "read_asc" -> compareValues(a.lastReadAt, b.lastReadAt)
            "pages_desc" -> compareValues(b.pageCount, a.pageCount)
            "pages_asc" -> compareValues(a.pageCount, b.pageCount)
            else -> compareNaturalTitles(a.title, b.title)
        }
        if (primary != 0) primary else compareNaturalTitles(a.title, b.title)
    }

    private fun showBook(book: Book) {
        flushReadingTime()
        try {
            val metadata = JSONObject(File(book.folder, "book.json").readText(Charsets.UTF_8))
            metadata.put("last_read_at", System.currentTimeMillis())
            writeTextAtomic(File(book.folder, "book.json"), metadata.toString())
            val rebuildParagraphs = metadata.optInt("paragraph_version", 0) < ParagraphExtractor.VERSION
            val state = File(book.folder, "reader_state.json")
            val savedState = if (state.isFile) JSONObject(state.readText(Charsets.UTF_8)) else JSONObject()
            val savedPage = savedState.optInt("page", 1).coerceIn(1, book.pageCount)
            val savedParagraph = if (rebuildParagraphs) null else savedState.optJSONObject("paragraph")
            val loadToken = ++bookLoadToken
            if (activeBook?.id != book.id) {
                mediaController?.pause()
                mediaController?.clearMediaItems()
                if (activeBook != null) startService(Intent(this, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_CLEAR
                })
                audioParagraphIndex = -1
            }
            restoringReaderPage = true
            viewer.open(book.pdf)
            activeBook = book
            readingStats.markOpened(book.id)
            paragraphs = emptyList()
            ignoredTexts = emptySet()
            cancelSearch()
            timeline = null
            renderAudioTime()
            playButton.isEnabled = false
            playButton.text = "…"
            searchActive = false
            if (searchRow.visibility == View.VISIBLE) {
                searchRow.visibility = View.GONE
                (readerHeader.layoutParams as FrameLayout.LayoutParams).height = dp(56)
                (viewer.layoutParams as FrameLayout.LayoutParams).topMargin = dp(56)
                (searchRow.getChildAt(0) as EditText).setText("")
            }
            latestExport = File(book.folder, "exports").listFiles { file -> file.extension == "mp3" }
                ?.maxByOrNull { it.lastModified() }
            selectedParagraph = savedParagraph?.let { reference ->
                val bounds = reference.optJSONArray("bbox")
                val box = if (bounds?.length() == 4) RectF(
                    bounds.getDouble(0).toFloat(), bounds.getDouble(1).toFloat(),
                    bounds.getDouble(2).toFloat(), bounds.getDouble(3).toFloat()
                ) else RectF()
                Paragraph(reference.optInt("page"), reference.optInt("index"), "", box)
            }
            centerSelectionAfterRotation = false
            refreshHighlight()
            title.text = book.title
            currentPage = savedPage
            getPreferences(MODE_PRIVATE).edit().putString("last_book_id", book.id).apply()
            screenMode = "reader"
            setContentView(root)
            updatePageLabel()
            showPageControls()
            root.post {
                if (loadToken == bookLoadToken && activeBook?.id == book.id) {
                    val selection = selectedParagraph
                    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        selection != null && !selection.box.isEmpty) followParagraph(selection)
                    else viewer.showPage(savedPage)
                    currentPage = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        selection != null && !selection.box.isEmpty) selection.page else savedPage
                    restoringReaderPage = false
                    readingStats.markPage(book.id, currentPage, book.pageCount)
                    updatePageLabel()
                    if (rebuildParagraphs || paragraphs.isNotEmpty()) saveReaderState()
                }
            }
            if (rebuildParagraphs) {
                Toast.makeText(this, "Atualizando parágrafos em segundo plano…", Toast.LENGTH_SHORT).show()
            }
            io.execute {
                try {
                    val loaded = if (rebuildParagraphs) {
                        val extracted = ParagraphExtractor.extractToFiles(this, book.pdf, book.folder)
                        metadata.put("paragraph_count", extracted.size)
                            .put("paragraph_version", ParagraphExtractor.VERSION)
                        writeTextAtomic(File(book.folder, "book.json"), metadata.toString())
                        extracted
                    } else loadParagraphs(book)
                    val ignored = NarrationFiles.ignored(book.folder, loaded)
                    runOnUiThread {
                        if (loadToken == bookLoadToken && activeBook?.id == book.id) {
                            paragraphs = loaded
                            ignoredTexts = ignored
                            selectedParagraph = savedParagraph?.let { reference ->
                                loaded.firstOrNull {
                                    it.page == reference.optInt("page") && it.index == reference.optInt("index")
                                }
                            }
                            refreshHighlight()
                            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                                !centerSelectionAfterRotation) selectedParagraph?.let(::followParagraph)
                            if (centerSelectionAfterRotation) {
                                selectedParagraph?.let { viewer.centerOn(it.page, it.box) }
                                centerSelectionAfterRotation = false
                            }
                            playButton.isEnabled = loaded.isNotEmpty()
                            playButton.text = if (mediaController?.isPlaying == true) "❚❚" else "▶"
                            if (!restoringReaderPage) saveReaderState()
                        }
                    }
                    val timelineVoice = currentVoice()
                    val loadedTimeline = DurationTimeline.load(book.folder, loaded, timelineVoice, ignored)
                    writeTextAtomic(loadedTimeline.file, loadedTimeline.snapshot())
                    runOnUiThread {
                        if (loadToken == bookLoadToken && activeBook?.id == book.id) {
                            if (timelineVoice == currentVoice()) timeline = loadedTimeline
                            else reloadTimelineForVoice()
                            renderAudioTime()
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (loadToken == bookLoadToken) {
                            Toast.makeText(this, "Falha ao carregar parágrafos: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            restoringReaderPage = false
            Toast.makeText(this, error.message ?: "Não foi possível abrir o PDF", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveReaderState() {
        val book = activeBook ?: return
        val selected = selectedParagraph
        val paragraphJson = if (selected == null) JSONObject.NULL else JSONObject()
            .put("page", selected.page).put("index", selected.index)
            .put("bbox", JSONArray().put(selected.box.left).put(selected.box.top)
                .put(selected.box.right).put(selected.box.bottom))
        writeTextAtomic(File(book.folder, "reader_state.json"),
            JSONObject().put("page", currentPage).put("paragraph", paragraphJson).toString())
    }

    private fun writeTextAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, file.name + ".tmp")
        try {
            partial.writeText(text, Charsets.UTF_8)
            check(partial.renameTo(file)) { "Não foi possível salvar ${file.name}." }
            queueStorageSync()
        } finally {
            partial.delete()
        }
    }

    private fun refreshHighlight() {
        val playing = if (mediaController?.isPlaying == true) paragraphs.getOrNull(audioParagraphIndex) else null
        val highlighted = playing ?: if (searchActive) null else selectedParagraph
        viewer.setHighlight(highlighted?.page ?: 0, highlighted?.box)
    }

    private fun followParagraph(paragraph: Paragraph) {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            !paragraph.box.isEmpty) viewer.centerOn(paragraph.page, paragraph.box)
        else viewer.showPage(paragraph.page)
    }

    private fun loadParagraphs(book: Book): List<Paragraph> {
        val index = File(book.folder, "paragraphs_index.json")
        if (index.isFile) {
            try {
                val data = JSONArray(index.readText(Charsets.UTF_8))
                return (0 until data.length()).map { Paragraph.fromJson(data.getJSONObject(it)) }
            } catch (_: Exception) { /* Rebuild the index from the full file. */ }
        }
        val file = File(book.folder, "paragraphs.json")
        if (!file.isFile) return emptyList()
        val result = mutableListOf<Paragraph>()
        JsonReader(file.reader(Charsets.UTF_8)).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var page = 0
                var number = 0
                var text = ""
                var box = RectF()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "page" -> page = reader.nextInt()
                        "index" -> number = reader.nextInt()
                        "text" -> text = reader.nextString()
                        "bbox" -> {
                            reader.beginArray()
                            box = RectF(reader.nextDouble().toFloat(), reader.nextDouble().toFloat(),
                                reader.nextDouble().toFloat(), reader.nextDouble().toFloat())
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                result.add(Paragraph(page, number, text, box))
            }
            reader.endArray()
        }
        writeParagraphIndex(book.folder, result)
        return result
    }

    private fun writeParagraphIndex(folder: File, values: List<Paragraph>) {
        writeTextAtomic(File(folder, "paragraphs_index.json"), JSONArray().apply {
            values.forEach { paragraph ->
                put(JSONObject().put("page", paragraph.page).put("index", paragraph.index)
                    .put("text", paragraph.text)
                    .put("bbox", JSONArray().put(paragraph.box.left).put(paragraph.box.top)
                        .put(paragraph.box.right).put(paragraph.box.bottom)))
            }
        }.toString())
    }

    private fun cancelSearch() {
        searchToken++
        pendingSearch?.let(searchHandler::removeCallbacks)
        pendingSearch = null
    }

    private fun queueSearch(term: String, onReady: (PdfSearchResult?) -> Unit) {
        val book = activeBook ?: return
        cancelSearch()
        val token = searchToken
        val loadToken = bookLoadToken
        val task = Runnable {
            if (!searchWorker.isShutdown) searchWorker.execute {
                val result = try {
                    PdfSearch.find(File(book.folder, "paragraphs.json"), term) {
                        token == searchToken && loadToken == bookLoadToken
                    }
                } catch (error: Exception) {
                    android.util.Log.e("ReverSearch", "Falha ao pesquisar no PDF", error)
                    null
                }
                runOnUiThread {
                    if (token == searchToken && loadToken == bookLoadToken && activeBook?.id == book.id) {
                        onReady(result)
                    }
                }
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 250)
    }

    private fun reloadTimelineForVoice() {
        val book = activeBook ?: return
        if (paragraphs.isEmpty()) return
        val voice = currentVoice()
        val loadedParagraphs = paragraphs
        val loadToken = bookLoadToken
        timeline = null
        renderAudioTime()
        io.execute {
            val updated = DurationTimeline.load(book.folder, loadedParagraphs, voice, ignoredTexts)
            writeTextAtomic(updated.file, updated.snapshot())
            runOnUiThread {
                if (loadToken == bookLoadToken && activeBook?.id == book.id && currentVoice() == voice) {
                    timeline = updated
                    renderAudioTime()
                }
            }
        }
    }

    private fun showExportDialog() {
        val book = activeBook ?: return
        if (exporting) return
        closePanel()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val start = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
            hint = "Página inicial"
        }
        val end = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(book.pageCount.toString())
            hint = "Página final"
        }
        val count = TextView(this).apply { textSize = 14f; setTextColor(Color.DKGRAY) }
        val update = {
            val first = start.text.toString().toIntOrNull()
            val last = end.text.toString().toIntOrNull()
            count.text = if (first != null && last != null && first in 1..book.pageCount &&
                last in first..book.pageCount) "${last - first + 1} página(s) selecionada(s)"
                else "Intervalo inválido"
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update() }
            override fun afterTextChanged(s: Editable?) {}
        }
        start.addTextChangedListener(watcher)
        end.addTextChangedListener(watcher)
        form.addView(start)
        form.addView(end)
        form.addView(count)
        update()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Gerar MP3")
            .setView(form)
            .setPositiveButton("Gerar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = start.text.toString().toIntOrNull()
                val last = end.text.toString().toIntOrNull()
                if (first != null && last != null && first in 1..book.pageCount && last in first..book.pageCount) {
                    dialog.dismiss()
                    startExport(book, first, last)
                } else {
                    Toast.makeText(this, "Escolha um intervalo válido.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun startExport(book: Book, first: Int, last: Int) {
        val voice = currentVoice()
        val entries = paragraphs.filter {
            it.page in first..last && NarrationFiles.automaticSpeech(it, ignoredTexts)
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, "Esse intervalo não contém texto narrável.", Toast.LENGTH_LONG).show()
            return
        }
        exporting = true
        val cancelled = AtomicBoolean(false)
        val deleteCreated = AtomicBoolean(false)
        val started = SystemClock.elapsedRealtime()
        var completed = 0
        val status = TextView(this).apply {
            textSize = 15f
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Gerando MP3")
            .setView(status)
            .setNegativeButton("Cancelar geração", null)
            .setCancelable(false)
            .create()
        progressDialog.setOnShowListener {
            progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Cancelar geração?")
                    .setMessage("Os áudios criados antes desta tentativa serão preservados.")
                    .setPositiveButton("Cancelar e apagar trechos novos") { _, _ ->
                        deleteCreated.set(true); cancelled.set(true)
                    }
                    .setNeutralButton("Cancelar e preservar trechos novos") { _, _ ->
                        cancelled.set(true)
                    }
                    .setNegativeButton("Continuar", null)
                    .show()
            }
        }
        val ticker = object : Runnable {
            override fun run() {
                if (!exporting || activeBook?.id != book.id) return
                val elapsed = SystemClock.elapsedRealtime() - started
                val eta = if (completed > 0) elapsed * (entries.size - completed) / completed else 0L
                status.text = "${completed}/${entries.size} parágrafos\nTempo decorrido: ${clock(elapsed)}\n" +
                    "Tempo estimado de conclusão: ${if (completed > 0) clock(eta) else "calculando…"}"
                status.postDelayed(this, 1000)
            }
        }
        progressDialog.show()
        ticker.run()
        io.execute {
            val created = mutableListOf<File>()
            try {
                val paths = entries.mapIndexed { index, paragraph ->
                    if (cancelled.get()) throw ExportCancelled()
                    val target = NarrationFiles.permanent(book.folder, voice, paragraph)
                    if (!target.isFile || target.length() == 0L) {
                        target.parentFile?.mkdirs()
                        val reusable = NarrationFiles.temporary(book.folder, voice, paragraph)
                        if (reusable.isFile && reusable.length() > 0) {
                            val partial = File(target.parentFile, target.name + ".part")
                            try {
                                reusable.copyTo(partial, overwrite = true)
                                check(partial.renameTo(target)) { "Não foi possível guardar um trecho MP3." }
                            } finally { partial.delete() }
                        } else {
                            AndroidTts.generate(this, paragraph, target, voice)
                        }
                        created.add(target)
                    }
                    runOnUiThread { completed = index + 1 }
                    target
                }
                if (cancelled.get()) throw ExportCancelled()
                val label = if (first == 1 && last == book.pageCount) "completo"
                    else "paginas-$first-$last"
                val output = File(book.folder, "exports/$label-$voice.mp3")
                Mp3Joiner.join(paths, output) { cancelled.get() }
                runOnUiThread {
                    latestExport = output
                    Toast.makeText(this, "MP3 concluído: ${output.name}", Toast.LENGTH_LONG).show()
                }
            } catch (_: ExportCancelled) {
                if (deleteCreated.get()) created.forEach { it.delete() }
                runOnUiThread { Toast.makeText(this, "Geração cancelada.", Toast.LENGTH_LONG).show() }
            } catch (error: Exception) {
                runOnUiThread { reportNarrationError(error) }
            } finally {
                queueStorageSync()
                runOnUiThread { exporting = false; progressDialog.dismiss() }
            }
        }
    }

    private fun saveLatestExport() {
        val source = latestExport?.takeIf { it.isFile } ?: return
        closePanel()
        pendingExportSave = source
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_TITLE, source.name)
        }, saveExportRequest)
    }

    private fun reportNarrationError(error: Exception) {
        val detail = error.message.orEmpty()
        AlertDialog.Builder(this).setTitle("Falha na narração")
            .setMessage(detail.ifBlank { "O mecanismo de voz do Android não conseguiu gerar o áudio." })
            .setPositiveButton("Entendi", null).show()
    }

    private fun showBookFiles(book: Book) {
        val files = book.folder.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedBy { it.relativeTo(book.folder).path }.toList()
        val names = files.map { it.relativeTo(book.folder).path.replace('\\', '/') }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Pasta interna: ${book.title}")
            .setItems(names) { _, index ->
                val file = files[index]
                AlertDialog.Builder(this).setTitle(file.name)
                    .setItems(arrayOf("Salvar fora do Rever", "Compartilhar")) { _, action ->
                        if (action == 0) saveFile(file) else shareFile(file)
                    }.show()
            }.setPositiveButton("Fechar", null).show()
    }

    private fun saveFile(source: File) {
        pendingExportSave = source
        val mime = if (source.extension.equals("pdf", true)) "application/pdf"
            else if (source.extension.equals("mp3", true)) "audio/mpeg"
            else if (source.extension.equals("log", true) || source.extension.equals("trace", true)) "text/plain"
            else "application/octet-stream"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, source.name)
        }, saveExportRequest)
    }

    private fun shareFile(source: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", source)
        val mime = if (source.extension.equals("pdf", true)) "application/pdf"
            else if (source.extension.equals("mp3", true)) "audio/mpeg"
            else if (source.extension.equals("log", true) || source.extension.equals("trace", true)) "text/plain"
            else "application/octet-stream"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartilhar ${source.name}"))
    }

    private fun renameBook(book: Book) {
        val input = EditText(this).apply { setText(book.title); selectAll(); isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Renomear PDF").setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val file = File(book.folder, "book.json")
                    val data = JSONObject(file.readText(Charsets.UTF_8)).put("title", name)
                    writeTextAtomic(file, data.toString())
                    librarySelection = null
                    showLibrary()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun confirmDeleteBook(book: Book) {
        AlertDialog.Builder(this).setTitle("Apagar ${book.title}?")
            .setMessage("O PDF, os áudios, os parágrafos, o tempo estimado e o ponto de leitura serão apagados do Rever.")
            .setPositiveButton("Apagar") { _, _ ->
                if (book.folder.canonicalFile.parentFile == library.canonicalFile && !io.isShutdown) {
                    io.execute {
                        val deleted = book.folder.deleteRecursively()
                        if (deleted) {
                            readingStats.removeBook(book.id)
                            queueStorageSync()
                        }
                        runOnUiThread {
                            if (deleted) {
                                if (getPreferences(MODE_PRIVATE).getString("last_book_id", null) == book.id) {
                                    getPreferences(MODE_PRIVATE).edit().remove("last_book_id").apply()
                                }
                                if (latestExport?.path?.startsWith(book.folder.path + File.separator) == true) latestExport = null
                                if (pendingExportSave?.path?.startsWith(book.folder.path + File.separator) == true) pendingExportSave = null
                                librarySelection = null
                                showLibrary()
                            } else {
                                Toast.makeText(this, "Não foi possível apagar todos os arquivos do PDF.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun updatePageLabel() {
        page.text = activeBook?.let { "$currentPage / ${it.pageCount}" } ?: ""
        val count = activeBook?.pageCount ?: 0
        if (screenMode != "reader" || count == 0) pageControls.visibility = View.GONE
        pageControls.translationY = if (count > 1) {
            (currentPage - 1).toFloat() / (count - 1) * pageControlTravel()
        } else 0f
    }

    private fun showPageControls() {
        if (screenMode != "reader" || activeBook == null) return
        pageControls.removeCallbacks(hidePageControls)
        pageControls.visibility = View.VISIBLE
        pageControls.postDelayed(hidePageControls, 3_000)
    }

    private fun pageControlTravel(): Int =
        (root.height - dp(64) - dp(94) - dp(16) - dp(52)).coerceAtLeast(0)

    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { click() }
        isAllCaps = false
    }

    private fun iconButton(symbol: String, description: String, click: () -> Unit): Button =
        button(symbol, click).apply {
            contentDescription = description
            textSize = 19f
            minWidth = dp(44)
            minHeight = dp(44)
        }

    @Deprecated("Framework back callback")
    override fun onBackPressed() {
        when {
            panel != null -> closePanel()
            screenMode == "messages" -> leaveMessages()
            screenMode == "about" -> {
                screenMode = "settings"
                showSettings()
            }
            screenMode == "categories" -> leaveCategories()
            screenMode == "narration_settings" || screenMode == "data_settings" ||
                screenMode == "statistics" -> showSettings()
            screenMode == "settings" -> leaveSettings()
            screenMode == "reader" -> showLibrary()
            screenMode == "library" && librarySelections.isNotEmpty() -> {
                librarySelection = null
                librarySelections.clear()
                showLibrary()
            }
            else -> super.onBackPressed()
        }
    }

    private fun dp(value: Int): Int = (value * density + .5f).toInt()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_messages", false)) showMessages()
    }

    override fun onStop() {
        wasStopped = true
        queueStorageSync()
        super.onStop()
    }

    override fun onPause() {
        inForeground = false
        readingTickAt = 0L
        flushReadingTime()
        statsHandler.removeCallbacks(readingTick)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        statsHandler.removeCallbacks(readingTick)
        statsHandler.post(readingTick)
        if (wasStopped) {
            wasStopped = false
            val apk = pendingInstallApk
            if (apk != null && apk.isFile && packageManager.canRequestPackageInstalls()) {
                installUpdate(apk)
            } else if (lastUpdateCheckElapsed > 0 &&
                SystemClock.elapsedRealtime() - lastUpdateCheckElapsed > 60_000) {
                checkForUpdates(manual = false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val pageBeforeRotation = currentPage
        super.onConfigurationChanged(newConfig)
        if (screenMode == "reader" && activeBook != null) {
            val observer = viewer.viewTreeObserver
            observer.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    observer.removeOnGlobalLayoutListener(this)
                    if (screenMode == "reader" && activeBook != null) {
                        val selection = selectedParagraph
                        if (selection != null && !selection.box.isEmpty) {
                            viewer.centerOn(selection.page, selection.box)
                            centerSelectionAfterRotation = false
                        } else {
                            viewer.showPage(pageBeforeRotation)
                            centerSelectionAfterRotation = selection != null
                        }
                        updatePageLabel()
                    }
                }
            })
            viewer.requestLayout()
        }
    }

    override fun onDestroy() {
        inForeground = false
        statsHandler.removeCallbacks(readingTick)
        bookLoadToken++
        cancelSearch()
        viewer.closeDocument()
        io.shutdown()
        searchWorker.shutdown()
        controllerFuture?.let(MediaController::releaseFuture)
        super.onDestroy()
    }
}
