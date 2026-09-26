package com.huanchengfly.tieba.post.api.params

import android.annotation.SuppressLint
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import com.huanchengfly.tieba.post.api.session.SessionProviders
import com.huanchengfly.tieba.post.core.common.toMD5
import com.huanchengfly.tieba.post.core.common.helios.Base32
import com.huanchengfly.tieba.post.core.common.helios.Hasher
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object UIDUtil {
    @get:SuppressLint("HardwareIds")
    val androidId: String
        get() = getAndroidId("")

    @SuppressLint("HardwareIds")
    fun getAndroidId(defaultValue: String): String {
        val androidId =
            Settings.Secure.getString(SessionProviders.appContext.contentResolver, Settings.Secure.ANDROID_ID)
        return androidId ?: defaultValue
    }

    fun getOAID(): String {
        if (SessionProviders.oaid.encodedOAID.isBlank()) return ""
        val raw = "A10-${SessionProviders.oaid.encodedOAID}-"
        val sign = Base32.encode(Hasher.hash(raw.toByteArray()))
        return "$raw$sign"
    }

    fun getAid(): String {
        val raw = "com.helios" + getAndroidId("000000000") + uUID
        val bytes = getSHA1(raw)
        val encoded = Base32.encode(bytes)
        val rawAid = "A00-$encoded-"
        val sign = Base32.encode(Hasher.hash(rawAid.toByteArray()))
        return "$rawAid$sign"
    }

    private fun getSHA1(str: String): ByteArray {
        var sha1: ByteArray = "".toByteArray()
        try {
            val digest = MessageDigest.getInstance("SHA1")
            sha1 = digest.digest(str.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sha1
    }

    val newCUID: String
        get() = "baidutiebaapp$uUID"

    val cUID: String
        get() {
            val androidId = androidId
            var imei = MobileInfoUtil.getIMEI(SessionProviders.appContext)
            if (TextUtils.isEmpty(imei)) {
                imei = "0"
            }
            val raw =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) imei + androidId + uUID else "com.baidu$androidId"
            return raw.toMD5().uppercase()
        }

    val finalCUID: String
        get() {
            var imei = MobileInfoUtil.getIMEI(SessionProviders.appContext)
            if (TextUtils.isEmpty(imei)) {
                imei = "0"
            }
            return cUID + "|" + StringBuffer(imei).reverse()
        }

    /**
     * 设备级 UUID。原先直接读写 app 的 SharedPreferences(`app_data`/`uuid`)，
     * 3b-prep-4（2026-09-17）改为走 [com.huanchengfly.tieba.post.api.session.DeviceInfoProvider]：
     * 首次取值仍由 app 侧实现生成并落盘，**落盘名与键不变**（`app_data` / `uuid`）。
     */
    val uUID: String
        get() = SessionProviders.deviceInfo.getOrCreateUuid()
}