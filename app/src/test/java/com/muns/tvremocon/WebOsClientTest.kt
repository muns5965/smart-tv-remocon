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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 가짜 LG TV(MockWebServer, TLS 자체 서명)로 WebOsClient 시나리오 확인 */
class WebOsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var held: HeldCertificate
    private val events = LinkedBlockingQueue<String>()
    private val pointerTexts = LinkedBlockingQueue<String>()
    private val tvReceived = LinkedBlockingQueue<String>()

    private val listener = object : WebOsClient.Listener {
        override fun onState(state: String, message: String) { events.add("state:$state") }
        override fun onRegistered(clientKey: String, certSha256: String?) { events.add("registered:$clientKey:$certSha256") }
        override fun onResponse(reqId: String, ok: Boolean, payload: JSONObject?, error: String?) {
            events.add("response:$reqId:$ok:${error ?: ""}")
        }
        override fun onKeyRejected() { events.add("keyRejected") }
        override fun onCertMismatch(actualSha256: String?) { events.add("certMismatch:$actualSha256") }
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
                    val key = m.getJSONObject("payload").optString("client-key")
                    when (key) {
                        "good-key" -> webSocket.send(registered("good-key"))
                        "bad-key" -> webSocket.send("""{"type":"error","id":"$id","error":"401 insufficient permissions"}""")
                        else -> {
                            webSocket.send("""{"type":"response","id":"$id","payload":{"pairingType":"PROMPT","returnValue":true}}""")
                            webSocket.send(registered("new-key"))
                        }
                    }
                }
                "request" -> when (m.optString("uri")) {
                    "ssap://com.webos.service.networkinput/getPointerInputSocket" -> webSocket.send(
                        """{"type":"response","id":"$id","payload":{"returnValue":true,"socketPath":"wss://${server.hostName}:${server.port}/pointer"}}"""
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

    @Test
    fun firstPairing_prompt_connected_request_button() {
        val c = client()
        c.connect(server.hostName, null, null)
        await("state:connecting")
        await("state:prompt")
        assertEquals("registered:new-key:${fp()}", await("registered:"))
        await("state:connected")
        assertTrue(c.isConnected())

        c.request("js_1", "ssap://system.launcher/launch", JSONObject().put("id", "netflix"))
        assertEquals("response:js_1:true:", await("response:js_1"))
        // TV가 받은 요청 확인
        var launch: String? = null
        while (launch == null) {
            val t = tvReceived.poll(3, TimeUnit.SECONDS) ?: break
            if (t.contains("system.launcher/launch")) launch = t
        }
        assertTrue(launch!!.contains("\"netflix\""))

        assertTrue(c.button("UP"))
        assertEquals("type:button\nname:UP\n\n", pointerTexts.poll(5, TimeUnit.SECONDS))
        c.disconnect()
    }

    @Test
    fun errorResponse_isReported() {
        val c = client()
        c.connect(server.hostName, "good-key", null)
        await("state:connected")
        c.request("js_2", "ssap://bad/uri", null)
        assertEquals("response:js_2:false:404 no such service", await("response:js_2"))
        c.disconnect()
    }

    @Test
    fun savedKey_connectsWithoutPrompt() {
        val c = client()
        c.connect(server.hostName, "good-key", fp())
        await("state:connecting")
        val next = await("state:")
        assertEquals("state:connected", next)          // prompt 없이 바로
        c.disconnect()
    }

    @Test
    fun rejectedKey_clearsAndRePairs() {
        val c = client()
        c.connect(server.hostName, "bad-key", null)
        await("keyRejected")
        await("state:prompt")
        assertEquals("registered:new-key:${fp()}", await("registered:"))
        await("state:connected")
        c.disconnect()
    }

    @Test
    fun changedCertificate_isRefused() {
        val c = client()
        c.connect(server.hostName, "good-key", "AA:BB:CC")
        assertEquals("certMismatch:${fp()}", await("certMismatch:"))
        assertFalse(c.isConnected())
    }

    @Test
    fun requestWhileDisconnected_failsFast() {
        val c = client()
        c.request("js_9", "ssap://audio/volumeUp", null)
        assertEquals("response:js_9:false:not_connected", await("response:js_9"))
        assertFalse(c.button("UP"))
    }
}
