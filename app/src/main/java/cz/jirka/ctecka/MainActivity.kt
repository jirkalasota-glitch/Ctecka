package cz.jirka.ctecka

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity(), ReaderService.Listener {

    private var svc: ReaderService? = null
    private var pendingUri: Uri? = null

    private lateinit var tvTitle: TextView
    private lateinit var tvText: TextView
    private lateinit var tvNext: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvRate: TextView
    private lateinit var tvLoading: TextView
    private lateinit var progress: ProgressBar
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: Button
    private var dragging = false

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openBook(uri)
    }

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as ReaderService.LocalBinder).service
            svc = s
            s.listener = this@MainActivity
            pendingUri?.let { s.load(it); pendingUri = null }
            onLoading(s.loadingMsg)
            onState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTitle = findViewById(R.id.tvTitle)
        tvText = findViewById(R.id.tvText)
        tvNext = findViewById(R.id.tvNext)
        tvPos = findViewById(R.id.tvPos)
        tvRate = findViewById(R.id.tvRate)
        tvLoading = findViewById(R.id.tvLoading)
        progress = findViewById(R.id.progress)
        seek = findViewById(R.id.seek)
        btnPlay = findViewById(R.id.btnPlay)

        btnPlay.setOnClickListener { svc?.toggle() }
        findViewById<Button>(R.id.btnPrev).apply {
            setOnClickListener { svc?.let { it.seek(it.index - 1) } }
            setOnLongClickListener { svc?.let { it.seek(it.index - 20) }; true }
        }
        findViewById<Button>(R.id.btnNext).apply {
            setOnClickListener { svc?.let { it.seek(it.index + 1) } }
            setOnLongClickListener { svc?.let { it.seek(it.index + 20) }; true }
        }
        findViewById<Button>(R.id.btnSlower).setOnClickListener { svc?.changeRate(-0.1f) }
        findViewById<Button>(R.id.btnFaster).setOnClickListener { svc?.changeRate(+0.1f) }
        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            openDoc.launch(arrayOf("application/pdf", "text/plain", "application/epub+zip", "application/octet-stream"))
        }
        findViewById<Button>(R.id.btnVoice).setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                Toast.makeText(this, "Nastavení → Usnadnění → Převod textu na řeč", Toast.LENGTH_LONG).show()
            }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) showPos(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                svc?.seek(seek.progress)
            }
        })

        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)

        handleIntent(intent)
        bindService(Intent(this, ReaderService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { openBook(it) }
    }

    private fun openBook(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val s = svc
        if (s != null) s.load(uri) else pendingUri = uri
    }

    override fun onState() {
        val s = svc ?: return
        val b = s.book
        tvRate.text = String.format("%.1f×", s.rate)
        btnPlay.text = if (s.playing) "⏸" else "▶"
        if (b == null) {
            tvTitle.text = "Čtečka"
            tvText.text = "Otevři knihu (PDF, EPUB nebo TXT) tlačítkem dole."
            tvNext.text = ""
            tvPos.text = ""
            seek.max = 0
            return
        }
        tvTitle.text = b.title
        tvText.text = b.sentences.getOrNull(s.index) ?: ""
        tvNext.text = b.sentences.getOrNull(s.index + 1) ?: ""
        seek.max = (b.sentences.size - 1).coerceAtLeast(0)
        if (!dragging) {
            seek.progress = s.index
            showPos(s.index)
        }
    }

    private fun showPos(i: Int) {
        val b = svc?.book ?: return
        val n = b.sentences.size
        val pct = if (n > 0) i * 100 / n else 0
        tvPos.text = "věta ${i + 1} z $n · $pct %"
        if (dragging) tvText.text = b.sentences.getOrNull(i) ?: ""
    }

    override fun onLoading(msg: String?) {
        val on = msg != null
        progress.visibility = if (on) View.VISIBLE else View.GONE
        tvLoading.visibility = if (on) View.VISIBLE else View.GONE
        tvLoading.text = msg ?: ""
    }

    override fun onError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        svc?.listener = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}
