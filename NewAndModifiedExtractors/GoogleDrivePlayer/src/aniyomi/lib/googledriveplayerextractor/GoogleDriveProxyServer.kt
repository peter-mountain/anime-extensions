package aniyomi.lib.googledriveplayerextractor

import keiyoushi.utils.ExtLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class GoogleDriveProxyServer(
    port: Int,
    private val client: OkHttpClient,
) : NanoHTTPD(port) {
    data class StreamInfo(
        val videoUrl: String,
        val cookies: String,
        val referer: String,
        val userAgent: String,
    )

    private val streams = ConcurrentHashMap<String, StreamInfo>()
    private val tag = "GDriveProxy"

    val port: Int get() = super.getListeningPort()

    fun register(info: StreamInfo): String {
        val id = UUID.randomUUID().toString()
        streams[id] = info
        ExtLog.d(tag, "Registered stream $id -> ${info.videoUrl.take(80)}")
        return id
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split("/")
        if (parts.size < 2 || parts[0] != "stream") {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
        val info =
            streams[parts[1]]
                ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "unknown stream: ${parts[1]}")

        ExtLog.d(tag, "Proxy request for ${parts[1]} range=${session.headers["range"]}")

        val rangeHeader = session.headers["range"]

        return try {
            val reqBuilder =
                Request
                    .Builder()
                    .url(info.videoUrl)
                    .header("Referer", info.referer)
                    .header("Origin", "https://drive.google.com")
                    .header("User-Agent", info.userAgent)
                    .header("Accept", "*/*")

            if (info.cookies.isNotEmpty()) {
                reqBuilder.header("Cookie", info.cookies)
            }
            if (rangeHeader != null) {
                reqBuilder.header("Range", rangeHeader)
            }

            val upstreamResp = client.newCall(reqBuilder.build()).execute()
            val upstreamBody =
                upstreamResp.body
                    ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "upstream empty body")

            val contentType = upstreamResp.header("Content-Type") ?: "video/mp4"
            val contentLength = upstreamResp.header("Content-Length")?.toLongOrNull() ?: -1
            val upstreamCode = upstreamResp.code

            ExtLog.d(tag, "Upstream response: $upstreamCode content-type=$contentType len=$contentLength")

            if (upstreamCode == 403) {
                return newFixedLengthResponse(
                    Status.FORBIDDEN,
                    MIME_PLAINTEXT,
                    "upstream 403: ${upstreamBody.string().take(200)}",
                )
            }

            val isPartial = upstreamCode == 206
            val response =
                newFixedLengthResponse(
                    if (isPartial) Status.PARTIAL_CONTENT else Status.OK,
                    contentType,
                    upstreamBody.byteStream(),
                    contentLength,
                )

            // Forward relevant headers
            upstreamResp.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Access-Control-Allow-Origin", "*")

            response
        } catch (e: Exception) {
            ExtLog.e(tag, "Proxy error: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${e.message}")
        }
    }
}
