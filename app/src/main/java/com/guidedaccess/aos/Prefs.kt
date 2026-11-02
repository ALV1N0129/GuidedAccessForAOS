package com.guidedaccess.aos

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs

/**
 * 앱별 사용법 유도 설정. iOS 처럼 앱마다 영역/옵션을 기억한다.
 * [areas] 는 화면 크기에 대한 비율(0..1) 좌표로 저장한다.
 */
data class AppConfig(
    val areas: MutableList<RectF> = mutableListOf(),
    var touchEnabled: Boolean = true,
    var volumeEnabled: Boolean = false,
    var timeLimitMinutes: Int = 0,
) {
    fun copyDeep() = AppConfig(areas.map { RectF(it) }.toMutableList(), touchEnabled, volumeEnabled, timeLimitMinutes)

    fun toJson(): String = JSONObject().apply {
        put("touch", touchEnabled)
        put("volume", volumeEnabled)
        put("time", timeLimitMinutes)
        put("areas", JSONArray().apply {
            areas.forEach { r ->
                put(JSONArray().apply {
                    put(r.left.toDouble()); put(r.top.toDouble())
                    put(r.right.toDouble()); put(r.bottom.toDouble())
                })
            }
        })
    }.toString()

    companion object {
        val TIME_LIMIT_OPTIONS = intArrayOf(0, 1, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180)

        fun fromJson(json: String?): AppConfig {
            if (json == null) return AppConfig()
            return try {
                val o = JSONObject(json)
                val list = mutableListOf<RectF>()
                val arr = o.optJSONArray("areas") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    list += RectF(
                        a.getDouble(0).toFloat(), a.getDouble(1).toFloat(),
                        a.getDouble(2).toFloat(), a.getDouble(3).toFloat(),
                    )
                }
                AppConfig(list, o.optBoolean("touch", true), o.optBoolean("volume", false), o.optInt("time", 0))
            } catch (e: Exception) {
                AppConfig()
            }
        }

        fun formatMinutes(ctx: Context, minutes: Int): String = when {
            minutes <= 0 -> ctx.getString(R.string.time_none)
            minutes < 60 -> ctx.getString(R.string.time_minutes, minutes)
            minutes % 60 == 0 -> ctx.getString(R.string.time_hours, minutes / 60)
            else -> ctx.getString(R.string.time_hours_minutes, minutes / 60, minutes % 60)
        }
    }
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("guided_access", Context.MODE_PRIVATE)

    // ---- 암호 ----
    fun hasPin() = sp.contains(KEY_PIN_HASH)

    val pinLength: Int get() = sp.getInt(KEY_PIN_LEN, 4)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, hash(salt, pin))
            .putInt(KEY_PIN_LEN, pin.length)
            .commit()
    }

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString(KEY_PIN_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val stored = sp.getString(KEY_PIN_HASH, null) ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: ByteArray, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var data = salt + pin.toByteArray()
        repeat(10_000) { data = md.digest(data) }
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    // ---- 전역 설정 ----
    var tripleClickEnabled: Boolean
        get() = sp.getBoolean("triple_click", true)
        set(v) { sp.edit().putBoolean("triple_click", v).apply() }

    var keepAfterReboot: Boolean
        get() = sp.getBoolean("keep_after_reboot", false)
        set(v) { sp.edit().putBoolean("keep_after_reboot", v).apply() }

    var strongLockEnabled: Boolean
        get() = sp.getBoolean("strong_lock", true)
        set(v) { sp.edit().putBoolean("strong_lock", v).apply() }

    // ---- 진행 중인 세션 (서비스가 재연결되면 복원) ----
    val activeSessionPackage: String? get() = sp.getString("session_pkg", null)

    /** 세션이 시작된 부팅과 지금 부팅이 같은지 확인한다. */
    fun sessionFromThisBoot(): Boolean = abs(sp.getLong("session_boot", 0) - currentBootTime()) < 10_000

    fun saveSession(pkg: String?) {
        sp.edit().apply {
            if (pkg == null) remove("session_pkg").remove("session_boot").remove("session_end")
            else putString("session_pkg", pkg).putLong("session_boot", currentBootTime())
        }.commit()
    }

    var sessionEndAt: Long
        get() = sp.getLong("session_end", 0)
        set(v) { sp.edit().putLong("session_end", v).apply() }

    // ---- 앱별 설정 ----
    fun loadConfig(pkg: String): AppConfig = AppConfig.fromJson(sp.getString("app_$pkg", null))

    fun saveConfig(pkg: String, cfg: AppConfig) {
        sp.edit().putString("app_$pkg", cfg.toJson()).apply()
    }

    private fun currentBootTime() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_LEN = "pin_len"
    }
}
