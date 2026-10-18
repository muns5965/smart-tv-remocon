package com.muns.tvremocon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class SsdpSearchTest {

    // 2026-10-19 실TV(OLED42C3KNA) 응답 (WEBOS_API.md 6절)
    private val real = "HTTP/1.1 200 OK\r\n" +
        "ST: urn:lge-com:service:webos-second-screen:1\r\n" +
        "LOCATION: http://192.168.8.219:1474/\r\n" +
        "SERVER: WebOS/4.1.0 UPnP/1.0\r\n" +
        "USN: uuid:d3d5b624-f443-c293-d320-d79150ded2b7::urn:lge-com:service:webos-second-screen:1\r\n\r\n"

    @Test
    fun parsesRealTvResponse() {
        val r = SsdpSearch.parse(real, "192.168.8.219")
        assertNotNull(r)
        val (tv, loc) = r!!
        assertEquals("uuid:d3d5b624-f443-c293-d320-d79150ded2b7", tv.id)
        assertEquals("192.168.8.219", tv.ip)
        assertEquals("d3d5b624-f443-c293-d320-d79150ded2b7", tv.uuid)
        assertEquals("http://192.168.8.219:1474/", loc)
    }

    @Test
    fun ignoresOtherDevices() {
        val other = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:x\r\n\r\n"
        assertNull(SsdpSearch.parse(other, "192.168.8.10"))
    }

    @Test
    fun fallsBackToIpIdWithoutUsn() {
        val noUsn = "HTTP/1.1 200 OK\r\nST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"
        assertEquals("ip:10.0.0.9", SsdpSearch.parse(noUsn, "10.0.0.9")!!.first.id)
    }

    @Test
    fun parsesDescription() {
        val xml = "<root><device><friendlyName>[LG] webOS TV OLED42C3KNA</friendlyName>" +
            "<modelName>LG Smart TV</modelName><modelNumber>OLED42C3KNA</modelNumber></device></root>"
        assertEquals("[LG] webOS TV OLED42C3KNA" to "OLED42C3KNA", SsdpSearch.parseDescription(xml))
    }

    // ---- LOCATION 검증 (검토 ③)

    @Test
    fun location_mustBeHttp_andSameHostAsResponder() {
        assertNotNull(SsdpSearch.checkLocation("http://192.168.8.219:1474/", "192.168.8.219"))
        assertNull(SsdpSearch.checkLocation("http://192.168.8.50:1474/", "192.168.8.219"))   // 다른 호스트
        assertNull(SsdpSearch.checkLocation("https://192.168.8.219:1474/", "192.168.8.219")) // http만
        assertNull(SsdpSearch.checkLocation("file:///etc/passwd", "192.168.8.219"))
        assertNull(SsdpSearch.checkLocation("http://tv.local:1474/", "192.168.8.219"))       // 이름 조회 안 함
        assertNull(SsdpSearch.checkLocation("not a url", "192.168.8.219"))
        assertEquals(80, SsdpSearch.checkLocation("http://192.168.8.219/desc.xml", "192.168.8.219")!!.second)
    }

    @Test
    fun ipLiteralCheck() {
        listOf("192.168.8.219", "10.0.0.50", "::1", "fe80::1%wlan0").forEach { assertEquals(it, true, SsdpSearch.isIpLiteral(it)) }
        listOf("256.1.1.1", "tv.local", "evil.com", "1.2.3", "").forEach { assertEquals(it, false, SsdpSearch.isIpLiteral(it)) }
    }

    /** 가짜 TV의 설명 XML을 소켓 GET으로 읽기 (안드로이드 HTTP 차단 우회 방식) */
    @Test
    fun fetchDescription_overPlainSocket() {
        val srv = ServerSocket(0)
        val body = "<root><device><friendlyName>[LG] webOS TV OLED42C3KNA</friendlyName>" +
            "<modelNumber>OLED42C3KNA</modelNumber></device></root>"
        var requestLine = ""
        val t = thread {
            srv.accept().use { s ->
                requestLine = s.getInputStream().bufferedReader().readLine() ?: ""
                s.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray()
                )
            }
        }
        val r = SsdpSearch.fetchDescription("http://127.0.0.1:${srv.localPort}/", "127.0.0.1")
        t.join(3000)
        srv.close()
        assertEquals("GET / HTTP/1.0", requestLine)
        assertEquals("[LG] webOS TV OLED42C3KNA" to "OLED42C3KNA", r)
    }

    @Test
    fun fetchDescription_refusesOtherHost_withoutConnecting() {
        assertNull(SsdpSearch.fetchDescription("http://127.0.0.2:1/", "127.0.0.1"))
    }
}
