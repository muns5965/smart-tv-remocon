package com.muns.tvremocon

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

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
 *
 * 1) timeoutMs 동안 응답만 모은다 (설명 XML 때문에 수신이 밀리지 않게)
 * 2) 그다음 TV마다 LOCATION의 설명 XML을 받아 이름·모델을 채운다
 *    - 안드로이드는 HTTP(cleartext)를 기본 차단 → 소켓으로 직접 GET (앱 전체 정책은 차단 유지)
 *    - LOCATION 호스트가 응답 보낸 IP와 같을 때만, http만, 2초·64KB 제한
 *    - 실패해도 TV는 이름 없이 그대로 돌려준다
 */
class SsdpSearch(private val st: String = ST_LG) {

    /** 검색해서 TV마다 onFound 호출. 찾은 개수 반환. 호출한 스레드를 막는다. */
    fun search(timeoutMs: Long = 3000, onFound: (TvInfo) -> Unit): Int {
        val msg = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n\r\n").toByteArray()
        val found = LinkedHashMap<String, Pair<TvInfo, String?>>()
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
                val parsed = parse(text, ip, st) ?: continue
                if (!found.containsKey(parsed.first.id)) found[parsed.first.id] = parsed
            }
        } finally {
            sock.close()
        }
        for ((tv, loc) in found.values) {
            val desc = if (loc != null) fetchDescription(loc, tv.ip) else null
            onFound(tv.copy(name = desc?.first ?: "", model = desc?.second ?: ""))
        }
        return found.size
    }

    companion object {
        const val ST_LG = "urn:lge-com:service:webos-second-screen:1"
        private const val MAX_DESC_BYTES = 64 * 1024
        private const val DESC_TIMEOUT_MS = 2000

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

        /**
         * LOCATION을 쓸 수 있는지 검사하고 (주소, 포트, 경로) 반환. 안 되면 null.
         * http만, 호스트는 IP 표기만, 응답 보낸 IP와 같아야 함.
         */
        fun checkLocation(location: String, fromIp: String): Triple<InetAddress, Int, String>? {
            val u = try { URI(location.trim()) } catch (e: Exception) { return null }
            if (!"http".equals(u.scheme, ignoreCase = true)) return null
            val host = (u.host ?: return null).removePrefix("[").removeSuffix("]")
            if (!isIpLiteral(host) || !isIpLiteral(fromIp)) return null     // 이름 조회(DNS) 안 함
            val addr = try {
                val a = InetAddress.getByName(host)
                if (a != InetAddress.getByName(fromIp)) return null
                a
            } catch (e: Exception) {
                return null
            }
            val port = if (u.port in 1..65535) u.port else 80
            val path = (u.rawPath ?: "").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
            return Triple(addr, port, path)
        }

        /** IPv4(점 4개) 또는 IPv6(콜론, 16진수, 선택적 %스코프) 표기인지. 호스트 이름은 false */
        fun isIpLiteral(s: String): Boolean =
            Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$").matches(s) ||
                Regex("^[0-9A-Fa-f]{0,4}(:[0-9A-Fa-f]{0,4}){2,7}(%[0-9A-Za-z_.-]+)?$").matches(s)

        /** LOCATION XML → (friendlyName, modelNumber 또는 modelName). 실패 시 null */
        fun fetchDescription(location: String, fromIp: String): Pair<String, String>? {
            val (addr, port, path) = checkLocation(location, fromIp) ?: return null
            return try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(addr, port), DESC_TIMEOUT_MS)
                    s.soTimeout = DESC_TIMEOUT_MS
                    val hostHeader = if (addr.address.size == 16) "[${addr.hostAddress}]" else addr.hostAddress
                    s.getOutputStream().write(
                        ("GET $path HTTP/1.0\r\nHost: $hostHeader:$port\r\n" +
                            "Connection: close\r\n\r\n").toByteArray()
                    )
                    val input = s.getInputStream()
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + DESC_TIMEOUT_MS
                    while (out.size() < MAX_DESC_BYTES && System.currentTimeMillis() < deadline) {
                        val n = input.read(buf, 0, minOf(buf.size, MAX_DESC_BYTES - out.size()))
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    val text = out.toString("UTF-8")
                    val status = text.substringBefore("\r\n")
                    if (!status.contains(" 200")) null else parseDescription(text.substringAfter("\r\n\r\n"))
                }
            } catch (e: Exception) {
                null
            }
        }

        fun parseDescription(xml: String): Pair<String, String> {
            fun tag(t: String) = Regex("<$t>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim() ?: ""
            val model = tag("modelNumber").ifEmpty { tag("modelName") }
            return tag("friendlyName") to model
        }
    }
}
