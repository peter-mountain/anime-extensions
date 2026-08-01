package eu.kanade.tachiyomi.animeextension.pl.shinden

import keiyoushi.utils.ShindenLog
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ShindenDns : Dns {

    private val tag = "ShindenDns"

    private val dohUrl = "https://cloudflare-dns.com/dns-query"

    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> = try {
        val request = Request.Builder()
            .url("$dohUrl?name=$hostname&type=A")
            .header("Accept", "application/dns-json")
            .build()

        val response = dohClient.newCall(request).execute()
        val body = response.body.string()

        val json = JSONObject(body)
        val answers = json.getJSONArray("Answer")
        val addresses = mutableListOf<InetAddress>()

        for (i in 0 until answers.length()) {
            val answer = answers.getJSONObject(i)
            if (answer.optString("type") == "1") {
                addresses.add(InetAddress.getByName(answer.getString("data")))
            }
        }

        if (addresses.isNotEmpty()) {
            ShindenLog.d(tag, "Resolved $hostname -> ${addresses.first().hostAddress}")
            addresses
        } else {
            ShindenLog.d(tag, "DoH returned no A records for $hostname, falling back to system")
            Dns.SYSTEM.lookup(hostname)
        }
    } catch (e: Exception) {
        ShindenLog.e(tag, "DoH failed for $hostname: ${e.message}, falling back to system", e)
        Dns.SYSTEM.lookup(hostname)
    }
}
