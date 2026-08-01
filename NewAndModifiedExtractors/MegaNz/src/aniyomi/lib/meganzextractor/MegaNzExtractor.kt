package aniyomi.lib.meganzextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.ShindenLog
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaNzExtractor(private val client: OkHttpClient) {

    companion object {
        @Volatile private var server: MegaNzProxyServer? = null

        private fun ensureServer(client: OkHttpClient): MegaNzProxyServer {
            server?.let { return it }
            synchronized(this) {
                server?.let { return it }
                val s = MegaNzProxyServer(0, client)
                s.start()
                server = s
                return s
            }
        }
    }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> = try {
        videosFromUrlOrThrow(url, prefix)
    } catch (e: Exception) {
        ShindenLog.e("MegaNzExtractor", "Failed to resolve mega link: ${e.message}", e)
        emptyList()
    }

    private fun videosFromUrlOrThrow(url: String, prefix: String): List<Video> {
        val (id, key43) = parseMegaUrl(url) ?: return emptyList()
        val (aesKey, nonce) = deriveKeyAndNonce(key43) ?: return emptyList()

        val requestJson = JSONArray().put(
            JSONObject().put("a", "g").put("g", 1).put("p", id),
        )
        val body = requestJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val resp = client.newCall(
            Request.Builder().url("https://g.api.mega.co.nz/cs").post(body).build(),
        ).execute()
        val bodyStr = resp.body?.string().orEmpty()

        val root = try {
            JSONArray(bodyStr)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        if (root.length() == 0) return emptyList()

        val entry = root.opt(0) as? JSONObject ?: return emptyList()

        val dlUrl = entry.optString("g", "")
        val size = entry.optLong("s", -1)
        if (dlUrl.isBlank() || size < 0) return emptyList()

        val name = try {
            val atRaw = base64UrlDecode(entry.optString("at", ""))
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
            val plain = cipher.doFinal(atRaw)
            val text = plain.toString(Charsets.UTF_8).trimEnd('\u0000')
            JSONObject(text.removePrefix("MEGA")).optString("n", "mega_video")
        } catch (_: Exception) {
            "mega_video"
        }

        val mimeType = when {
            name.endsWith(".mkv", true) -> "video/x-matroska"
            name.endsWith(".avi", true) -> "video/x-msvideo"
            else -> "video/mp4"
        }

        val proxy = ensureServer(client)
        val streamId = proxy.register(
            MegaNzProxyServer.StreamInfo(dlUrl = dlUrl, size = size, aesKey = aesKey, nonce = nonce, mimeType = mimeType),
        )
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val localUrl = "http://127.0.0.1:${proxy.port}/stream/$streamId/$encodedName"

        return listOf(Video(localUrl, "${prefix}Mega - $name", localUrl))
    }

    private fun parseMegaUrl(url: String): Pair<String, String>? {
        Regex("""mega(?:\.co)?\.nz/file/([^#?]+)#([^/?]+)""").find(url)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        Regex("""mega(?:\.co)?\.nz/embed/#!([^!]+)!([^/?]+)""").find(url)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        Regex("""mega(?:\.co)?\.nz/#!([^!]+)!([^/?]+)""").find(url)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        return null
    }

    private fun base64UrlDecode(s: String): ByteArray {
        var padded = s.replace('-', '+').replace('_', '/')
        while (padded.length % 4 != 0) padded += "="
        return Base64.decode(padded, Base64.NO_WRAP)
    }

    private fun deriveKeyAndNonce(key43: String): Pair<ByteArray, ByteArray>? {
        val decoded = base64UrlDecode(key43)
        if (decoded.size < 32) return null
        val aesKey = ByteArray(16)
        for (i in 0 until 8) {
            aesKey[i] = (decoded[i].toInt() xor decoded[i + 16].toInt()).toByte()
            aesKey[i + 8] = (decoded[i + 8].toInt() xor decoded[i + 24].toInt()).toByte()
        }
        val nonce = decoded.copyOfRange(16, 24)
        return aesKey to nonce
    }
}
