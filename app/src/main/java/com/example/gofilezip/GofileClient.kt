package com.example.gofilezip

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class RemoteFile(val path: String, val link: String, val size: Long)

data class Listing(val name: String, val files: List<RemoteFile>)

class GofileException(message: String) : Exception(message)

/**
 * Gofile ile konuşan küçük istemci.
 *
 * Akış: misafir hesap aç -> içerik listesini al (alt klasörlerle birlikte)
 * -> her dosyayı accountToken çerezi ile indir.
 *
 * NOT: X-Website-Token değeri Gofile'ın resmi dokümanında yok; sitenin kendi
 * web arayüzünden tersine mühendislikle bulunan bir formül. Gofile bunu
 * değiştirirse sadece [websiteToken] fonksiyonunu güncellemek yeterli.
 */
class GofileClient(private val password: String? = null) {

    private var token: String = ""

    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 40_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en-US")
        }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (body.isBlank()) throw GofileException("Boş yanıt (HTTP $code)")
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw GofileException("Geçersiz yanıt (HTTP $code)")
        }
        val status = json.optString("status")
        if (status != "ok") throw GofileException(describeError(status))
        return json
    }

    private fun describeError(status: String): String = when {
        status.contains("notFound") -> "İçerik bulunamadı (link silinmiş olabilir)"
        status.contains("passwordRequired") -> "Bu klasör şifreli, şifre girin"
        status.contains("passwordWrong") -> "Şifre yanlış"
        status.contains("notPublic") -> "İçerik herkese açık değil"
        status.contains("rateLimit") -> "Çok fazla istek, biraz bekleyip tekrar deneyin"
        else -> "Gofile hatası: $status"
    }

    /** Misafir hesap oluşturur ve token'ı saklar. */
    fun login() {
        val conn = open("https://api.gofile.io/accounts", "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write("{}".toByteArray()) }
        val data = readJson(conn).getJSONObject("data")
        token = data.getString("token")
    }

    private fun websiteToken(): String {
        val window = System.currentTimeMillis() / 1000 / 14_400 // 4 saatlik pencere
        val raw = "$USER_AGENT::en-US::$token::$window::5d4f7g8sd45fsd"
        return sha256(raw)
    }

    /** Linkteki klasörü (alt klasörler dahil) düz bir dosya listesine çevirir. */
    fun list(contentId: String): Listing {
        val files = mutableListOf<RemoteFile>()
        val name = walk(contentId, "", files, 0)
        return Listing(name, files)
    }

    private fun walk(id: String, prefix: String, out: MutableList<RemoteFile>, depth: Int): String {
        if (depth > 20) return id
        val url = StringBuilder("https://api.gofile.io/contents/$id?cache=true&sortField=createTime&sortDirection=1")
        if (!password.isNullOrEmpty()) url.append("&password=").append(sha256(password))

        val conn = open(url.toString())
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-Website-Token", websiteToken())
        conn.setRequestProperty("Cookie", "accountToken=$token")
        val data = readJson(conn).getJSONObject("data")
        val name = data.optString("name", id)

        // Link tek bir dosyaya aitse
        if (data.optString("type") == "file") {
            out += RemoteFile(prefix + cleanName(name), data.getString("link"), data.optLong("size"))
            return name
        }

        val children = data.optJSONObject("children") ?: data.optJSONObject("contents") ?: return name
        val keys = children.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = children.getJSONObject(key)
            val childName = cleanName(child.optString("name", key))
            if (child.optString("type") == "folder") {
                walk(child.optString("id", key), "$prefix$childName/", out, depth + 1)
            } else {
                val link = child.optString("link")
                if (link.isNotEmpty()) {
                    out += RemoteFile(prefix + childName, link, child.optLong("size"))
                }
            }
        }
        return name
    }

    /** Tek bir dosyayı indirmek için bağlantıyı açar. */
    fun openFile(link: String): HttpURLConnection {
        val conn = open(link)
        conn.setRequestProperty("Cookie", "accountToken=$token")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Referer", "https://gofile.io/")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw GofileException("İndirme hatası (HTTP $code)")
        }
        if (conn.contentType?.startsWith("text/html") == true) {
            conn.disconnect()
            throw GofileException("Gofile dosya yerine sayfa döndürdü (oturum/token sorunu)")
        }
        return conn
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        fun extractId(input: String): String {
            val s = input.trim()
            val tail = if (s.contains("/d/")) s.substringAfter("/d/") else s
            return tail.substringBefore('?').substringBefore('#').trimEnd('/').substringBefore('/')
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun cleanName(name: String): String =
            name.replace(Regex("[\\\\:*?\"<>|]"), "_").replace("/", "_").trim().ifEmpty { "dosya" }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return "%.1f %s".format(v, units[i])
}
