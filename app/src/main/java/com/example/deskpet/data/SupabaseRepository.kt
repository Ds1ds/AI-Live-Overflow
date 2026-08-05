package com.example.deskpet.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Supabase 同步层 —— 对接 pet_state / pet_config / messages 三张表。
 * REST API + publishable key（配合 public_all RLS 策略）读写数据。
 */
object SupabaseRepository {

    private const val TAG = "SupabaseRepo"
    private const val BASE_URL = "https://itejjdphpohoszhtzizc.supabase.co"
    private const val API_KEY = "sb_publishable_la9nsrznzY8zu9vXcLfEFQ_12deogpr"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor()

    /** 本地缓存的宠物状态，与云端 pet_state 行一一对应 */
    data class PetState(
        var heat: Int = 50,
        var valence: Double = 0.0,
        var arousal: Double = 0.0,
        var mood: String = "normal"
    ) {
        fun toJson(deviceId: String): JSONObject = JSONObject()
            .put("device_id", deviceId)
            .put("heat", heat.coerceIn(0, 100))
            .put("valence", valence)
            .put("arousal", arousal)
            .put("mood", mood)
    }

    /** 生成/读取本机稳定 device_id（Android ID 派生） */
    fun getDeviceId(context: Context): String {
        val sp = context.getSharedPreferences("pet_sync", Context.MODE_PRIVATE)
        sp.getString("device_id", null)?.let { return it }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
        val id = "android-$androidId"
        sp.edit().putString("device_id", id).apply()
        return id
    }

    /** 拉取云端宠物状态；无记录时返回 null */
    fun fetchPetState(deviceId: String, onResult: (PetState?) -> Unit) {
        executor.execute {
            try {
                val url = "$BASE_URL/rest/v1/pet_state?device_id=eq.$deviceId&select=*"
                val req = Request.Builder().url(url)
                    .addHeader("apikey", API_KEY)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        if (arr.length() > 0) {
                            val o = arr.getJSONObject(0)
                            onResult(
                                PetState(
                                    heat = o.optInt("heat", 50),
                                    valence = o.optDouble("valence", 0.0),
                                    arousal = o.optDouble("arousal", 0.0),
                                    mood = o.optString("mood", "normal")
                                )
                            )
                        } else {
                            onResult(null)
                        }
                    } else {
                        Log.w(TAG, "fetchPetState http=${resp.code}")
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchPetState failed", e)
                onResult(null)
            }
        }
    }

    /** 以 device_id 为主键 upsert 宠物状态（Prefer: merge-duplicates） */
    fun upsertPetState(deviceId: String, state: PetState) {
        executor.execute {
            try {
                val body = state.toJson(deviceId).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$BASE_URL/rest/v1/pet_state?on_conflict=device_id")
                    .post(body)
                    .addHeader("apikey", API_KEY)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Prefer", "resolution=merge-duplicates")
                    .build()
                client.newCall(req).execute().close()
            } catch (e: Exception) {
                Log.e(TAG, "upsertPetState failed", e)
            }
        }
    }

    /** 读取一条配置（value 为 jsonb，返回原始 JSON 字符串） */
    fun fetchConfig(key: String, onResult: (String?) -> Unit) {
        executor.execute {
            try {
                val url = "$BASE_URL/rest/v1/pet_config?key=eq.$key&select=value"
                val req = Request.Builder().url(url)
                    .addHeader("apikey", API_KEY)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        onResult(if (arr.length() > 0) arr.getJSONObject(0).optString("value") else null)
                    } else {
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchConfig failed", e)
                onResult(null)
            }
        }
    }

    /** 写入一条消息（direction: in/out/self） */
    fun sendMessage(deviceId: String, direction: String, content: String, bubbleType: String = "normal") {
        executor.execute {
            try {
                val body = JSONObject()
                    .put("device_id", deviceId)
                    .put("direction", direction)
                    .put("content", content)
                    .put("bubble_type", bubbleType)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$BASE_URL/rest/v1/messages")
                    .post(body)
                    .addHeader("apikey", API_KEY)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Prefer", "return=minimal")
                    .build()
                client.newCall(req).execute().close()
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed", e)
            }
        }
    }

    /** 拉取最近消息，按时间正序返回 [(direction, content)] */
    fun fetchMessages(deviceId: String, limit: Int = 10, onResult: (List<Pair<String, String>>) -> Unit) {
        executor.execute {
            try {
                val url = "$BASE_URL/rest/v1/messages" +
                    "?device_id=eq.$deviceId&order=created_at.desc&limit=$limit" +
                    "&select=direction,content,bubble_type,created_at"
                val req = Request.Builder().url(url)
                    .addHeader("apikey", API_KEY)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        val list = mutableListOf<Pair<String, String>>()
                        for (i in arr.length() - 1 downTo 0) {
                            val o = arr.getJSONObject(i)
                            list.add(o.optString("direction", "self") to o.optString("content", ""))
                        }
                        onResult(list)
                    } else {
                        onResult(emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchMessages failed", e)
                onResult(emptyList())
            }
        }
    }
}