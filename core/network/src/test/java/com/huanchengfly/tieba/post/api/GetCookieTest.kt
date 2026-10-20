package com.huanchengfly.tieba.post.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Cookie` 头拼装契约（2026-10-20）。
 *
 * 回归点：分隔符曾是 `:`（`CUID:xxx; ka:open`），不是 RFC 6265 的 `cookie-pair`
 * （`name=value`，"`; `" 分隔）——四个用它拼头的客户端实际发出的 Cookie 无法被解析，
 * 等于没带 CUID/ka/TBBRAND。
 */
class GetCookieTest {

    @Test
    fun pairsUseEqualsAndSemicolonSpace() {
        val cookie = getCookie(
            "CUID" to { "abc123" },
            "ka" to { "open" },
            "TBBRAND" to { "25060RK16C" },
        )
        assertEquals("CUID=abc123; ka=open; TBBRAND=25060RK16C", cookie)
    }

    /** 空值/空串项必须整段丢弃，不能产出 `name=`（空值的 Cookie 会被服务端当成有值）。 */
    @Test
    fun emptyValuesAreDropped() {
        val cookie = getCookie(
            "ka" to { "open" },
            "BDUSS" to { null },
            "STOKEN" to { "" },
        )
        assertEquals("ka=open", cookie)
    }

    @Test
    fun allEmptyYieldsEmptyHeader() {
        assertEquals("", getCookie("BDUSS" to { null }))
    }

    /** 取值函数在**拼装时**才调用（延迟求值）：调用方依赖它拿请求时刻的账号/设备标识。 */
    @Test
    fun valueProvidersAreEvaluatedLazily() {
        var calls = 0
        val cookie = getCookie("ZID" to { calls++; "z1" })
        assertEquals(1, calls)
        assertEquals("ZID=z1", cookie)
    }
}
