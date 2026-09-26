package com.huanchengfly.tieba.post.api.internal

import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * api 层需要的编码工具。
 *
 * 结构大改 3b-prep-2（2026-09-17）：原先 api 直接调 app 的 `CacheUtil.base64Encode` 与
 * `ImageUtil.imageToBase64`，这两个类都带 app 私有依赖（CacheUtil 碰文件缓存、ImageUtil 碰 UI），
 * 搬不走。这里把**两个纯编码实现逐字搬出来**，app 侧原方法保留为同名转发（调用点零改动）。
 *
 * 实现不得改写：`base64Encode` 的输出是请求体签名的一部分；`imageToBase64` 用于发图。
 */
object ApiEncoding {

    /** 与 `CacheUtil.base64Encode` 逐字等价（UTF-8 + `Base64.DEFAULT`） */
    @JvmStatic
    fun base64Encode(s: String): String =
        Base64.encodeToString(s.toByteArray(StandardCharsets.UTF_8), Base64.DEFAULT)

    /** 与 `CacheUtil.base64Decode` 逐字等价 */
    @JvmStatic
    fun base64Decode(s: String): String =
        String(Base64.decode(s.toByteArray(StandardCharsets.UTF_8), Base64.DEFAULT), StandardCharsets.UTF_8)

    /** 与 `ImageUtil.imageToBase64(InputStream?)` 逐字等价（失败返回 null，不抛） */
    @JvmStatic
    fun imageToBase64(inputStream: InputStream?): String? {
        if (inputStream == null) return null
        return runCatching { inputStream.use { Base64.encodeToString(it.readBytes(), Base64.DEFAULT) } }
            .getOrNull()
    }

    /** 与 `ImageUtil.imageToBase64(File?)` 逐字等价 */
    @JvmStatic
    fun imageToBase64(file: File?): String? {
        if (file == null) return null
        return try {
            FileInputStream(file).use { imageToBase64(it) }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
}
