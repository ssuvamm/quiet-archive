package com.monochrome.annas.data

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One player shared by the audio screen and the foreground-service notification,
 * so playback continues when the screen is closed and lock-screen controls work.
 */
object AudioController {
    private var player: MediaPlayer? = null
    private var session: MediaSessionCompat? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sleepJob: Job? = null
    private var saveJob: Job? = null

    var bookId: String = ""
        private set
    var title: String = ""
        private set
    var source: String = ""
        private set

    private val _playing = MutableStateFlow(false)
    val playing = _playing.asStateFlow()
    private val _position = MutableStateFlow(0)
    val position = _position.asStateFlow()
    private val _duration = MutableStateFlow(0)
    val duration = _duration.asStateFlow()
    private val _speed = MutableStateFlow(1f)
    val speed = _speed.asStateFlow()
    private val _sleepUntil = MutableStateFlow(0L)
    val sleepUntil = _sleepUntil.asStateFlow()

    fun start(context: Context, sourceUri: String, id: String, bookTitle: String, resumeMs: Int) {
        appContext = context.applicationContext
        _speed.value = context.getAudioSpeed()
        if (this.source != sourceUri || player == null) {
            releasePlayer()
            this.source = sourceUri
            this.bookId = id
            this.title = bookTitle
            player = MediaPlayer().apply {
                try {
                    if (sourceUri.startsWith("content://") || sourceUri.startsWith("file://")) {
                        setDataSource(appContext!!, Uri.parse(sourceUri))
                    } else {
                        setDataSource(sourceUri)
                    }
                } catch (e: Exception) {
                    return@apply
                }
                setOnPreparedListener { mp ->
                    _duration.value = mp.duration.coerceAtLeast(0)
                    val target = resumeMs.coerceIn(0, _duration.value)
                    runCatching { mp.seekTo(target) }
                    _position.value = target
                    runCatching { mp.playbackParams = mp.playbackParams.setSpeed(_speed.value) }
                    runCatching { mp.start() }
                    _playing.value = true
                    publishState()
                }
                setOnCompletionListener {
                    _playing.value = false
                    _position.value = _duration.value
                    appContext?.setAudioPosition(bookId, _duration.value.toLong())
                    publishState()
                }
                prepareAsync()
            }
        } else {
            this.bookId = id
            this.title = bookTitle
            if (!_playing.value) play()
        }
        initSession(context)
        startSaveLoop()
    }

    private fun initSession(context: Context) {
        if (session != null) return
        session = MediaSessionCompat(context, "QuietArchiveAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = skip(30_000)
                override fun onSkipToPrevious() = skip(-30_000)
                override fun onSeekTo(pos: Long) = seek(pos.toInt())
                override fun onSetPlaybackSpeed(speed: Float) = setSpeed(speed)
            })
            isActive = true
        }
        publishState()
    }

    val sessionToken get() = session?.sessionToken

    fun play() {
        val p = player ?: return
        runCatching { p.start() }
        _playing.value = true
        publishState()
    }

    fun pause() {
        val p = player ?: return
        runCatching { p.pause() }
        _playing.value = false
        appContext?.setAudioPosition(bookId, _position.value.toLong())
        publishState()
    }

    fun toggle() = if (_playing.value) pause() else play()

    fun seek(ms: Int) {
        val target = ms.coerceIn(0, _duration.value.coerceAtLeast(0))
        runCatching { player?.seekTo(target) }
        _position.value = target
        publishState()
    }

    fun skip(deltaMs: Int) = seek(_position.value + deltaMs)

    fun setSpeed(value: Float) {
        _speed.value = value
        appContext?.setAudioSpeed(value)
        runCatching { player?.playbackParams }.getOrNull()?.let { pp ->
            runCatching { player?.playbackParams = pp.setSpeed(value) }
        }
        publishState()
    }

    /** Sleep timer; Long.MAX_VALUE means pause at end of book. */
    fun setSleepUntil(time: Long) {
        _sleepUntil.value = time
        sleepJob?.cancel()
        if (time == 0L) return
        sleepJob = scope.launch {
            while (time != Long.MAX_VALUE) {
                val left = time - System.currentTimeMillis()
                if (left <= 0) {
                    pause()
                    _sleepUntil.value = 0
                    break
                }
                delay(1000)
            }
        }
    }

    private fun startSaveLoop() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            while (true) {
                delay(5000)
                if (_playing.value) appContext?.setAudioPosition(bookId, _position.value.toLong())
            }
        }
    }

    fun poll() {
        if (_playing.value) {
            runCatching { player?.currentPosition }.getOrNull()?.let { _position.value = it }
        }
    }

    fun publishState() {
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP,
                )
                .setState(if (_playing.value) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, _position.value.toLong(), _speed.value)
                .build(),
        )
        session?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, _duration.value.toLong())
                .build(),
        )
    }

    fun stop() {
        pause()
        _sleepUntil.value = 0
        sleepJob?.cancel()
        releasePlayer()
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        _playing.value = false
        _position.value = 0
        _duration.value = 0
        session?.isActive = false
        session?.release()
        session = null
    }
}
