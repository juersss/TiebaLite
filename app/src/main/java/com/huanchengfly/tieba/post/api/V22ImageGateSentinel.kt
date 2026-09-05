package com.huanchengfly.tieba.post.api

import com.huanchengfly.tieba.post.api.models.protos.PbContent
import com.huanchengfly.tieba.post.api.models.protos.SubPostList

/**
 * V22 楼中楼图片门控哨兵(log-only)。
 *
 * 背景:pb page/floor 接口以 [com.huanchengfly.tieba.post.api.ClientVersion.TIEBA_V22]
 * 身份请求,服务端只对 >=22.8.5.0 下发光楼中楼的真实图片内容(type 3/20)。若百度日后
 * 对 22.x 也关闸,症状是楼中楼图片退回字面文本"[图片]"占位。
 *
 * 本哨兵只做取证不做处置:自动降级会牵动全部 pb 请求的版本身份、且降级后也未必能出图,
 * 风险大于收益。检测到占位时打一条 WARNING,运维路径保持人工处置流程:
 * 重跑版本探测(伪装不同 _client_version 看 content type)→ 更新 TIEBA_V22 版本字符串。
 */
object V22ImageGateSentinel {

    /** 服务端关闸后楼中楼图片位置下发的字面占位文本 */
    private const val PLACEHOLDER = "[图片]"

    /** 图片内容类型(渲染链见 Extensions.kt 的 renders:3/20 → PicContentRender) */
    private val IMAGE_TYPES = setOf(3, 20)

    /**
     * 统计"纯占位"楼中楼条数:content 含字面 "[图片]" 文本且不含任何图片类型内容。
     * 有图又带占位文本的不计(那是正常混排)。
     */
    fun placeholderSubPostCount(subPosts: List<SubPostList>): Int =
        subPosts.count { sub ->
            val content: List<PbContent> = sub.content
            content.any { it.type == 0 && it.text == PLACEHOLDER } &&
                content.none { it.type in IMAGE_TYPES }
        }

    /** 命中时给出一条可直接指导处置的日志;返回命中数供测试与调用方复用 */
    fun report(endpoint: String, subPosts: List<SubPostList>): Int {
        val n = placeholderSubPostCount(subPosts)
        if (n > 0) {
            android.util.Log.w(
                "V22Gate",
                "$endpoint: $n 条楼中楼仅收到'$PLACEHOLDER'占位、无图片内容——" +
                    "V22 门控可能已关。处置:重跑版本探测(伪装不同 _client_version 打 pb/floor " +
                    "看 content type),更新 Enums.kt 的 TIEBA_V22 版本字符串。"
            )
        }
        return n
    }
}
