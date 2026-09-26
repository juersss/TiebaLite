package com.huanchengfly.tieba.post.ui.page.hottopic.detail

import com.huanchengfly.tieba.post.api.models.Author
import com.huanchengfly.tieba.post.api.models.Agree
import com.huanchengfly.tieba.post.api.models.MediaNumBean
import com.huanchengfly.tieba.post.api.models.ThreadBean
import com.huanchengfly.tieba.post.api.models.ThreadInfoBean
import com.huanchengfly.tieba.post.api.models.TopicDetailBean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 话题页置顶/特殊内容提取(外部审查-8)。
 *
 * 话题详情为未完成的预览功能([TopicDetailBean.specialTopic] 的真实报文
 * 形状未经验证),因此这里对捕获的 JsonElement 做多形状、逐元素的防御性解析:
 * 任何一层形状不符/字段缺失都跳过该元素或整体降级为空列表,绝不影响话题页
 * 主列表的解析与渲染。与上游注释掉的强类型 `List<SpecialTopicBean>` 建模相比,
 * 本方案在形状正确时能展示置顶帖,形状不符时退化为"不展示"(与修复前行为
 * 一致),不会崩页面。
 *
 * 元素解析两级策略:先按完整 [ThreadInfoBean] DTO(与 relate_thread 同族,
 * 信息完整可正常渲染卡片);失败再按宽松载荷(仅要求 tid/id,其余字段空缺)
 * 兜底,保证"至少能点进去看"。
 *
 * 兼容的承载形状(按上游 DTO 的建模假设排序):
 * 1. `special_topic: [ { title, thread_list: [...] }, ... ]`
 * 2. `special_topic: { title, thread_list: [...] }`(单对象变体)
 */
object TopicDetailPinned {

    /** 宽松解析:未知键忽略、错型兜底默认值,与主解析器口径一致 */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * 从 special_topic 的 JsonElement 提取置顶帖,按服务端顺序返回并按
     * threadId 去重;调用方需再对主列表按 threadId 去重,避免重复渲染。
     */
    fun extract(specialTopic: JsonElement?): List<ThreadBean> {
        if (specialTopic == null) return emptyList()
        val threadElements: List<JsonElement> = when (specialTopic) {
            is JsonArray ->
                // 形状 1:逐个 SpecialTopicBean 展开;元素失败跳过
                specialTopic.flatMap { element ->
                    runCatching {
                        lenientJson.decodeFromJsonElement<SpecialTopicPayload>(element).threadList
                    }.getOrDefault(emptyList())
                }

            is JsonObject ->
                // 形状 2:单对象取 thread_list
                (specialTopic["thread_list"] as? JsonArray) ?: emptyList()

            else -> emptyList()
        }
        return threadElements
            .mapNotNull(::parseThreadElement)
            .distinctBy { it.threadInfo.threadId }
    }

    private fun parseThreadElement(element: JsonElement): ThreadBean? {
        // 第一优先:完整 DTO,与主列表卡片同一渲染路径
        runCatching { lenientJson.decodeFromJsonElement<ThreadInfoBean>(element) }
            .getOrNull()
            ?.let { return ThreadBean(feedId = it.feedId, source = 0, threadInfo = it, userAgree = it.userAgree) }
        // 兜底:宽松载荷,只保证 tid/id 与标题可得
        val loose = runCatching {
            lenientJson.decodeFromJsonElement<LooseThreadPayload>(element)
        }.getOrNull() ?: return null
        return runCatching { loose.toThreadBean() }.getOrNull()
    }

    /** 与上游 SpecialTopicBean 对齐的宽松载荷(title 允许缺失) */
    @kotlinx.serialization.Serializable
    private data class SpecialTopicPayload(
        val title: String? = null,
        @kotlinx.serialization.SerialName("thread_list")
        val threadList: List<JsonElement> = emptyList(),
    )

    /** 最小可用载荷:仅要求能确定 threadId */
    @kotlinx.serialization.Serializable
    private data class LooseThreadPayload(
        @kotlinx.serialization.SerialName("tid")
        val threadId: Long = 0L,
        val id: Long = 0L,
        @kotlinx.serialization.SerialName("feed_id")
        val feedId: Long = 0L,
        val title: String? = null,
        @kotlinx.serialization.SerialName("forum_name")
        val forumName: String? = null,
        @kotlinx.serialization.SerialName("forum_id")
        val forumId: Long = 0L,
    ) {
        fun toThreadBean(): ThreadBean {
            val resolvedThreadId = threadId.takeIf { it != 0L } ?: id.takeIf { it != 0L }
            checkNotNull(resolvedThreadId) { "缺少 thread_id" }
            return ThreadBean(
                feedId = feedId.takeIf { it != 0L } ?: resolvedThreadId,
                source = 0,
                threadInfo = ThreadInfoBean(
                    id = id,
                    feedId = feedId.takeIf { it != 0L } ?: resolvedThreadId,
                    avatar = "",
                    title = title ?: "",
                    threadId = resolvedThreadId,
                    forumId = forumId,
                    forumName = forumName ?: "",
                    createTime = 0L,
                    lastTime = "",
                    lastTimeInt = 0L,
                    abstractText = "",
                    media = emptyList(),
                    mediaNum = MediaNumBean(pic = 0),
                    agreeNum = 0,
                    replyNum = 0,
                    shareNum = 0L,
                    userId = 0L,
                    firstPostId = 0L,
                    userAgree = 0,
                    author = Author(name = null, id = 0L, showNickName = "", nameShow = "", portrait = ""),
                    agree = Agree(agreeNum = 0, agreeType = 0, hasAgree = 0),
                ),
                userAgree = 0,
            )
        }
    }
}
