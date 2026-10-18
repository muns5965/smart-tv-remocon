package com.muns.tvremocon

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_LOCATION_LEN = 256
        const val DESC_TIMEOUT_MS = 2000L
        private val UUID_RE = Regex("^[0-9A-Za-z-]{1,64}$")
        private val STATUS_RE = Regex("^HTTP/1\\.[01] 200\\b")

        /**
         * 설명 조회 마감 감시용 공유 스케줄러 (데몬 1개). 조회마다 "마감 시 소켓 닫기"를 예약하고
         * finally에서 취소한다 → 정상 완료 후 남는 예약 없음 (removeOnCancelPolicy).
         */
        internal val DESC_TIMER = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "ssdp-desc-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        /** 응답 패킷 → (TvInfo, LOCATION). 대상 ST가 아니면 null */
        /**
         * 검색 응답 검사 (UPnP 규격 형식):
         * 첫 줄 "HTTP/1.x 200", ST 정확히 일치. 아니면 null.
         * uuid가 형식에 안 맞으면 uuid 없이 "ip:" 항목(새 기기)으로 — 기존 TV의 키와 묶이지 않게.
         * LOCATION이 너무 길면 버리고 TV는 이름 없이 유지.
         */
        fun parse(packet: String, fromIp: String, st: String = ST_LG): Pair<TvInfo, String?>? {
            val lines = packet.split("\r\n", "\n")
            if (!STATUS_RE.containsMatchIn(lines.firstOrNull()?.trim() ?: "")) return null
            val headers = HashMap<String, String>()
            lines.drop(1).forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().uppercase()] = line.substring(i + 1).trim()
            }
            if (headers["ST"] != st) return null
            val usn = headers["USN"] ?: ""
            val raw = if (usn.startsWith("uuid:")) usn.removePrefix("uuid:").substringBefore("::") else ""
            val uuid = if (UUID_RE.matches(raw)) raw else ""
            val id = if (uuid.isNotEmpty()) "uuid:$uuid" else "ip:$fromIp"
            val loc = headers["LOCATION"]?.takeIf { it.length <= MAX_LOCATION_LEN }
            return TvInfo(id = id, ip = fromIp, uuid = uuid) to loc
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
        /**
         * 설명 XML 조회. 연결·헤더·본문을 합쳐 [DESC_TIMEOUT_MS] 안에 끝낸다:
         * 하나의 마감 시각으로 연결/읽기 대기 시간을 남은 시간만큼만 주고,
         * 마감 시각에 소켓을 강제로 닫는 예약을 걸어 절대 마감을 보장한다.
         * 실패(강제 종료 포함)는 null — 호출자는 TV를 이름 없이 유지.
         */
        fun fetchDescription(location: String, fromIp: String, timeoutMs: Long = DESC_TIMEOUT_MS): Pair<String, String>? {
            val (addr, port, path) = checkLocation(location, fromIp) ?: return null
            val deadline = System.currentTimeMillis() + timeoutMs
            fun remaining(): Int = (deadline - System.currentTimeMillis()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val s = Socket()
            val closeAtDeadline = Runnable { try { s.close() } catch (e: Exception) { } }   // Runnable 명시: Callable과 겹침 방지
            val watchdog = DESC_TIMER.schedule(closeAtDeadline, timeoutMs, TimeUnit.MILLISECONDS)
            try {
                s.connect(InetSocketAddress(addr, port), remaining().coerceAtLeast(1))
                val hostHeader = if (addr.address.size == 16) "[${addr.hostAddress}]" else addr.hostAddress
                s.getOutputStream().write(
                    ("GET $path HTTP/1.0\r\nHost: $hostHeader:$port\r\nConnection: close\r\n\r\n").toByteArray()
                )
                val input = s.getInputStream()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (out.size() < MAX_DESC_BYTES) {
                    val left = remaining()
                    if (left <= 0) return null
                    s.soTimeout = left
                    val n = input.read(buf, 0, minOf(buf.size, MAX_DESC_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                val text = out.toString("UTF-8")
                val headerEnd = text.indexOf("\r\n\r\n")
                if (headerEnd < 0 || headerEnd > MAX_HEADER_BYTES) return null
                if (!STATUS_RE.containsMatchIn(text.substringBefore("\r\n"))) return null
                return parseDescription(text.substring(headerEnd + 4))
            } catch (e: Exception) {
                return null
            } finally {
                watchdog.cancel(false)
                try { s.close() } catch (e: Exception) { }
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
