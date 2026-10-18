package com.muns.tvremocon

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 가짜 LG TV(MockWebServer, TLS 자체 서명)로 WebOsClient 시나리오 확인.
 * PIN 흐름은 2026-10-19 실TV(OLED42C3KNA) 응답 형식을 따른다 (WEBOS_API.md 2절).
 */
class WebOsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var held: HeldCertificate
    private val events = LinkedBlockingQueue<String>()
    private val pointerTexts = LinkedBlockingQueue<String>()
    private val tvReceived = LinkedBlockingQueue<String>()
    @Volatile private var pointerFails = false
    @Volatile private var pointerPath: String? = null     // null이면 정상 주소

    private val listener = object : WebOsClient.Listener {
        override fun onState(tag: String, state: String, message: String) { events.add("state:$tag:$state:$message") }
        override fun onRegistered(tag: String, clientKey: String, certSha256: String?) {
            events.add("registered:$tag:$clientKey:$certSha256")
        }
        override fun onResponse(tag: String, reqId: String, ok: Boolean, payload: JSONObject?, error: String?) {
            events.add("response:$tag:$reqId:$ok:${error ?: ""}")
        }
        override fun onKeyRejected(tag: String) { events.add("keyRejected:$tag") }
        override fun onCertMismatch(tag: String, actualSha256: String?) { events.add("certMismatch:$tag:$actualSha256") }
        override fun onPointerError(tag: String, message: String) { events.add("pointerError:$tag") }
    }

    /** 가짜 TV 본 소켓 */
    private val tv = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)                 // 실제 TV처럼 close에 응답
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            tvReceived.add(text)
            val m = JSONObject(text)
            val id = m.optString("id")
            when (m.optString("type")) {
                "register" -> {
                    val p = m.getJSONObject("payload")
                    when (p.optString("client-key")) {
                        "good-key" -> webSocket.send(registered("good-key"))
                        "bad-key" -> webSocket.send("""{"type":"error","id":"$id","error":"401 insufficient permissions"}""")
                        else -> if (p.optString("pairingType") == "PIN") {
                            // 실TV: {"type":"response","id":"register_0","payload":{"pairingType":"PIN","returnValue":true}}
                            webSocket.send("""{"type":"response","id":"$id","payload":{"pairingType":"PIN","returnValue":true}}""")
                        } else {
                            webSocket.send("""{"type":"response","id":"$id","payload":{"pairingType":"PROMPT","returnValue":true}}""")
                            webSocket.send(registered("prompt-key"))
                        }
                    }
                }
                "request" -> when (m.optString("uri")) {
                    "ssap://pairing/setPin" -> {
                        if (m.getJSONObject("payload").optString("pin") == "49914398") {
                            webSocket.send(registered("pin-key"))
                        } else {
                            webSocket.send("""{"type":"error","id":"$id","error":"500 Application error","payload":{}}""")
                        }
                    }
                    "ssap://com.webos.service.networkinput/getPointerInputSocket" -> webSocket.send(
                        if (pointerFails) """{"type":"error","id":"$id","error":"500 no pointer"}"""
                        else """{"type":"response","id":"$id","payload":{"returnValue":true,"socketPath":"${pointerPath ?: "wss://${server.hostName}:${server.port}/pointer"}"}}"""
                    )
                    "ssap://bad/uri" -> webSocket.send("""{"type":"error","id":"$id","error":"404 no such service"}""")
                    else -> webSocket.send("""{"type":"response","id":"$id","payload":{"returnValue":true}}""")
                }
            }
        }
    }

    private val pointerTv = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) { pointerTexts.add(text) }
        override fun onOpen(webSocket: WebSocket, response: Response) {}
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun registered(key: String) = """{"type":"registered","id":"register_0","payload":{"client-key":"$key"}}"""

    @Before
    fun setUp() {
        held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val certs = HandshakeCertificates.Builder().heldCertificate(held).build()
        server = MockWebServer()
        server.useHttps(certs.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(tv))
        server.enqueue(MockResponse().withWebSocketUpgrade(pointerTv))
        server.enqueue(MockResponse().withWebSocketUpgrade(tv))
        server.enqueue(MockResponse().withWebSocketUpgrade(pointerTv))
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (e: java.io.IOException) {
            // 소켓 정리 대기 시간 초과는 시나리오 결과와 무관
        }
    }

    private fun fp() = WebOsClient.sha256(held.certificate.encoded)

    private fun client() = WebOsClient(listener, port = server.port, scheme = "wss")

    /** 조건에 맞는 이벤트가 올 때까지 기다림 (지나간 이벤트는 버림) */
    private fun await(prefix: String, sec: Long = 5): String {
        val end = System.currentTimeMillis() + sec * 1000
        val seen = ArrayList<String>()
        while (System.currentTimeMillis() < end) {
            val e = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (e.startsWith(prefix)) return e
            seen.add(e)
        }
        throw AssertionError("'$prefix' 이벤트 없음. 받은 이벤트: $seen")
    }

    private fun receivedRegister(): JSONObject {
        while (true) {
            val t = tvReceived.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("register 없음")
            val m = JSONObject(t)
            if (m.optString("type") == "register") return m.getJSONObject("payload")
        }
    }

    @Test
    fun newPairing_usesPin_byDefault() {
        val c = client()
        c.connect("tvA", server.hostName, null, null)
        assertEquals("PIN", receivedRegister().optString("pairingType"))
        await("state:tvA:pin")
        c.sendPin("49914398")
        assertEquals("registered:tvA:pin-key:${fp()}", await("registered:"))
        await("state:tvA:connected")
        assertTrue(c.isConnected())
        c.disconnect()
    }

    @Test
    fun wrongPin_staysInPinState_thenCorrectPinWorks() {
        val c = client()
        c.connect("tvA", server.hostName, null, null)
        await("state:tvA:pin")
        c.sendPin("0000")
        assertEquals("state:tvA:pin:PIN이 맞지 않습니다", await("state:tvA:pin:PIN이"))
        assertFalse(c.isConnected())
        c.sendPin("49914398")
        await("registered:tvA:pin-key")
        c.disconnect()
    }

    @Test
    fun promptPairing_whenRequested() {
        val c = client()
        c.connect("tvA", server.hostName, null, null, WebOsClient.PAIRING_PROMPT)
        assertEquals("PROMPT", receivedRegister().optString("pairingType"))
        await("state:tvA:prompt")
        assertEquals("registered:tvA:prompt-key:${fp()}", await("registered:"))
        await("state:tvA:connected")
        c.disconnect()
    }

    @Test
    fun connected_request_and_button() {
        val c = client()
        c.connect("tvA", server.hostName, "good-key", fp())
        await("state:tvA:connecting")
        assertTrue(await("state:tvA:").startsWith("state:tvA:connected"))   // 허용·PIN 없이 바로

        c.request("js_1", "ssap://system.launcher/launch", JSONObject().put("id", "netflix"))
        assertEquals("response:tvA:js_1:true:", await("response:tvA:js_1"))
        c.request("js_2", "ssap://bad/uri", null)
        assertEquals("response:tvA:js_2:false:404 no such service", await("response:tvA:js_2"))

        assertTrue(c.button("UP"))
        assertEquals("type:button\nname:UP\n\n", pointerTexts.poll(5, TimeUnit.SECONDS))
        c.disconnect()
    }

    @Test
    fun pointer_move_click_scroll_formats() {
        val c = client()
        c.connect("tvA", server.hostName, "good-key", null)
        await("state:tvA:connected")
        assertTrue(c.pointerMove(12, -7))
        assertTrue(c.pointerClick())
        assertTrue(c.pointerScroll(10))
        assertEquals("type:move\ndx:12\ndy:-7\ndown:0\n\n", pointerTexts.poll(5, TimeUnit.SECONDS))
        assertEquals("type:click\n\n", pointerTexts.poll(5, TimeUnit.SECONDS))
        assertEquals("type:scroll\ndx:0\ndy:10\n\n", pointerTexts.poll(5, TimeUnit.SECONDS))
        c.disconnect()
    }

    @Test
    fun rejectedKey_clearsAndRePairsWithPin() {
        val c = client()
        c.connect("tvA", server.hostName, "bad-key", null)
        await("keyRejected:tvA")
        await("state:tvA:pin")
        c.sendPin("49914398")
        await("registered:tvA:pin-key")
        c.disconnect()
    }

    @Test
    fun changedCertificate_isRefused_andKeyIsNeverSent() {
        val c = client()
        c.connect("tvA", server.hostName, "good-key", "AA:BB:CC")
        assertEquals("certMismatch:tvA:${fp()}", await("certMismatch:"))
        assertFalse(c.isConnected())
        // TLS 단계에서 끊었으므로 TV(가짜)는 register(=키)를 받지 못해야 한다
        assertNull(tvReceived.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun callbacksCarryTheirOwnTag_whenSwitchingTv() {
        val c = client()
        c.connect("tvA", server.hostName, "good-key", null)
        assertEquals("registered:tvA:good-key:${fp()}", await("registered:"))
        await("state:tvA:connected")
        Thread.sleep(300)                                      // tvA 포인터 소켓이 먼저 서버 응답을 쓰도록
        c.connect("tvB", server.hostName, null, null)          // 다른 TV로 전환
        await("state:tvB:pin")
        c.sendPin("49914398")
        assertEquals("registered:tvB:pin-key:${fp()}", await("registered:"))
        c.disconnect()
    }

    @Test
    fun pointerFailure_isReported() {
        pointerFails = true
        val c = client()
        c.connect("tvA", server.hostName, "good-key", null)
        await("state:tvA:connected")
        Thread.sleep(300)                       // 연결 직후 자동 요청 실패가 지나가길 기다림 (대기 버튼 없음 → 조용히)
        assertTrue(c.button("UP"))              // 대기열에 넣고 다시 요청 → 실패
        await("pointerError:tvA")
        c.disconnect()
    }

    private fun badPointer(path: String) {
        pointerPath = path
        val c = client()
        c.connect("tvA", server.hostName, "good-key", null)
        await("state:tvA:connected")
        await("pointerError:tvA")                 // 연결 직후 자동 요청에서 차단, 오류 1회
        assertFalse(c.button("UP"))                 // 이후 이 세션에서는 포인터 차단
        assertFalse(c.pointerMove(5, 5))
        assertNull(pointerTexts.poll(500, TimeUnit.MILLISECONDS))
        c.disconnect()
    }

    @Test
    fun pointerPath_otherHost_isRefused() = badPointer("wss://127.0.0.2:${server.port}/pointer")

    @Test
    fun pointerPath_otherPort_isRefused() = badPointer("wss://${server.hostName}:1/pointer")

    @Test
    fun pointerPath_plainWs_isRefused() = badPointer("ws://${server.hostName}:${server.port}/pointer")

    @Test
    fun sameTargetCheck_matchesRealTvFormat() {
        assertTrue(WebOsClient.isSameTarget(
            "wss://192.168.8.219:3001/resources/bb05cde3d83ccbcb183c901177d05e315e770b9f/netinput.pointer.sock",
            "wss", "192.168.8.219", 3001))
        assertFalse(WebOsClient.isSameTarget("wss://192.168.8.219/x", "wss", "192.168.8.219", 3001))
        assertFalse(WebOsClient.isSameTarget("not a url", "wss", "192.168.8.219", 3001))
    }

    @Test
    fun requestWhileDisconnected_failsFast() {
        val c = client()
        c.request("js_9", "ssap://audio/volumeUp", null)
        assertEquals("response::js_9:false:not_connected", await("response::js_9"))
        assertFalse(c.button("UP"))
    }
}
