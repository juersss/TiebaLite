package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.core.network.model.protos.GeneralTabList.GeneralTabListResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.core.data.appPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

object GeneralTabListRepository {
    /**
     * 与 [FrsPageRepository] 同源的会话级缓存:替代此前 `lastHash` / `lastResponse`
     * 单槽全局字段,修掉并发串味、撕裂读与"失败后返回错误的页"三个缺陷。
     */
    private val cache = PageResponseCache<GeneralTabListResponse>()

    fun generalTabList(
        forumId: Long,
        forumName: String,
        tabId: Int,
        tabType: Int,
        tabName: String,
        isGeneralTab: Int,
        pn: Int = 1,
        sortType: Int = -1,
        lastThreadId: Long = 0,
        isDefaultNavTab: Int = 0,
        forceNew: Boolean = false,
    ): Flow<GeneralTabListResponse> {
        // 缓存键编入 uid:响应含账号态字段(关注/签到/我的态度),不区分账号则
        // 切号后首访同一 tab 会命中上一个账号的响应(与 FrsPageRepository 同口径)。
        // blockVideo 一并编入(理由同 FrsPageRepository:过滤后响应整体进缓存)
        val hash = "${AccountUtil.getUid()}_${App.INSTANCE.appPreferences.blockVideo.value}_${forumId}_${tabId}_${pn}_${sortType}_${lastThreadId}_${tabType}"
        if (!forceNew) {
            cache.get(hash)?.let { return flowOf(it) }
        }
        val blockVideo = App.INSTANCE.appPreferences.blockVideo.value
        return TiebaApi.getInstance().generalTabList(
            forumId, forumName, tabId, tabType, tabName, isGeneralTab,
            pn, sortType, lastThreadId, isDefaultNavTab
        ).map { response ->
            if (response.data_ == null) throw TiebaUnknownException
            val userById = response.data_!!.user_list.associateBy { it.id }
            val threadList = response.data_!!.general_list
                .map { threadInfo ->
                    threadInfo.copy(author = userById[threadInfo.authorId])
                }
                .filter { !blockVideo || it.videoInfo == null }
                .filter { it.ala_info == null }
            response.copy(data_ = response.data_!!.copy(general_list = threadList))
        }.onEach { cache.put(hash, it) }
    }
}
