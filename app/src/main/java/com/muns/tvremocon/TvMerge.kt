package com.muns.tvremocon

import org.json.JSONObject

/**
 * SSDP로 찾은 TV를 저장 목록에 합치는 판단 (순수 JVM — 단위 테스트 가능). TvStore가 사용.
 *
 * | 상황                          | 처리                                              |
 * |-------------------------------|---------------------------------------------------|
 * | 정상 uuid, 기존 uuid와 일치    | 기존 키·인증서 사용, IP만 갱신                      |
 * | 정상 uuid, 새 기기            | 새 항목 (키 없음 → PIN 등록)                        |
 * | 정상 uuid, 같은 IP의 수동 ip: | 수동 항목의 키·인증서를 uuid 항목으로 옮기고 삭제   |
 * | 비정상 uuid(→ ip: id)         | ip: 항목만 사용. **기존 uuid 항목의 키는 절대 가져오지 않음** |
 *
 * 사용자가 IP를 직접 입력하는 경우(TvStore.ensureManual)는 사용자가 TV를 지목한 것이므로
 * 같은 IP의 기존 항목을 쓴다 — 상대가 바뀌었으면 인증서 지문 고정이 막는다.
 */
object TvMerge {

    class Result(val known: Boolean, val migratedFrom: String?)

    fun upsertFound(all: JSONObject, tv: TvInfo): Result {
        val cur = all.optJSONObject(tv.id) ?: JSONObject()
        var migrated: String? = null
        if (tv.id.startsWith("uuid:")) {
            val manualId = "ip:${tv.ip}"
            val manual = all.optJSONObject(manualId)
            if (manual != null) {
                if (cur.optString("key").isEmpty() && manual.optString("key").isNotEmpty()) cur.put("key", manual.optString("key"))
                if (cur.optString("cert").isEmpty() && manual.optString("cert").isNotEmpty()) cur.put("cert", manual.optString("cert"))
                all.remove(manualId)
                migrated = manualId
            }
        }
        cur.put("ip", tv.ip)
        if (tv.name.isNotEmpty()) cur.put("name", tv.name)
        if (tv.model.isNotEmpty()) cur.put("model", tv.model)
        if (tv.uuid.isNotEmpty()) cur.put("uuid", tv.uuid)
        all.put(tv.id, cur)
        return Result(cur.optString("key").isNotEmpty(), migrated)
    }
}
