package com.example.gofilezip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Uzun süren toplu indirmeyi ön plan servisi olarak yürütür; böylece uygulama
 * arka plana alınsa bile indirme sürer. Dosyalar sırayla indirilip
 * Downloads/Gofile/<klasör adı>.zip içine yazılır.
 */
class DownloadService : Service() {

    private class Cancelled : RuntimeException()

    private var lastReport = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested = true
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val links = intent?.getStringArrayListExtra(EXTRA_LINKS).orEmpty()
        val password = intent?.getStringExtra(EXTRA_PASSWORD)

        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotification("Hazırlanıyor…", 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        running = true
        cancelRequested = false

        Thread {
            val summary = try {
                runAll(links, password)
            } catch (e: Cancelled) {
                "İptal edildi"
            } catch (e: Exception) {
                "Hata: ${e.message}"
            }
            running = false
            report(summary, 0, 0, finished = true)
            finalNotification(summary)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    private fun runAll(links: List<String>, password: String?): String {
        val results = mutableListOf<String>()
        for ((i, link) in links.withIndex()) {
            checkCancel()
            val tag = if (links.size > 1) "[${i + 1}/${links.size}] " else ""
            try {
                report("${tag}Giriş yapılıyor…", 0, 0)
                val client = GofileClient(password)
                client.login()
                report("${tag}Dosyalar listeleniyor…", 0, 0)
                val listing = client.list(GofileClient.extractId(link))
                if (listing.files.isEmpty()) {
                    results += "${listing.name}: içinde dosya yok"
                } else {
                    results += downloadListing(client, listing, tag)
                }
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                results += "$link → ${e.message}"
            }
        }
        return results.joinToString("\n")
    }

    private fun downloadListing(client: GofileClient, listing: Listing, tag: String): String {
        val total = listing.files.sumOf { it.size }
        var done = 0L
        var failed = 0
        val used = HashSet<String>()
        val zipName = safeName(listing.name) + ".zip"
        val (rawOut, uri) = createOutput(zipName)
        val tmp = File(cacheDir, "download.tmp")
        var ok = false

        try {
            ZipOutputStream(BufferedOutputStream(rawOut, 64 * 1024)).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION) // çoğu dosya zaten sıkışık; hız için
                for ((idx, f) in listing.files.withIndex()) {
                    checkCancel()
                    val label = "$tag${idx + 1}/${listing.files.size}: ${f.path.substringAfterLast('/')}"
                    try {
                        fetchToTemp(client, f, tmp) { delta ->
                            done += delta
                            report(label, done, total)
                        }
                    } catch (e: Cancelled) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        continue
                    }
                    zip.putNextEntry(ZipEntry(uniqueName(f.path, used)))
                    tmp.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    tmp.delete()
                }
            }
            ok = used.isNotEmpty()
        } finally {
            tmp.delete()
            if (ok) publish(uri) else contentResolver.delete(uri, null, null)
        }

        val count = listing.files.size
        return when {
            !ok -> "${listing.name}: hiçbir dosya indirilemedi"
            failed == 0 -> "${listing.name}: $count dosya → Downloads/Gofile/$zipName"
            else -> "${listing.name}: ${count - failed}/$count dosya → Downloads/Gofile/$zipName ($failed dosya indirilemedi)"
        }
    }

    /** Dosyayı geçici dosyaya indirir; ağ hatasında 3 kereye kadar tekrar dener. */
    private fun fetchToTemp(client: GofileClient, file: RemoteFile, tmp: File, onBytes: (Long) -> Unit) {
        var attempt = 0
        while (true) {
            attempt++
            var counter = 0L
            try {
                val conn = client.openFile(file.link)
                try {
                    val expected = conn.contentLengthLong
                    val buf = ByteArray(128 * 1024)
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { out ->
                            while (true) {
                                checkCancel()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                counter += n
                                onBytes(n.toLong())
                            }
                        }
                    }
                    if (expected > 0 && counter != expected) throw IOException("Eksik veri alındı")
                } finally {
                    conn.disconnect()
                }
                return
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                onBytes(-counter)
                if (attempt >= 3) throw e
                Thread.sleep(1500L * attempt)
            }
        }
    }

    private fun createOutput(zipName: String): Pair<OutputStream, Uri> {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, zipName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Gofile")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("İndirilenler klasöründe dosya oluşturulamadı")
        val out = contentResolver.openOutputStream(uri)
            ?: throw IOException("ZIP dosyası açılamadı")
        return out to uri
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        contentResolver.update(uri, values, null, null)
    }

    private fun uniqueName(path: String, used: MutableSet<String>): String {
        var name = path
        var n = 1
        while (!used.add(name.lowercase())) {
            n++
            val dot = path.lastIndexOf('.')
            name = if (dot > path.lastIndexOf('/')) {
                "${path.substring(0, dot)} ($n)${path.substring(dot)}"
            } else {
                "$path ($n)"
            }
        }
        return name
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "gofile" }

    private fun checkCancel() {
        if (cancelRequested) throw Cancelled()
    }

    // ---- İlerleme bildirimi ----

    private fun report(text: String, done: Long, total: Long, finished: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!finished && now - lastReport < 400) return
        lastReport = now
        sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_FINISHED, finished)
        )
        if (!finished) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text, done, total))
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "İndirmeler", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(text: String, done: Long, total: Long): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Gofile ZIP indiriliyor")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "İptal", cancel).build())
        if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun finalNotification(summary: String) {
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Gofile ZIP")
            .setContentText(summary.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

    companion object {
        const val ACTION_PROGRESS = "com.example.gofilezip.PROGRESS"
        const val ACTION_CANCEL = "com.example.gofilezip.CANCEL"
        const val EXTRA_LINKS = "links"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DONE = "done"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_FINISHED = "finished"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1

        @Volatile var running = false
        @Volatile var cancelRequested = false
    }
}
