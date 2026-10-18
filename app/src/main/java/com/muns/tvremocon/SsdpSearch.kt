package com.muns.tvremocon

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** 찾은 TV. id는 uuid가 있으면 "uuid:<값>", 없으면 "ip:<IP>" */
data class TvInfo(
    val id: String,
    val ip: String,
    val name: String = "",
    val model: String = "",
    val uuid: String = ""
)

/**
 * SSDP 검색 (순수 JVM — 안드로이드 의존 없음, 단위 테스트 가능).
 * 실TV 확인 결과: WEBOS_API.md 6절
 */
class SsdpSearch(private val st: String = ST_LG) {

    /** 검색해서 TV를 찾을 때마다 onFound 호출. 찾은 개수 반환. 호출한 스레드를 막는다. */
    fun search(timeoutMs: Long = 3000, onFound: (TvInfo) -> Unit): Int {
        val msg = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n\r\n").toByteArray()
        val seen = HashSet<String>()
        // 주의: Kotlin 1.9는 inline 람다(use 등) 안에서 continue 불가 → try/finally 사용
        val sock = DatagramSocket()
        try {
            sock.soTimeout = 300
            val group = InetAddress.getByName("239.255.255.250")
            for (i in 0 until 3) {                       // UDP 유실 대비 3번
                sock.send(DatagramPacket(msg, msg.size, group, 1900))
                Thread.sleep(100)
            }
            val buf = ByteArray(4096)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                val ip = pkt.address?.hostAddress ?: continue
                val parsed = parse(text, ip) ?: continue
                if (!seen.add(parsed.first.id)) continue
                val loc = parsed.second
                val desc = if (loc != null) fetchDescription(loc) else null
                onFound(
                    parsed.first.copy(
                        name = desc?.first ?: "",
                        model = desc?.second ?: ""
                    )
                )
            }
        } finally {
            sock.close()
        }
        return seen.size
    }

    companion object {
        const val ST_LG = "urn:lge-com:service:webos-second-screen:1"

        /** 응답 패킷 → (TvInfo, LOCATION). 대상 ST가 아니면 null */
        fun parse(packet: String, fromIp: String, st: String = ST_LG): Pair<TvInfo, String?>? {
            val headers = HashMap<String, String>()
            packet.split("\r\n", "\n").drop(1).forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().uppercase()] = line.substring(i + 1).trim()
            }
            val gotSt = headers["ST"] ?: headers["NT"] ?: return null
            if (!gotSt.contains(st)) return null
            val usn = headers["USN"] ?: ""
            val uuid = if (usn.startsWith("uuid:")) usn.removePrefix("uuid:").substringBefore("::") else ""
            val id = if (uuid.isNotEmpty()) "uuid:$uuid" else "ip:$fromIp"
            return TvInfo(id = id, ip = fromIp, uuid = uuid) to headers["LOCATION"]
        }

        /** LOCATION XML → (friendlyName, modelNumber 또는 modelName) */
        fun fetchDescription(location: String): Pair<String, String>? = try {
            val conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val xml = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            parseDescription(xml)
        } catch (e: Exception) {
            null
        }

        fun parseDescription(xml: String): Pair<String, String> {
            fun tag(t: String) = Regex("<$t>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim() ?: ""
            val model = tag("modelNumber").ifEmpty { tag("modelName") }
            return tag("friendlyName") to model
        }
    }
}
