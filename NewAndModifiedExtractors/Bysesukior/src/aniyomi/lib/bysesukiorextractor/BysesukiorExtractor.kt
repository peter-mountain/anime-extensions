package aniyomi.lib.bysesukiorextractor

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.ExtLog
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BysesukiorExtractor(private val client: OkHttpClient, private val dns: Dns? = null) {
    private val plainClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
        if (dns != null) builder.dns(dns)
        builder.build()
    }
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val random = SecureRandom()
    private val tag = "BysesukiorExtractor"
    private val androidUA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.134 Mobile Safari/537.36"
    private val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:152.0) Gecko/20100101 Firefox/152.0"

    fun videosFromUrl(url: String, prefix: String = "", headers: Headers? = null): List<Video> {
        try {
            return videosFromUrlOrThrow(url, prefix, headers)
        } catch (e: Exception) {
            ExtLog.e(tag, "Failed: ${e.message}")
            return listOf(Video("about:blank", "${prefix}Byseukior: failed", "about:blank"))
        }
    }

    private fun videosFromUrlOrThrow(url: String, prefix: String, headers: Headers?): List<Video> {
        val httpUrl = runCatching { url.toHttpUrl() }.getOrNull()
            ?: return listOf(Video("about:blank", "${prefix}Byseukior: invalid_url", "about:blank"))

        val host = httpUrl.host
        val origin = "${httpUrl.scheme}://$host"
        val mediaId = Regex("""/e/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: return listOf(Video("about:blank", "${prefix}Byseukior: no_media_id", "about:blank"))

        logDebug("host=$host mediaId=$mediaId")

        val embedUrl = "https://$host/e/$mediaId"
        val embedSiteHost = headers?.get("Referer")?.let { runCatching { it.toHttpUrl().host }.getOrNull() } ?: host

        val embedHeaders = Headers.Builder()
            .set("Referer", origin)
            .set("Origin", origin)
            .set("User-Agent", androidUA)
            .build()

        logDebug("GET $embedUrl")
        val noRedirectClient = plainClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var currentUrl = embedUrl
        var currentHeaders = embedHeaders
        var embedHtml = ""
        var totalCookies = mutableListOf<String>()
        for (i in 0 until 5) {
            val resp = noRedirectClient.newCall(GET(currentUrl, currentHeaders)).execute()
            logDebug("embed_redirect_$i: code=${resp.code} url=$currentUrl -> ${resp.header("Location")}")
            for (ck in resp.headers("Set-Cookie")) {
                val name = ck.substringBefore("=")
                val value = ck.substringAfter("=").substringBefore(";")
                totalCookies.add("$name=$value")
                logDebug("embed_redirect_cookie: $name=$value (from ${resp.code} response)")
            }
            val loc = resp.header("Location")
            if (resp.code in 300..399 && loc != null) {
                currentUrl = if (loc.startsWith("http")) loc else "https://${embedUrl.toHttpUrl().host}$loc"
                currentHeaders = currentHeaders.newBuilder().set("Referer", currentUrl).build()
                resp.close()
                continue
            }
            embedHtml = resp.body?.string() ?: ""
            resp.close()
            break
        }
        logDebug("embed_final_url=$currentUrl html_len=${embedHtml.length}")
        val cookieStr = totalCookies.joinToString("; ")
        logDebug("embed_cookies=$cookieStr")

        val hlsFromHtml = extractHlsFromHtml(embedHtml)
        if (hlsFromHtml != null) {
            logDebug("found_hls_in_html=$hlsFromHtml")
            return extractHls(hlsFromHtml, origin, prefix, cookieStr)
        }

        val embeddedData = findPlaybackInHtml(embedHtml)
        if (embeddedData != null) {
            val pb = embeddedData.optJSONObject("playback")
            if (pb != null) {
                logDebug("found_playback_in_html=true")
                val hls = decryptSources(pb)
                if (hls != null) return extractHls(hls, origin, prefix, cookieStr)
                logDebug("embedded_playback_decrypt_failed")
            }
        }

        logDebug("trying_old_api")
        val detailsUrl = "https://$host/api/videos/$mediaId/embed/details"
        val detailsResp = plainClient.newCall(GET(detailsUrl, embedHeaders)).execute()
        logDebug("details_http=${detailsResp.code}")
        val detailsBody = detailsResp.body?.string() ?: ""
        logDebug("details_body=${detailsBody.take(500)}")

        var embedFrameUrl = ""
        if (detailsResp.code == 200 && detailsBody.isNotBlank()) {
            embedFrameUrl = detailsBody
                .substringAfter("embed_frame_url")
                .substringAfter(":")
                .substringAfter('"')
                .substringBefore('"')
            logDebug("embed_frame_url=$embedFrameUrl")

            if (embedFrameUrl.isNotBlank()) {
                val embedHost = embedFrameUrl.toHttpUrl().host
                val embedOrigin = "https://$embedHost"

                logDebug("GET embed_frame_url=$embedFrameUrl")
                val frameResp = runCatching {
                    plainClient.newCall(GET(embedFrameUrl, embedHeaders)).execute()
                }.getOrNull()
                if (frameResp != null) {
                    val frameHtml = frameResp.body?.string() ?: ""
                    val frameCookies = frameResp.headers("Set-Cookie").joinToString("; ") { it.split(";")[0] }
                    frameResp.close()
                    logDebug("frame_len=${frameHtml.length} cookies=$frameCookies")
                    val frameData = findPlaybackInHtml(frameHtml)
                    if (frameData != null) {
                        val pb = frameData.optJSONObject("playback")
                        if (pb != null) {
                            logDebug("found_playback_in_frame=true")
                            val hls = decryptSources(pb)
                            if (hls != null) return extractHls(hls, embedOrigin, prefix, cookieStr)
                        }
                        val srcs = frameData.optJSONArray("sources")
                        if (srcs != null && srcs.length() > 0) {
                            val url2 = srcs.getJSONObject(0).optString("url", "")
                            if (url2.isNotBlank()) return extractHls(url2, embedOrigin, prefix, cookieStr)
                        }
                    }
                }

                val pbUrl = "https://$embedHost/api/videos/$mediaId/embed/playback"
                val pbHeaders = Headers.Builder()
                    .set("Referer", embedFrameUrl)
                    .set("X-Embed-Origin", embedSiteHost)
                    .set("X-Embed-Parent", embedUrl)
                    .set("X-Embed-Referer", embedUrl)
                    .set("Accept", "*/*")
                    .set("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Cache-Control", "no-cache")
                    .set("Pragma", "no-cache")
                    .set("Priority", "u=1, i")
                    .set("Sec-Fetch-Dest", "empty")
                    .set("Sec-Fetch-Mode", "cors")
                    .set("Sec-Fetch-Site", "cross-site")
                    .set("User-Agent", androidUA)
                    .build()
                logDebug("POST $pbUrl")
                val pbResp = plainClient.newCall(POST(pbUrl, pbHeaders, """{"code":"$mediaId"}""".toRequestBody("application/json".toMediaType()))).execute()
                logDebug("pb_http=${pbResp.code}")
                val pbBody = pbResp.body?.string() ?: ""
                logDebug("pb_body=${pbBody.take(1000)}")

                if (pbResp.code == 200 && pbBody.isNotBlank()) {
                    try {
                        val json = JSONObject(pbBody)
                        if (json.has("playback")) {
                            logDebug("has_playback=true")
                            val hls = decryptSources(json.getJSONObject("playback"))
                            if (hls != null) return extractHls(hls, embedOrigin, prefix, cookieStr)
                        }
                        if (json.has("sources")) {
                            logDebug("has_sources=true")
                            val srcs = json.getJSONArray("sources")
                            if (srcs.length() > 0) {
                                val u = srcs.getJSONObject(0).optString("url", "")
                                if (u.isNotBlank()) return extractHls(u, embedOrigin, prefix, cookieStr)
                            }
                        }
                    } catch (e: Exception) {
                        logDebug("old_parse_err:${e.message}")
                    }
                }
            }
        }

        val apiHost = when (host) {
            "byssesukior.com", "bysesukior.com" -> "q8y5z.com"
            else -> host
        }
        val apiOrigin = "https://$apiHost"

        // Fallback: construct frame URL from known pattern when details=403
        if (embedFrameUrl.isBlank()) {
            embedFrameUrl = "$apiOrigin/6fs/$mediaId"
            logDebug("embed_frame_url_fallback=$embedFrameUrl")
        }

        var viewerId = ""
        var deviceId = ""
        val sessionCookies = totalCookies
        for (ck in sessionCookies) {
            val name = ck.substringBefore("=")
            val value = ck.substringAfter("=").substringBefore(";")
            when (name) {
                "byse_viewer_id" -> viewerId = value
                "byse_device_id" -> deviceId = value
            }
        }
        logDebug("session_cookies=$cookieStr viewer_id=$viewerId device_id=$deviceId")
        if (viewerId.isBlank()) viewerId = generateId()
        if (deviceId.isBlank()) deviceId = generateId()

        val challengeUrl = "$apiOrigin/api/videos/access/challenge"
        val chHeaders = Headers.Builder()
            .set("Referer", embedUrl)
            .set("Origin", apiOrigin)
            .set("Content-Type", "application/json")
            .set("User-Agent", androidUA)
            .apply { if (cookieStr.isNotBlank()) set("Cookie", cookieStr) }
            .build()
        logDebug("POST $challengeUrl")
        val challengeResp = plainClient.newCall(POST(challengeUrl, chHeaders, "{}".toRequestBody("application/json".toMediaType()))).execute()
        logDebug("challenge_http=${challengeResp.code}")
        for ((k, v) in challengeResp.headers) {
            logDebug("challenge_hdr: $k=$v")
        }
        val challengeBody = challengeResp.body?.string() ?: ""
        logDebug("challenge_body=$challengeBody")

        val challengeJson = try {
            JSONObject(challengeBody)
        } catch (e: Exception) {
            null
        }
        if (challengeJson == null) {
            logDebug("challenge_parse_err")
            return listOf(Video("about:blank", "${prefix}Byseukior: no_challenge", "about:blank"))
        }
        val challengeId = challengeJson.optString("challenge_id", "")
        val nonce = challengeJson.optString("nonce", "")
        if (challengeId.isBlank() || nonce.isBlank()) {
            logDebug("no_challenge_id_or_nonce")
            return listOf(Video("about:blank", "${prefix}Byseukior: no_challenge", "about:blank"))
        }
        val viewerHint = challengeJson.optString("viewer_hint", "")
        if (viewerHint.isNotBlank()) viewerId = viewerHint
        logDebug("challenge_id=$challengeId nonce=$nonce viewer_id=$viewerId")

        val keyPair = generateEcKeyPair()
        val pubKey = keyPair.public as ECPublicKey
        val xB64 = b64urlEncode(to32Bytes(pubKey.w.affineX))
        val yB64 = b64urlEncode(to32Bytes(pubKey.w.affineY))
        val dataToSign = nonce.toByteArray(StandardCharsets.UTF_8)
        val signatureB64 = b64urlEncode(signEcdsa(keyPair.private, dataToSign))
        val publicKeyJwk = JSONObject().apply {
            put("alg", "ES256")
            put("crv", "P-256")
            put("ext", true)
            put("key_ops", JSONArray().put("verify"))
            put("kty", "EC")
            put("x", xB64)
            put("y", yB64)
        }

        val attestBody = buildAttestBody(viewerId, deviceId, challengeId, nonce, publicKeyJwk, signatureB64)
        logDebug("attest_body_len=${attestBody.length}")

        val attestUrl = "$apiOrigin/api/videos/access/attest"
        val attHeaders = Headers.Builder()
            .set("Referer", embedUrl)
            .set("Origin", apiOrigin)
            .set("Content-Type", "application/json")
            .set("User-Agent", androidUA)
            .apply { if (cookieStr.isNotBlank()) set("Cookie", cookieStr) }
            .build()
        logDebug("POST $attestUrl")
        val attestResp = plainClient.newCall(POST(attestUrl, attHeaders, attestBody.toRequestBody("application/json".toMediaType()))).execute()
        logDebug("attest_http=${attestResp.code}")
        for ((k, v) in attestResp.headers) {
            logDebug("attest_hdr: $k=$v")
        }
        val attestBodyStr = attestResp.body?.string() ?: ""
        logDebug("attest_body=$attestBodyStr")
        if (attestResp.code != 200) {
            logDebug("attest_failed")
            return listOf(Video("about:blank", "${prefix}Byseukior: attest_${attestResp.code}", "about:blank"))
        }

        val attestJson = try {
            JSONObject(attestBodyStr)
        } catch (e: Exception) {
            null
        }
        if (attestJson == null) {
            return listOf(Video("about:blank", "${prefix}Byseukior: attest_parse", "about:blank"))
        }
        val token = attestJson.optString("token", "")
        if (token.isBlank()) {
            logDebug("attest_no_token")
            return listOf(Video("about:blank", "${prefix}Byseukior: no_token", "about:blank"))
        }
        logDebug("token=${token.take(50)}...")

        val attestViewerId = attestJson.optString("viewer_id", "")
        val attestDeviceId = attestJson.optString("device_id", "")
        val confidence = attestJson.optDouble("confidence", 0.0)
        if (attestViewerId.isNotBlank()) viewerId = attestViewerId
        if (attestDeviceId.isNotBlank()) deviceId = attestDeviceId
        logDebug("attest_viewer_id=$viewerId device_id=$deviceId confidence=$confidence")

        val attCookies = mutableListOf<String>()
        for (ck in attestResp.headers("Set-Cookie")) {
            val name = ck.substringBefore("=")
            val value = ck.substringAfter("=").substringBefore(";")
            attCookies.add("$name=$value")
            when (name) {
                "byse_viewer_id" -> viewerId = value
                "byse_device_id" -> deviceId = value
            }
        }
        val finalCookies = if (attCookies.isNotEmpty()) listOfNotNull(cookieStr.ifBlank { null }, attCookies.joinToString("; ")).joinToString("; ") else cookieStr

        var captchaToken = ""
        try {
            val captchaApiHeaders = Headers.Builder()
                .set("Referer", embedFrameUrl.ifBlank { embedUrl })
                .set("Origin", apiOrigin)
                .set("Content-Type", "application/json")
                .set("User-Agent", androidUA)
                .set("Authorization", "Bearer $token")
                .apply { if (finalCookies.isNotBlank()) set("Cookie", finalCookies) }
                .build()

            val captchaUrl = "$apiOrigin/api/videos/access/captcha"
            val captchaFp = JSONObject().apply {
                put("token", token)
                put("viewer_id", viewerId)
                put("device_id", deviceId)
                put("confidence", confidence)
            }
            val captchaStartBody = JSONObject().put("fingerprint", captchaFp)
                .toString().toRequestBody("application/json".toMediaType())
            logDebug("POST $captchaUrl")
            val captchaResp = plainClient.newCall(POST(captchaUrl, captchaApiHeaders, captchaStartBody)).execute()
            logDebug("captcha_start_http=${captchaResp.code}")
            for ((k, v) in captchaResp.headers) {
                logDebug("captcha_start_hdr: $k=$v")
            }
            val captchaBody = captchaResp.body?.string() ?: ""
            logDebug("captcha_start_body=$captchaBody")

            if (captchaResp.code == 200 && captchaBody.isNotBlank()) {
                val captchaJson = try {
                    JSONObject(captchaBody)
                } catch (e: Exception) {
                    null
                }
                if (captchaJson != null) {
                    val powNonce = captchaJson.optString("pow_nonce", "")
                    val powDifficulty = captchaJson.optInt("pow_difficulty", 0)
                    val powToken = captchaJson.optString("pow_token", "")
                    logDebug("pow_nonce=$powNonce difficulty=$powDifficulty pow_token=${powToken.take(30)}")

                    if (powNonce.isNotBlank() && powDifficulty > 0 && powToken.isNotBlank()) {
                        logDebug("solving_pow_difficulty=$powDifficulty")
                        val solution = solvePow(powNonce, powDifficulty)
                        logDebug("pow_solution=${solution ?: "null"}")

                        if (solution != null) {
                            val verifyUrl = "$apiOrigin/api/videos/access/captcha/verify"
                            val verifyBody = JSONObject().apply {
                                put("pow_token", powToken)
                                put("solution", solution)
                                put("fingerprint", captchaFp)
                            }.toString().toRequestBody("application/json".toMediaType())
                            logDebug("POST $verifyUrl")
                            val verifyResp = plainClient.newCall(POST(verifyUrl, captchaApiHeaders, verifyBody)).execute()
                            logDebug("captcha_verify_http=${verifyResp.code}")
                            for ((k, v) in verifyResp.headers) {
                                logDebug("captcha_verify_hdr: $k=$v")
                            }
                            val verifyBodyStr = verifyResp.body?.string() ?: ""
                            logDebug("captcha_verify_body=$verifyBodyStr")

                            if (verifyResp.code == 200) {
                                val verifyJson = try {
                                    JSONObject(verifyBodyStr)
                                } catch (e: Exception) {
                                    null
                                }
                                if (verifyJson != null && verifyJson.optString("status", "") == "ok") {
                                    captchaToken = verifyJson.optString("token", "")
                                    logDebug("captcha_token=${captchaToken.take(50)}")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logDebug("captcha_flow_err:${e.message}")
        }

        val pbHeadersBuilder = Headers.Builder()
            .set("Referer", embedFrameUrl)
            .set("Origin", apiOrigin)
            .set("Content-Type", "application/json")
            .set("User-Agent", androidUA)
            .set("X-Embed-Parent", embedUrl)
            .set("Accept", "*/*")
            .set("Accept-Language", "pl,en-US;q=0.9,en;q=0.8")
            .set("Priority", "u=4")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "cross-site")
            .set("Sec-CH-UA", "\"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\", \"Not-A.Brand\";v=\"8\"")
            .set("Sec-CH-UA-Mobile", "?1")
            .set("Sec-CH-UA-Platform", "\"Android\"")
            .apply { if (captchaToken.isNotBlank()) set("X-Captcha-Token", captchaToken.trim()) }
            .apply { if (finalCookies.isNotBlank()) set("Cookie", finalCookies) }
        val pbHeaders = pbHeadersBuilder.build()

        val pbUrl = "$apiOrigin/api/videos/$mediaId/embed/playback"
        val fp = JSONObject()
        fp.put("token", token)
        fp.put("viewer_id", viewerId)
        fp.put("device_id", deviceId)
        fp.put("confidence", confidence)
        val pbBody = JSONObject().put("fingerprint", fp)
            .toString().toRequestBody("application/json".toMediaType())
        logDebug("POST $pbUrl captcha_token_present=${captchaToken.isNotBlank()}")
        val pbResp = plainClient.newCall(POST(pbUrl, pbHeaders, pbBody)).execute()
        logDebug("pb_http=${pbResp.code}")
        for ((k, v) in pbResp.headers) {
            logDebug("pb_hdr: $k=$v")
        }
        val bodyStr = pbResp.body?.string() ?: ""
        logDebug("pb_body=${bodyStr.take(2000)}")
        if (!pbResp.isSuccessful || bodyStr.isBlank()) {
            if (pbResp.code == 428 || pbResp.code == 403) {
                logDebug("pb_428_fallback_to_webview")
                val wvResult = BysesukiorWebViewResolver(client).resolve(embedUrl, androidUA, finalCookies)
                if (wvResult != null) {
                    logDebug("wv_result_len=${wvResult.length}")
                    val wvData = try {
                        JSONObject(wvResult)
                    } catch (e: Exception) {
                        null
                    }
                    if (wvData != null) {
                        val hlsUrl = if (wvData.has("sources")) {
                            val srcs = wvData.getJSONArray("sources")
                            if (srcs.length() > 0) pickBest(srcs) else null
                        } else if (wvData.has("playback")) {
                            decryptSources(wvData.getJSONObject("playback"))
                        } else {
                            null
                        }
                        if (!hlsUrl.isNullOrBlank()) return extractHls(hlsUrl, apiOrigin, prefix, finalCookies)
                    }
                }
                logDebug("wv_fallback_failed")
            }
            return listOf(Video("about:blank", "${prefix}Byseukior: pb_${pbResp.code}", "about:blank"))
        }

        val data = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            return listOf(Video("about:blank", "${prefix}Byseukior: json_err", "about:blank"))
        }

        val hlsUrl = if (data.has("sources")) {
            val srcs = data.getJSONArray("sources")
            if (srcs.length() == 0) return listOf(Video("about:blank", "${prefix}Byseukior: no_src", "about:blank"))
            pickBest(srcs)
        } else if (data.has("playback")) {
            decryptSources(data.getJSONObject("playback"))
        } else {
            null
        }

        if (hlsUrl.isNullOrBlank()) {
            return listOf(Video("about:blank", "${prefix}Byseukior: no_url", "about:blank"))
        }
        return extractHls(hlsUrl, apiOrigin, prefix, finalCookies)
    }

    companion object {
        private const val BE = 512
        private const val LT = BE - 1
        private const val DR = 2
        private val LR = 2654435761L.toInt()
        private val HR = 2246822519L.toInt()
    }

    private fun rotl32(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun chachaQuarterRound(s: IntArray) {
        s[0] = s[0] + s[1]
        s[3] = rotl32(s[3] xor s[0], 16)
        s[2] = s[2] + s[3]
        s[1] = rotl32(s[1] xor s[2], 12)
        s[0] = s[0] + s[1]
        s[3] = rotl32(s[3] xor s[0], 8)
        s[2] = s[2] + s[3]
        s[1] = rotl32(s[1] xor s[2], 7)
    }

    private fun powHash(input: ByteArray): IntArray {
        val e = intArrayOf(1779033703, 3144134277L.toInt(), 1013904242, 2773480762L.toInt())

        for (b in input) {
            e[0] = e[0] + (b.toInt() and 0xFF)
            e[0] = rotl32(e[0], 7)
            chachaQuarterRound(e)
        }
        for (i in 0 until 8) chachaQuarterRound(e)

        val r = IntArray(BE)
        for (i in 0 until BE) {
            chachaQuarterRound(e)
            r[i] = e[0] xor e[2]
        }

        for (i in 0 until DR) {
            for (s in 0 until BE) {
                val a = r[s] and LT
                var c = r[s] + r[a]
                c = rotl32(c, 13)
                c = c xor (r[(s + 1) and LT] * LR)
                r[s] = c
                e[0] = e[0] xor c
                chachaQuarterRound(e)
            }
        }

        val n = IntArray(8)
        val o = BE / 8
        for (i in 0 until 8) {
            chachaQuarterRound(e)
            var s = e[0]
            val base = i * o
            for (ci in 0 until o) {
                val d = r[base + ci]
                s = s + d
                s = rotl32(s, 5)
                s = s xor (d * HR)
            }
            n[i] = s xor e[2]
        }
        return n
    }

    private fun powLeadingZeros(hash: IntArray): Int {
        var count = 0
        for (word in hash) {
            if (word == 0) {
                count += 32
                continue
            }
            return count + Integer.numberOfLeadingZeros(word)
        }
        return count
    }

    private fun solvePow(nonce: String, difficulty: Int, timeoutMs: Long = 30000): String? {
        if (difficulty <= 0) return "0"
        val prefix = "$nonce:"
        val startTime = System.currentTimeMillis()
        var counter = 0L
        val batchSize = 1024
        while (true) {
            for (i in 0 until batchSize) {
                val inputBytes = "$prefix$counter".toByteArray(StandardCharsets.UTF_8)
                val hash = powHash(inputBytes)
                if (powLeadingZeros(hash) >= difficulty) return counter.toString()
                counter++
            }
            if (System.currentTimeMillis() - startTime > timeoutMs) return null
        }
    }

    private fun logDebug(msg: String) {
        ExtLog.d(tag, msg)
    }

    private fun extractHlsFromHtml(html: String): String? {
        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*[:=]\s*(["'])((?:https?:)?//[^"']+\.m3u8[^"']*)\1"""),
            Regex("""source\s*[:=]\s*(["'])((?:https?:)?//[^"']+\.m3u8[^"']*)\1"""),
            Regex("""hls\s*[:=]\s*(["'])((?:https?:)?//[^"']+\.m3u8[^"']*)\1"""),
        )
        for (p in patterns) {
            val m = p.find(html)
            if (m != null) return m.groupValues[2].takeIf { it.isNotBlank() } ?: m.groupValues[1]
        }
        return null
    }

    private fun findPlaybackInHtml(html: String): JSONObject? {
        val startMarker = "\"playback\""
        val idx = html.indexOf(startMarker)
        if (idx < 0) return null

        val searchStart = html.lastIndexOf('{', idx)
        if (searchStart < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        var jsonEnd = -1
        for (i in searchStart until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            when (c) {
                '\\' -> escaped = true

                '"' -> inString = !inString

                '{' -> if (!inString) depth++

                '}' -> if (!inString) {
                    depth--
                    if (depth == 0) {
                        jsonEnd = i
                        break
                    }
                }
            }
        }
        if (jsonEnd < 0) return null
        return try {
            JSONObject(html.substring(searchStart, jsonEnd + 1))
        } catch (e: Exception) {
            null
        }
    }

    private fun extractHls(hlsUrl: String, origin: String, prefix: String, cookies: String = ""): List<Video> {
        val outHeaders = Headers.Builder()
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .set("Accept", "*/*")
            .set("User-Agent", androidUA)
            .apply {
                if (cookies.isNotBlank()) set("Cookie", cookies)
            }
            .build()
        logDebug("hls_returning_direct_url=$hlsUrl")
        return listOf(Video(hlsUrl, "${prefix}Bysesukior", hlsUrl, headers = outHeaders))
    }

    private fun pickBest(sources: JSONArray): String {
        var bestUrl = ""
        var bestQuality = -1
        for (i in 0 until sources.length()) {
            val s = sources.getJSONObject(i)
            val label = s.optInt("label", 0)
            val url = s.optString("url", "")
            if (url.isNotBlank() && label > bestQuality) {
                bestQuality = label
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun decryptSources(pb: JSONObject): String? {
        val iv = b64urlDecode(pb.optString("iv", ""))
        val keyParts = pb.optJSONArray("key_parts")
        val payload = b64urlDecode(pb.optString("payload", ""))
        val version = pb.optString("version", "")
        logDebug("decrypt: iv_len=${iv.size} key_parts=${keyParts?.length()} payload_len=${payload.size} version=$version")

        if (keyParts != null && iv.isNotEmpty() && payload.isNotEmpty()) {
            val key = selectKeyParts(keyParts, version)
            logDebug("key_len=${key.size}")
            if (key.isEmpty()) logDebug("empty_key!")
            val decrypted = aesGcmDecrypt(key, iv, payload)
            if (decrypted != null) {
                logDebug("decrypt_ok")
                val parsed = try {
                    JSONObject(decrypted)
                } catch (e: Exception) {
                    logDebug("parse_fail:${e.message}")
                    return null
                }
                val sources = parsed.optJSONArray("sources")
                if (sources != null && sources.length() > 0) {
                    logDebug("sources_count=${sources.length()}")
                    val best = pickBest(sources)
                    logDebug("best=$best")
                    return best
                }
                logDebug("no_sources_after_decrypt")
            } else {
                logDebug("decrypt_fail")
            }
        }

        val iv2 = b64urlDecode(pb.optString("iv2", ""))
        val pay2 = b64urlDecode(pb.optString("payload2", ""))
        val decryptKeys = pb.optJSONObject("decrypt_keys")
        logDebug("fallback: iv2_len=${iv2.size} pay2_len=${pay2.size} decrypt_keys=${decryptKeys?.length()}")
        if (iv2.isNotEmpty() && pay2.isNotEmpty() && decryptKeys != null) {
            for (keyName in decryptKeys.keys()) {
                try {
                    val key2 = b64urlDecode(decryptKeys.getString(keyName))
                    logDebug("try_key=$keyName key_len=${key2.size}")
                    val decrypted = aesGcmDecrypt(key2, iv2, pay2)
                    if (decrypted != null) {
                        logDebug("decrypt_ok")
                        val parsed = try {
                            JSONObject(decrypted)
                        } catch (e: Exception) {
                            logDebug("parse_fail:${e.message}")
                            continue
                        }
                        val sources = parsed.optJSONArray("sources")
                        if (sources != null && sources.length() > 0) {
                            logDebug("sources_count=${sources.length()}")
                            val best = pickBest(sources)
                            logDebug("best=$best")
                            return best
                        }
                    } else {
                        logDebug("decrypt_fail")
                    }
                } catch (e: Exception) {
                    logDebug("key_error:${e.message}")
                }
            }
        }
        return null
    }

    private fun selectKeyParts(parts: JSONArray, version: String): ByteArray {
        val selected = mutableListOf<String>()
        val v = version.trim().toIntOrNull()
        if (v != null && v in 1..20) {
            val idx1 = v
            val idx2 = 31 - v
            if (idx1 in 1..parts.length() && idx2 in 1..parts.length()) {
                selected.add(parts.getString(idx1 - 1))
                selected.add(parts.getString(idx2 - 1))
            }
        }
        val sourceParts = if (selected.isNotEmpty()) {
            selected
        } else {
            (0 until parts.length()).map { parts.getString(it) }
        }

        val all = mutableListOf<Byte>()
        for (part in sourceParts) {
            val decoded = b64urlDecode(part)
            if (decoded.isNotEmpty()) all.addAll(decoded.toList())
        }
        logDebug("selectKeyParts: version=$version selected=${selected.size} total_len=${all.size}")
        return all.toByteArray()
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), gcmSpec)
        String(cipher.doFinal(data), StandardCharsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun b64urlEncode(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64urlDecode(s: String): ByteArray {
        var padded = s.replace('-', '+').replace('_', '/')
        while (padded.length % 4 != 0) padded += "="
        return Base64.decode(padded, Base64.NO_WRAP)
    }

    private fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    private fun signEcdsa(privateKey: java.security.PrivateKey, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return derToRaw(sig.sign())
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var pos = 2
        require(der[pos++] == 0x02.toByte()) { "Expected integer tag 0x02" }
        val rLen = der[pos++].toInt() and 0xFF
        val rBytes = if (rLen > 32) der.copyOfRange(pos + 1, pos + rLen) else der.copyOfRange(pos, pos + rLen)
        pos += rLen
        require(der[pos++] == 0x02.toByte()) { "Expected integer tag 0x02" }
        val sLen = der[pos++].toInt() and 0xFF
        val sBytes = if (sLen > 32) der.copyOfRange(pos + 1, pos + sLen) else der.copyOfRange(pos, pos + sLen)
        return to32Bytes(rBytes) + to32Bytes(sBytes)
    }

    private fun to32Bytes(arr: ByteArray): ByteArray = if (arr.size == 32) {
        arr
    } else if (arr.size < 32) {
        ByteArray(32 - arr.size) + arr
    } else {
        arr.copyOfRange(arr.size - 32, arr.size)
    }

    private fun to32Bytes(bi: BigInteger): ByteArray = to32Bytes(bi.toByteArray())

    private fun generateId(): String {
        val bytes = ByteArray(21)
        random.nextBytes(bytes)
        return b64urlEncode(bytes)
    }

    private fun buildAttestBody(
        viewerId: String,
        deviceId: String,
        challengeId: String,
        nonce: String,
        publicKeyJwk: JSONObject,
        signature: String,
    ): String {
        val body = JSONObject()
        body.put("viewer_id", viewerId)
        body.put("device_id", deviceId)
        body.put("challenge_id", challengeId)
        body.put("nonce", nonce)
        body.put("signature", signature)
        body.put("public_key", publicKeyJwk)

        val c = JSONObject()
        c.put("user_agent", desktopUA)
        c.put("pixel_ratio", 1.25)
        c.put("screen_width", 1536)
        c.put("screen_height", 960)
        c.put("color_depth", 24)
        c.put("languages", JSONArray().put("pl").put("en-US").put("en"))
        c.put("timezone", "Europe/Warsaw")
        c.put("hardware_concurrency", 16)
        c.put("touch_points", 0)
        c.put("webgl_vendor", "Google Inc. (AMD)")
        c.put("webgl_renderer", "ANGLE (AMD, Radeon HD 3200 Graphics Direct3D11 vs_5_0 ps_5_0), or similar")
        c.put("canvas_hash", "WX-VJN0kXlcMZ0stNqbJdsXINUkI3GaOtrqKflBzdOw")
        c.put("audio_hash", "_oGTjFqFiMCfUhMTzdEID7gIliFGMmPeNMqniFYvQ7M")
        c.put("webgl_params_hash", "C6UdsI-Zl3jO7GZUpXIlV0vD2S6XRmDtmaaRszYZ334")
        c.put("fonts_hash", "A3NvW7_xc4imEb2Z_dU5M6k6vDZTjWR7YiuZjLqys2o")
        c.put("codecs_hash", "gAcHkrAdUTpJQMTQz3IUpbxaSfLF8v-qi3--oveUBbQ")
        c.put("media_devices", "ai0ao0vi0")
        c.put("pointer_type", "fine,hover")
        val extra = JSONObject()
        extra.put("vendor", "")
        extra.put("appVersion", "5.0 (Windows)")
        c.put("extra", extra)
        body.put("client", c)

        val storage = JSONObject()
        storage.put("cookie", viewerId)
        storage.put("local_storage", viewerId)
        storage.put("indexed_db", "$viewerId:$deviceId")
        storage.put("cache_storage", "$viewerId:$deviceId")
        body.put("storage", storage)

        val attributes = JSONObject()
        attributes.put("entropy", "low")
        body.put("attributes", attributes)

        return body.toString()
    }
}
