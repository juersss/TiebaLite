package com.huanchengfly.tieba.post.core.network.model.protos

/**
 * protobuf 模型的**纯数据**扩展（零 UI / 零 app 依赖）。
 *
 * 结构大改 Phase 3b-prep-1（2026-09-17）：原先这里混着渲染侧扩展（`renders`/`plainText`/
 * `contentRenders`/`getContentText`…），它们依赖 UI、主题、图片与 app 的 `R`，让整个 api 包
 * 没法脱离 app 成模块。渲染侧已整体挪到 `com.huanchengfly.tieba.post.ui.common.PbContentRenderExtensions.kt`；
 * 本文件只留真·数据扩展，**本文件对 app 零依赖**（改这里别再引 UI/工具类）。
 */

val List<Abstract>.abstractText: String
    get() = joinToString(separator = "") {
        when (it.type) {
            0 -> it.text.replace(Regex(" {2,}"), " ")
            4 -> it.text

            else -> ""
        }
    }

val ThreadInfo.hasAgree: Int
    get() = agree?.hasAgree ?: 0
val ThreadInfo.hasAbstract: Boolean
    get() = richAbstract.any { (it.type == 0 && it.text.isNotBlank()) || it.type == 2 }

// 赞踩状态机改造:旧的 updateAgreeStatus 系列"直接改写 proto 计数字段"的扩展
// 已全部删除——计数永不被修改、±1 由差分公式自然得出,是消灭计数漂移整类 bug 的前提。
// 记录更新唯一入口:OpRecordStore(setPending/confirm/revertPending/rebase)。

fun ThreadInfo.updateCollectStatus(
    newStatus: Int,
    markPostId: Long
) = if (collectStatus != newStatus) {
    this.copy(
        collectStatus = newStatus,
        collectMarkPid = markPostId.toString()
    )
} else {
    this
}

val User.bawuType: String?
    get() = if (is_bawu == 1) {
        if (bawu_type == "manager") "吧主" else "小吧主"
    } else null

/**
 * pb 响应的 post 不下发 from_forum(实测 pb/page 与 pb/floor 均缺失),
 * 而图片的 PhotoViewData 绑定需要 from_forum/tid 作为大图上下文,
 * 这里用同一响应内的吧信息补齐,缺省时原样返回
 */
fun Post.withForumFallback(forum: SimpleForum?): Post =
    if (from_forum == null && forum != null) copy(from_forum = forum) else this
