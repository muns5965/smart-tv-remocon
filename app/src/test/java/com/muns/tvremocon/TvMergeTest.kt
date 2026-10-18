package com.muns.tvremocon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4차 검토의 저장 경계 조건 — 비정상 uuid가 기존 TV의 인증정보와 묶이지 않는지 */
class TvMergeTest {

    private val U = "uuid:d3d5b624-f443-c293-d320-d79150ded2b7"

    private fun store() = JSONObject().put(
        U, JSONObject().put("ip", "192.168.8.219").put("key", "real-key").put("cert", "AA:BB")
            .put("uuid", "d3d5b624-f443-c293-d320-d79150ded2b7")
    )

    @Test
    fun validUuid_matchingExisting_usesKey_andUpdatesIp() {
        val all = store()
        val r = TvMerge.upsertFound(all, TvInfo(U, "192.168.8.230", uuid = "d3d5b624-f443-c293-d320-d79150ded2b7"))
        assertTrue(r.known)
        assertEquals("192.168.8.230", all.getJSONObject(U).getString("ip"))
        assertEquals("real-key", all.getJSONObject(U).getString("key"))
    }

    @Test
    fun validUuid_newDevice_hasNoKey() {
        val all = store()
        val r = TvMerge.upsertFound(all, TvInfo("uuid:other", "192.168.8.50", uuid = "other"))
        assertFalse(r.known)
        assertEquals("", all.getJSONObject("uuid:other").optString("key"))
    }

    @Test
    fun badUuid_sameIpAsExisting_doesNotTakeExistingKey() {
        val all = store()
        // SsdpSearch.parse가 비정상 uuid를 ip: id로 바꿔 넘김
        val r = TvMerge.upsertFound(all, TvInfo("ip:192.168.8.219", "192.168.8.219"))
        assertFalse(r.known)
        assertEquals("", all.getJSONObject("ip:192.168.8.219").optString("key"))
        assertEquals("", all.getJSONObject("ip:192.168.8.219").optString("cert"))
        assertEquals("real-key", all.getJSONObject(U).getString("key"))     // 기존 항목은 그대로
        assertNull(r.migratedFrom)
    }

    @Test
    fun badUuid_newIp_createsIpEntry() {
        val all = store()
        val r = TvMerge.upsertFound(all, TvInfo("ip:10.0.0.9", "10.0.0.9"))
        assertFalse(r.known)
        assertTrue(all.has("ip:10.0.0.9"))
    }

    @Test
    fun validUuid_absorbsManualIpEntry() {
        val all = JSONObject().put("ip:192.168.8.219", JSONObject().put("ip", "192.168.8.219").put("key", "manual-key").put("cert", "CC"))
        val r = TvMerge.upsertFound(all, TvInfo(U, "192.168.8.219", uuid = "d3d5b624-f443-c293-d320-d79150ded2b7"))
        assertTrue(r.known)
        assertEquals("ip:192.168.8.219", r.migratedFrom)
        assertFalse(all.has("ip:192.168.8.219"))
        assertEquals("manual-key", all.getJSONObject(U).getString("key"))
        assertEquals("CC", all.getJSONObject(U).getString("cert"))
    }
}
