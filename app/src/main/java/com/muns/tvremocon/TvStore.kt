package com.muns.tvremocon

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * TV별 저장 데이터 (ARCHITECTURE.md 4절).
 * 키: TV id ("uuid:<값>" 또는 수동 입력 시 "ip:<IP>")
 * 값: {ip, name, model, uuid, key(client-key), cert(인증서 SHA-256)}
 */
class TvStore(context: Context) {

    private val prefs = context.getSharedPreferences("tv_store", Context.MODE_PRIVATE)
    private val lock = Any()

    private fun load(): JSONObject = try {
        JSONObject(prefs.getString("tvs", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun save(all: JSONObject) {
        prefs.edit().putString("tvs", all.toString()).apply()
    }

    fun get(id: String): JSONObject? = synchronized(lock) { load().optJSONObject(id) }

    fun findByIp(ip: String): String? = synchronized(lock) {
        val all = load()
        all.keys().asSequence().firstOrNull { all.optJSONObject(it)?.optString("ip") == ip }
    }

    /** 수동 입력 IP → 기존 TV가 있으면 그 id, 없으면 "ip:<IP>" 항목 생성 */
    fun ensureManual(ip: String): String = synchronized(lock) {
        findByIp(ip) ?: run {
            val id = "ip:$ip"
            val all = load()
            all.put(id, JSONObject().put("ip", ip).put("name", "").put("model", "").put("uuid", ""))
            save(all)
            id
        }
    }

    /**
     * SSDP로 찾은 TV 반영. IP가 바뀌었으면 갱신하고,
     * 같은 IP로 수동 등록된 "ip:" 항목이 있으면 키·인증서를 uuid 항목으로 옮긴다.
     * @return 이미 키가 있는(연결해 본) TV인지
     */
    fun upsertFound(tv: TvInfo): Boolean = synchronized(lock) {
        val all = load()
        val cur = all.optJSONObject(tv.id) ?: JSONObject()
        if (tv.id.startsWith("uuid:")) {
            val manualId = "ip:${tv.ip}"
            val manual = all.optJSONObject(manualId)
            if (manual != null) {
                if (cur.optString("key").isEmpty()) cur.put("key", manual.optString("key"))
                if (cur.optString("cert").isEmpty()) cur.put("cert", manual.optString("cert"))
                all.remove(manualId)
                if (prefs.getString("last", "") == manualId) prefs.edit().putString("last", tv.id).apply()
            }
        }
        cur.put("ip", tv.ip)
        if (tv.name.isNotEmpty()) cur.put("name", tv.name)
        if (tv.model.isNotEmpty()) cur.put("model", tv.model)
        if (tv.uuid.isNotEmpty()) cur.put("uuid", tv.uuid)
        all.put(tv.id, cur)
        save(all)
        cur.optString("key").isNotEmpty()
    }

    private fun setField(id: String, field: String, value: String?) {
        synchronized(lock) {
            val all = load()
            val cur = all.optJSONObject(id) ?: return
            if (value.isNullOrEmpty()) cur.remove(field) else cur.put(field, value)
            all.put(id, cur)
            save(all)
        }
    }

    fun setKey(id: String, key: String?) {
        setField(id, "key", key)
    }

    fun setCert(id: String, cert: String?) {
        setField(id, "cert", cert)
    }

    fun forget(id: String) {
        synchronized(lock) {
            val all = load()
            all.remove(id)
            save(all)
            if (prefs.getString("last", "") == id) prefs.edit().remove("last").apply()
        }
    }

    fun setLast(id: String) {
        prefs.edit().putString("last", id).apply()
    }

    fun last(): String = prefs.getString("last", "") ?: ""

    /** 화면용 목록 (키·인증서 값은 내보내지 않음) */
    fun listForUi(): JSONArray = synchronized(lock) {
        val all = load()
        val arr = JSONArray()
        all.keys().forEach { id ->
            val t = all.optJSONObject(id) ?: return@forEach
            arr.put(
                JSONObject().put("id", id).put("ip", t.optString("ip"))
                    .put("name", t.optString("name")).put("model", t.optString("model"))
                    .put("known", t.optString("key").isNotEmpty())
            )
        }
        arr
    }
}
