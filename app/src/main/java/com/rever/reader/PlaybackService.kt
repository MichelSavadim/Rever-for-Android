package com.rever.reader

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File
import java.util.concurrent.Executors

/** Keeps narration alive while the app is in the background or the screen is locked. */
class PlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_CONFIGURE = "com.rever.reader.CONFIGURE_NARRATION"
        const val ACTION_CLEAR = "com.rever.reader.CLEAR_NARRATION"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_VOICE = "voice"
    }

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val worker = Executors.newSingleThreadExecutor()
    private val indexWorker = Executors.newSingleThreadExecutor()
    private val storageWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val syncStorage = Runnable {
        if (!storageWorker.isShutdown) storageWorker.execute {
            try { LibraryLocation(this).sync() }
            catch (error: Exception) { Log.e("ReverStorage", "Falha ao sincronizar áudios", error) }
        }
    }
    private var bookFolder: File? = null
    private var paragraphs: List<Paragraph> = emptyList()
    private var ignoredTexts: Set<String> = emptySet()
    private var voice = AndroidTts.DEFAULT_LANGUAGE
    private var preparing = -1
    private var token = 0
    private val readingStats by lazy { ReadingStats(this) }
    private var narrationStartedAt = 0L
    private val durationFlush = object : Runnable {
        override fun run() {
            flushNarrationTime()
            main.postDelayed(this, 15_000)
        }
    }

    private fun flushNarrationTime() {
        val started = narrationStartedAt
        if (started == 0L) return
        narrationStartedAt = if (player.isPlaying) SystemClock.elapsedRealtime() else 0L
        bookFolder?.name?.let {
            readingStats.addDuration(it, "narration_ms", SystemClock.elapsedRealtime() - started)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                flushNarrationTime()
                narrationStartedAt = if (isPlaying) SystemClock.elapsedRealtime() else 0L
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val current = mediaItem?.mediaId?.toIntOrNull() ?: return
                main.post {
                    if (player.currentMediaItemIndex > 0) {
                        val previous = player.getMediaItemAt(0).localConfiguration?.uri?.path
                        player.removeMediaItem(0)
                        previous?.let(::deleteTemporary)
                    }
                    prepareNext(current)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.currentMediaItem?.mediaId?.toIntOrNull()?.let { current ->
                        if ((current + 1 until paragraphs.size).none {
                            NarrationFiles.automaticSpeech(paragraphs[it], ignoredTexts)
                        }) bookFolder?.name?.let(readingStats::markCompleted)
                        prepareNext(current)
                    }
                }
            }
        })
        session = MediaSession.Builder(this, player).build()
        main.postDelayed(durationFlush, 15_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLEAR) {
            flushNarrationTime()
            token++
            val old = bookFolder
            bookFolder = null
            paragraphs = emptyList()
            ignoredTexts = emptySet()
            preparing = -1
            player.pause()
            player.clearMediaItems()
            old?.let { folder -> worker.execute { clearTemporary(folder) } }
            main.removeCallbacks(syncStorage)
            main.postDelayed(syncStorage, 5_000)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONFIGURE) {
            val id = intent.getStringExtra(EXTRA_BOOK_ID)
            if (id?.matches(Regex("[a-f0-9]{20}")) == true) {
                val folder = File(File(filesDir, "library"), id)
                if (folder.isDirectory) {
                    flushNarrationTime()
                    val old = bookFolder
                    token++
                    bookFolder = folder
                    if (old != null && old != folder) worker.execute { clearTemporary(old) }
                    paragraphs = emptyList()
                    ignoredTexts = emptySet()
                    val configuredToken = token
                    indexWorker.execute {
                        val loaded = try { NarrationFiles.paragraphs(folder) }
                            catch (error: Exception) {
                                Log.e("ReverPlayback", "Falha ao carregar índice de narração", error)
                                emptyList()
                            }
                        val ignored = try { NarrationFiles.ignored(folder, loaded) }
                            catch (error: Exception) {
                                Log.e("ReverPlayback", "Falha ao carregar parágrafos ignorados", error)
                                NarrationFiles.repeatedTexts(loaded)
                            }
                        main.post {
                            if (configuredToken == token && bookFolder == folder) {
                                paragraphs = loaded
                                ignoredTexts = ignored
                                player.currentMediaItem?.mediaId?.toIntOrNull()?.let(::prepareNext)
                            }
                        }
                    }
                    voice = intent.getStringExtra(EXTRA_VOICE) ?: AndroidTts.DEFAULT_LANGUAGE
                    preparing = -1
                    player.currentMediaItem?.mediaId?.toIntOrNull()?.let(::prepareNext)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun prepareNext(current: Int) {
        val folder = bookFolder ?: return
        if (preparing >= 0 || player.mediaItemCount > player.currentMediaItemIndex + 1) return
        val next = ((current + 1) until paragraphs.size).firstOrNull {
            NarrationFiles.automaticSpeech(paragraphs[it], ignoredTexts)
        } ?: return
        preparing = next
        val activeToken = token
        val activeVoice = voice
        val paragraph = paragraphs[next]
        CrashReporter.markNarration(this, folder.name, paragraph, "next_tts")
        worker.execute {
            var path: File? = null
            var error: Exception? = null
            try {
                path = NarrationFiles.playable(folder, activeVoice, paragraph)
                if (path == null) {
                    path = NarrationFiles.temporary(folder, activeVoice, paragraph)
                    AndroidTts.generate(this, paragraph, path, activeVoice)
                    main.removeCallbacks(syncStorage)
                    main.postDelayed(syncStorage, 5_000)
                }
            } catch (e: Exception) {
                error = e
                Log.e("ReverPlayback", "Falha ao preparar o próximo parágrafo", e)
            }
            main.post {
                if (activeToken == token) {
                    preparing = -1
                    if (path != null && error == null) {
                        val item = mediaItem(next, path!!, paragraph)
                        if (player.playbackState == Player.STATE_ENDED) {
                            player.setMediaItem(item)
                            player.prepare()
                            player.play()
                        } else {
                            player.addMediaItem(item)
                        }
                    }
                }
            }
        }
    }

    private fun mediaItem(index: Int, path: File, paragraph: Paragraph): MediaItem = MediaItem.Builder()
        .setMediaId(index.toString())
        .setUri(Uri.fromFile(path))
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle("Página ${paragraph.page}, parágrafo ${paragraph.index}")
            .setArtist("Rever")
            .build())
        .build()

    private fun deleteTemporary(path: String) {
        val folder = bookFolder ?: return
        val tempRoot = File(folder, "temp_audio").canonicalPath + File.separator
        val file = File(path)
        if (file.canonicalPath.startsWith(tempRoot)) file.delete()
    }

    private fun clearTemporary(folder: File) {
        val tempRoot = File(folder, "temp_audio")
        if (tempRoot.isDirectory && tempRoot.canonicalFile.parentFile == folder.canonicalFile) {
            tempRoot.deleteRecursively()
        }
        // A queued synthesis can finish after the book was deleted and recreate its folder.
        if (!File(folder, "book.json").exists() && !File(folder, "document.pdf").exists()) {
            folder.delete()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        main.removeCallbacks(durationFlush)
        main.removeCallbacks(syncStorage)
        flushNarrationTime()
        token++
        worker.shutdown()
        indexWorker.shutdown()
        storageWorker.shutdown()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        bookFolder?.let(::clearTemporary)
        super.onDestroy()
    }
}
