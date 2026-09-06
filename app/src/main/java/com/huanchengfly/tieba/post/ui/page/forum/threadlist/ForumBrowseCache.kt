package com.huanchengfly.tieba.post.ui.page.forum.threadlist

/**
 * 吧列表滚动锚点的会话级暂存(修复"从帖子页返回时偶发回到顶部")。
 *
 * 病根(trace_20260907_030646.log 行 909 实证):从帖子页返回,rememberSaveable
 * 恢复了滚动索引,但列表数据晚一帧才到——空列表的首次测量把位置钳回顶部
 * (27/1065 → 0),数据落地后停在顶部,恢复即失效。
 *
 * 对策:离开吧页时把锚点(锚点帖 threadId + 像素偏移)暂存于此;回到吧页且列表
 * 数据落地后,按锚点帖重新定位(与索引数字无关,不受空帧影响),消费一次即失效。
 *
 * 行为约定(2026-09-07 用户拍板):
 * - 返回(导航栈存活、ViewModel 存活)→ 恢复位置;
 * - 重新进入(FirstLoad 走网络)= 新的一次浏览,归零——页面在发起 FirstLoad 时
 *   会丢弃暂存锚点。
 *
 * 仅"最新"列表参与恢复;精品区/换排序为"重新开始"语义。仅进程内存,不落盘。
 */
object ForumBrowseCache {

    data class Anchor(val key: Long, val offset: Int)

    private val pending = HashMap<String, Anchor>()

    /** 锚点键:吧名 + 区(最新/精品) + 排序,不同组合互不串位 */
    fun key(forumName: String, isGood: Boolean, sortType: Int): String =
        "$forumName|$isGood|$sortType"

    /** 离开吧页时登记锚点(页面 DisposableEffect 调用;anchor 为 null 表示离开时在顶部) */
    fun markPendingRestore(key: String, anchor: Anchor?) {
        if (anchor == null) return
        synchronized(this) { pending[key] = anchor }
    }

    /** 数据落地后取走待恢复锚点;取走即失效,只恢复一次(重新进入的归零语义靠丢弃实现) */
    fun consumeRestoreAnchor(key: String): Anchor? {
        synchronized(this) { return pending.remove(key) }
    }
}
