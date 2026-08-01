package aniyomi.lib.meganzextractor

import keiyoushi.utils.ShindenLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class MegaNzProxyServer(port: Int, private val client: OkHttpClient) : NanoHTTPD(port) {

    data class StreamInfo(
        val dlUrl: String,
        val size: Long,
        val aesKey: ByteArray,
        val nonce: ByteArray,
        val mimeType: String,
    )

    private val streams = ConcurrentHashMap<String, StreamInfo>()
    private val executor = Executors.newCachedThreadPool()
    private val tag = "MegaNzProxyServer"

    val port: Int get() = super.getListeningPort()

    fun register(info: StreamInfo): String {
        val id = UUID.randomUUID().toString()
        streams[id] = info
        return id
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split("/")
        if (parts.size < 2 || parts[0] != "stream") {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
        val info = streams[parts[1]]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "unknown stream")

        var start = 0L
        var end = info.size - 1
        var isPartial = false
        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            isPartial = true
            val range = rangeHeader.removePrefix("bytes=").split("-")
            start = range.getOrNull(0)?.toLongOrNull() ?: 0L
            if (range.size > 1 && range[1].isNotBlank()) {
                range[1].toLongOrNull()?.let { end = it }
            }
        }
        if (end >= info.size) end = info.size - 1
        if (start > end) start = end
        val contentLength = end - start + 1

        val alignedStart = (start / 16) * 16
        val skip = (start - alignedStart).toInt()
        val blockOffset = alignedStart / 16

        return try {
            val upstreamReq = Request.Builder()
                .url(info.dlUrl)
                .header("Range", "bytes=$alignedStart-$end")
                .build()
            val upstreamResp = client.newCall(upstreamReq).execute()
            val upstreamBody = upstreamResp.body
                ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "upstream empty body")

            val counterBlock = ByteArray(16)
            System.arraycopy(info.nonce, 0, counterBlock, 0, 8)
            for (i in 0 until 8) {
                counterBlock[15 - i] = ((blockOffset shr (8 * i)) and 0xFF).toByte()
            }

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(info.aesKey, "AES"), IvParameterSpec(counterBlock))

            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut, 128 * 1024)

            executor.execute {
                try {
                    upstreamBody.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var toSkip = skip
                        var remaining = contentLength
                        while (remaining > 0) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            val decrypted = cipher.update(buffer, 0, read) ?: continue
                            var offset = 0
                            var length = decrypted.size
                            if (toSkip > 0) {
                                val skipNow = minOf(toSkip, length)
                                offset += skipNow
                                length -= skipNow
                                toSkip -= skipNow
                            }
                            if (length > remaining) length = remaining.toInt()
                            if (length > 0) {
                                pipedOut.write(decrypted, offset, length)
                                remaining -= length
                            }
                        }
                    }
                } catch (e: Exception) {
                    ShindenLog.e(tag, "Error streaming mega content: ${e.message}")
                } finally {
                    try {
                        pipedOut.close()
                    } catch (_: Exception) {}
                }
            }

            val status = if (isPartial) Status.PARTIAL_CONTENT else Status.OK
            val response = newFixedLengthResponse(status, info.mimeType, pipedIn, contentLength)
            response.addHeader("Accept-Ranges", "bytes")
            if (isPartial) response.addHeader("Content-Range", "bytes $start-$end/${info.size}")
            response
        } catch (e: Exception) {
            ShindenLog.e(tag, "Error setting up mega stream: ${e.message}")
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${e.message}")
        }
    }
}
