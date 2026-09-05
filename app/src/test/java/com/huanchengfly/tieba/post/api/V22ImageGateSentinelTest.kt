package com.huanchengfly.tieba.post.api

import com.huanchengfly.tieba.post.api.models.protos.PbContent
import com.huanchengfly.tieba.post.api.models.protos.SubPostList
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V22 楼中楼图片门控哨兵的纯函数单测。
 *
 * "纯占位" = content 里有字面 "[图片]" 文本(type 0)且不含任何图片类型(3/20)。
 * 误判代价不对称:漏报只是少一条日志(真机反馈会兜底);误报会让日志噪音
 * 淹没真信号,因此边界以"宁可漏报"为准。
 */
class V22ImageGateSentinelTest {

    private fun text(s: String) = PbContent(type = 0, text = s)
    private fun image() = PbContent(type = 3, bsize = "100,100", originSrc = "https://x/p.jpg")
    private fun sub(vararg contents: PbContent) = SubPostList(content = contents.toList())

    @Test
    fun purePlaceholderIsCounted() {
        val subs = listOf(sub(text("层主"), text("[图片]")))
        assertEquals(1, V22ImageGateSentinel.placeholderSubPostCount(subs))
    }

    @Test
    fun realImageContentNotCounted() {
        // 正常放图:文本 + 图片类型内容,不算占位
        val subs = listOf(sub(text("看图"), image()))
        assertEquals(0, V22ImageGateSentinel.placeholderSubPostCount(subs))
    }

    @Test
    fun placeholderWithImageTypeNotCounted() {
        // 混排里带占位字样但同时有图片内容 → 有图可渲染,不计(防误报)
        val subs = listOf(sub(text("[图片]"), image()))
        assertEquals(0, V22ImageGateSentinel.placeholderSubPostCount(subs))
    }

    @Test
    fun plainTextSubPostNotCounted() {
        val subs = listOf(sub(text("纯文字楼中楼")))
        assertEquals(0, V22ImageGateSentinel.placeholderSubPostCount(subs))
    }

    @Test
    fun multiplePlaceholderSubPostsAllCounted() {
        val subs = listOf(
            sub(text("[图片]")),
            sub(text("[图片]"), text("补充")),
            sub(text("正常")),
            image().let { sub(it) },
        )
        assertEquals(2, V22ImageGateSentinel.placeholderSubPostCount(subs))
    }

    @Test
    fun emptyInputIsZero() {
        assertEquals(0, V22ImageGateSentinel.placeholderSubPostCount(emptyList()))
    }
}
