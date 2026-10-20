package com.huanchengfly.tieba.post.api

import com.huanchengfly.tieba.post.api.session.SessionProviders
import android.os.Build


private val defaultUserAgent: String =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/135.0.0.0 Mobile Safari/537.36"

fun getUserAgent(appendString: String? = null): String {
    val append = " ${appendString?.trim()}".takeIf { !appendString.isNullOrEmpty() }.orEmpty()
    return "${SessionProviders.clientConfig.userAgent ?: defaultUserAgent}$append"
}

/**
 * 拼装 `Cookie` 请求头：`name=value; name2=value2`。
 *
 * 2026-10-20 修正：此前分隔符用的是 `:`（`name:value`），不是 RFC 6265 的 `cookie-pair`
 * 语法——四个用它拼 Cookie 的客户端（AppHybrid / V12 / V22 / POST）实际发出去的是浏览器与
 * 服务端都解析不了的字符串，等价于**没带** CUID/ka/TBBRAND。空值项照旧丢弃（不产出 `name=`）。
 */
fun getCookie(vararg cookies: Pair<String, () -> String?>): String {
    return cookies.map { it.first to it.second() }.filterNot { it.second.isNullOrEmpty() }
        .joinToString("; ") { "${it.first}=${it.second}" }
}

fun getScreenHeight(): Int = SessionProviders.deviceInfo.exactScreenHeight

fun getScreenWidth(): Int = SessionProviders.deviceInfo.exactScreenWidth

fun Boolean.booleanToString(): String = if (this) "1" else "0"
