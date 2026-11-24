package com.example.gofilezip

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var linksInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var preview: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnList: Button
    private lateinit var btnDownload: Button
    private lateinit var btnCancel: Button

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(DownloadService.EXTRA_TEXT).orEmpty()
            val done = intent.getLongExtra(DownloadService.EXTRA_DONE, 0)
            val total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, 0)
            val finished = intent.getBooleanExtra(DownloadService.EXTRA_FINISHED, false)

            status.text = if (total > 0 && !finished) "$text\n${formatSize(done)} / ${formatSize(total)}" else text
            if (total > 0) {
                progress.isIndeterminate = false
                progress.progress = (done * 1000 / total).toInt().coerceIn(0, 1000)
            } else {
                progress.isIndeterminate = true
            }
            setBusy(!finished)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        linksInput = findViewById(R.id.links)
        passwordInput = findViewById(R.id.password)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnList = findViewById(R.id.btnList)
        btnDownload = findViewById(R.id.btnDownload)
        btnCancel = findViewById(R.id.btnCancel)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        btnList.setOnClickListener { listFiles() }
        btnDownload.setOnClickListener { startDownload() }
        btnCancel.setOnClickListener { DownloadService.cancelRequested = true }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(DownloadService.ACTION_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setBusy(DownloadService.running)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
    }

    private fun handleIntent(intent: Intent?) {
        val incoming = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val current = linksInput.text.toString().trim()
        linksInput.setText(if (current.isEmpty()) incoming else "$current\n$incoming")
    }

    private fun readLinks(): List<String> =
        linksInput.text.toString().split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun setBusy(busy: Boolean) {
        btnList.isEnabled = !busy
        btnDownload.isEnabled = !busy
        btnCancel.visibility = if (busy) View.VISIBLE else View.GONE
        progress.visibility = if (busy || progress.progress > 0) View.VISIBLE else View.GONE
    }

    private fun listFiles() {
        val links = readLinks()
        if (links.isEmpty()) {
            Toast.makeText(this, "Önce bir Gofile linki girin", Toast.LENGTH_SHORT).show()
            return
        }
        val pw = passwordInput.text.toString()
        preview.text = "Listeleniyor…"
        io.execute {
            val sb = StringBuilder()
            for (link in links) {
                try {
                    val client = GofileClient(pw)
                    client.login()
                    val listing = client.list(GofileClient.extractId(link))
                    val total = listing.files.sumOf { it.size }
                    sb.append("• ${listing.name}: ${listing.files.size} dosya, ${formatSize(total)}\n")
                } catch (e: Exception) {
                    sb.append("• $link → ${e.message}\n")
                }
            }
            main.post { preview.text = sb.toString().trim() }
        }
    }

    private fun startDownload() {
        val links = readLinks()
        if (links.isEmpty()) {
            Toast.makeText(this, "Önce bir Gofile linki girin", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, DownloadService::class.java)
            .putStringArrayListExtra(DownloadService.EXTRA_LINKS, ArrayList(links))
            .putExtra(DownloadService.EXTRA_PASSWORD, passwordInput.text.toString())
        ContextCompat.startForegroundService(this, intent)
        progress.progress = 0
        progress.isIndeterminate = true
        status.text = "Başlatılıyor…"
        setBusy(true)
    }
}
