package cz.jirka.ctecka

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Cte knihu nahlas i pri zhasnutem displeji (foreground sluzba + wake lock),
 * ovladani z notifikace, zamcene obrazovky a sluchatek.
 */
class ReaderService : Service(), TextToSpeech.OnInitListener {

    interface Listener {
        fun onState()
        fun onLoading(msg: String?)
        fun onError(msg: String)
    }

    inner class LocalBinder : Binder() {
        val service: ReaderService get() = this@ReaderService
    }

    var listener: Listener? = null
    var book: Book? = null
        private set
    var index = 0
        private set
    var playing = false
        private set
    var rate = 1.0f
        private set
    var loadingMsg: String? = null
        private set

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var playWhenReady = false
    private lateinit var session: MediaSessionCompat
    private lateinit var audio: AudioManager
    private var focusReq: AudioFocusRequest? = null
    private var resumeOnFocus = false
    private var wake: PowerManager.WakeLock? = null
    private var noisyRegistered = false

    // fronta: kolik vet uz je predanych hlasu; "generation" zahodi zpravy od zrusenych vet
    private var queuedUpTo = -1
    private var generation = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ctecka", MODE_PRIVATE)
        rate = prefs.getFloat("rate", 1.0f)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()

        session = MediaSessionCompat(this, "Ctecka").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stopAll()
                override fun onSkipToNext() = seek(index + 1)
                override fun onSkipToPrevious() = seek(index - 1)
                override fun onFastForward() = seek(index + 10)
                override fun onRewind() = seek(index - 10)
            })
            isActive = true
        }

        tts = TextToSpeech(this, this)

        // posledni rozectena kniha
        val lastId = prefs.getString("last_id", null)
        val lastTitle = prefs.getString("last_title", "") ?: ""
        if (lastId != null) {
            thread {
                val b = BookLoader.loadCached(this, lastId, lastTitle)
                main.post {
                    if (b != null && book == null) setBook(b)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            listener?.onError("Hlas v telefonu se nepodařilo spustit. Zkontroluj Nastavení → Převod textu na řeč.")
            return
        }
        ttsReady = true
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val r = tts.setLanguage(Locale("cs", "CZ"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            listener?.onError("Chybí český hlas. Otevři „Hlas“ a stáhni češtinu (Google Převod textu na řeč).")
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                val (g, i) = parse(id) ?: return
                main.post {
                    if (g != generation) return@post
                    index = i
                    savePos()
                    fill()
                    publish()
                }
            }

            override fun onDone(id: String) {
                val (g, i) = parse(id) ?: return
                main.post {
                    if (g != generation) return@post
                    val b = book ?: return@post
                    if (i >= b.sentences.size - 1) {
                        index = b.sentences.size - 1
                        pause()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = onDone(id)
            override fun onError(id: String, errorCode: Int) = onDone(id)
        })
        if (playWhenReady) {
            playWhenReady = false
            play()
        }
    }

    private fun parse(id: String): Pair<Int, Int>? {
        val p = id.split(':')
        if (p.size != 2) return null
        return Pair(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null)
    }

    // ---------- nacteni knihy ----------
    fun load(uri: Uri) {
        pause()
        loadingMsg = "Načítám knihu…"
        listener?.onLoading(loadingMsg)
        thread {
            try {
                val b = BookLoader.load(this, uri) { msg ->
                    main.post { loadingMsg = msg; listener?.onLoading(msg) }
                }
                main.post {
                    loadingMsg = null
                    listener?.onLoading(null)
                    setBook(b)
                    prefs.edit().putString("last_id", b.id).putString("last_title", b.title).apply()
                }
            } catch (e: Throwable) {
                main.post {
                    loadingMsg = null
                    listener?.onLoading(null)
                    listener?.onError("Knihu se nepodařilo otevřít: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun setBook(b: Book) {
        book = b
        index = prefs.getInt("pos_" + b.id, 0).coerceIn(0, b.sentences.size - 1)
        publish()
    }

    // ---------- ovladani ----------
    fun toggle() = if (playing) pause() else play()

    fun play() {
        val b = book ?: return
        if (!ttsReady) {
            playWhenReady = true; return
        }
        if (index >= b.sentences.size - 1 && !playing && prefs.getInt("pos_" + b.id, 0) >= b.sentences.size - 1) index = 0
        if (!requestFocus()) return
        playing = true
        try {
            ContextCompat.startForegroundService(this, Intent(this, ReaderService::class.java))
        } catch (_: Exception) {
        }
        goForeground()
        acquireWake()
        registerNoisy()
        speakFrom(index)
        publish()
    }

    fun pause() {
        if (!playing) {
            publish(); return
        }
        playing = false
        generation++
        tts.stop()
        savePos()
        releaseWake()
        unregisterNoisy()
        abandonFocus()
        publish()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    fun seek(i: Int) {
        val b = book ?: return
        index = i.coerceIn(0, b.sentences.size - 1)
        savePos()
        if (playing) speakFrom(index)
        publish()
    }

    fun changeRate(delta: Float) {
        rate = (Math.round((rate + delta) * 10f) / 10f).coerceIn(0.5f, 3.0f)
        prefs.edit().putFloat("rate", rate).apply()
        if (playing) speakFrom(index)
        publish()
    }

    private fun speakFrom(i: Int) {
        generation++
        tts.stop()
        tts.setSpeechRate(rate)
        queuedUpTo = i - 1
        fill()
    }

    /** drzi ve fronte hlasu par vet dopredu, at mezi vetami nejsou mezery */
    private fun fill() {
        val b = book ?: return
        if (!playing) return
        while (queuedUpTo < index + 3 && queuedUpTo < b.sentences.size - 1) {
            val q = queuedUpTo + 1
            val mode = if (q == index && queuedUpTo == index - 1) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(b.sentences[q], mode, null, "$generation:$q")
            queuedUpTo = q
        }
    }

    private fun savePos() {
        val b = book ?: return
        prefs.edit().putInt("pos_" + b.id, index).apply()
    }

    // ---------- audio focus, sluchatka, wake lock ----------
    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (playing) { resumeOnFocus = true; pause() }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) { resumeOnFocus = false; play() } else Unit
                    else -> Unit
                }
            }
            .build()
        focusReq = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!resumeOnFocus) focusReq?.let { audio.abandonAudioFocusRequest(it) }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = pause() // vytazena sluchatka
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { unregisterReceiver(noisy) } catch (_: Exception) {}
        noisyRegistered = false
    }

    private fun acquireWake() {
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ctecka:cteni").apply { setReferenceCounted(false) }
        }
        wake?.acquire(6 * 60 * 60 * 1000L)
    }

    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
    }

    // ---------- notifikace + zamcena obrazovka ----------
    private fun createChannel() {
        val ch = NotificationChannel("cteni", "Čtení", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val b = book
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun act(icon: Int, title: String, action: Long) = NotificationCompat.Action(
            icon, title, MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)
        )
        val pct = if (b != null && b.sentences.isNotEmpty()) (index * 100 / b.sentences.size) else 0
        return NotificationCompat.Builder(this, "cteni")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(b?.title ?: "Čtečka")
            .setContentText(if (b != null) "$pct % · ${b.sentences.getOrNull(index) ?: ""}" else "")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .addAction(act(android.R.drawable.ic_media_previous, "Zpět", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (playing) act(android.R.drawable.ic_media_pause, "Pauza", PlaybackStateCompat.ACTION_PAUSE)
                else act(android.R.drawable.ic_media_play, "Číst", PlaybackStateCompat.ACTION_PLAY)
            )
            .addAction(act(android.R.drawable.ic_media_next, "Dál", PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun goForeground() {
        try {
            ServiceCompat.startForeground(
                this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (_: Exception) {
        }
    }

    /** zavreni notifikace = konec cteni */
    fun stopAll() {
        pause()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(1)
    }

    private fun publish() {
        val b = book
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    index.toLong(), 1f
                ).build()
        )
        if (b != null) {
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, b.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Čtečka")
                    .build()
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (playing || nm.activeNotifications.any { it.id == 1 }) {
                try { nm.notify(1, buildNotification()) } catch (_: SecurityException) {}
            }
        }
        listener?.onState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // tlacitka ze sluchatek / zamcene obrazovky muzou sluzbu nastartovat na pozadi
        goForeground()
        MediaButtonReceiver.handleIntent(session, intent)
        if (!playing) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playing) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePos()
        playing = false
        generation++
        unregisterNoisy()
        releaseWake()
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
        session.release()
        super.onDestroy()
    }
}
