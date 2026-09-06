package com.huanchengfly.tieba.post.ui.page.hottopic.detail

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部审查-8:话题页置顶/特殊内容提取的防御性回归。
 * 真实报文形状未经验证,提取必须满足:
 * 形状正确时能取出置顶帖;任何形状不符/字段缺失时安全降级为空,绝不抛异常。
 */
class TopicDetailPinnedTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun extract_nullReturnsEmpty() {
        assertTrue(TopicDetailPinned.extract(null).isEmpty())
    }

    @Test
    fun extract_arrayShape_returnsPinnedThreads() {
        // 形状 1(上游 DTO 建模假设):[{ title, thread_list: [...] }]
        val element = json.parseToJsonElement(
            """
            [{"title":"本周热帖","thread_list":[
                {"tid":111,"feed_id":9001,"title":"置顶帖一","forum_name":"吧一","forum_id":7},
                {"tid":222,"feed_id":9002,"title":"置顶帖二"}
            ]}]
            """.trimIndent()
        )
        val pinned = TopicDetailPinned.extract(element)

        assertEquals(2, pinned.size)
        assertEquals(111L, pinned[0].threadInfo.threadId)
        assertEquals(9001L, pinned[0].feedId)
        assertEquals("置顶帖一", pinned[0].threadInfo.title)
        assertEquals(222L, pinned[1].threadInfo.threadId)
    }

    @Test
    fun extract_objectShape_returnsPinnedThreads() {
        // 形状 2:单对象 { title, thread_list: [...] }
        val element = json.parseToJsonElement(
            """
            {"title":"公告","thread_list":[{"tid":333,"title":"唯一置顶"}]}
            """.trimIndent()
        )
        val pinned = TopicDetailPinned.extract(element)

        assertEquals(1, pinned.size)
        assertEquals(333L, pinned[0].threadInfo.threadId)
    }

    @Test
    fun extract_malformedElementsAreSkipped() {
        // 缺 thread_id 的元素跳过;完整 ThreadInfoBean 形状优先解析
        val element = json.parseToJsonElement(
            """
            {"thread_list":[
                {"title":"缺 tid"},
                {"tid":444,"title":"合法置顶","forum_name":"吧","forum_id":7,
                 "author":{"name":"u","id":1,"show_nickname":"u","name_show":"U","portrait":"x"},
                 "agree":{"agree_num":1,"agree_type":0,"has_agree":0},
                 "media":[],"media_num":{"pic":0}}
            ]}
            """.trimIndent()
        )
        val pinned = TopicDetailPinned.extract(element)

        assertEquals(1, pinned.size)
        assertEquals(444L, pinned[0].threadInfo.threadId)
    }

    @Test
    fun extract_deduplicatesByThreadId() {
        val element = json.parseToJsonElement(
            """
            [{"thread_list":[{"tid":555,"title":"A"}]},
             {"thread_list":[{"tid":555,"title":"A 副本"},{"tid":666,"title":"B"}]}]
            """.trimIndent()
        )
        val pinned = TopicDetailPinned.extract(element)

        assertEquals(2, pinned.size)
        assertEquals(listOf(555L, 666L), pinned.map { it.threadInfo.threadId })
    }

    @Test
    fun extract_completelyUnexpectedShapeReturnsEmptyInsteadOfThrowing() {
        // 形状不符(数字/字符串/空对象)必须降级为空,不影响话题页主列表
        assertTrue(TopicDetailPinned.extract(json.parseToJsonElement("123")).isEmpty())
        assertTrue(TopicDetailPinned.extract(json.parseToJsonElement("\"x\"")).isEmpty())
        assertTrue(TopicDetailPinned.extract(json.parseToJsonElement("{}")).isEmpty())
        assertTrue(
            TopicDetailPinned.extract(json.parseToJsonElement("{\"unknown_field\":[1,2,3]}")).isEmpty()
        )
    }
}
