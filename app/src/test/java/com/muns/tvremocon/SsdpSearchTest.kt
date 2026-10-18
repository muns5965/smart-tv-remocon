package com.muns.tvremocon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

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
}
