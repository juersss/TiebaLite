package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.core.network.model.protos.frsPage.FrsPageResponse
import com.huanchengfly.tieba.post.core.network.model.protos.threadList.ThreadListResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.core.data.appPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

object FrsPageRepository {
    /**
     * 列表响应会话级缓存。
     *
     * 替代此前的 `lastHash` / `lastResponse` 单槽全局字段:旧实现并发下会跨吧串味、
     * 存在撕裂读窗口,且在请求失败后会留下"hash 已更新而 response 仍是旧对象"的
     * 状态,使下一次相同请求命中并返回**错误的页**。详见 [PageResponseCache] 类注释。
     */
    private val cache = PageResponseCache<FrsPageResponse>()

    fun frsPage(
        forumName: String,
        page: Int,
        loadType: Int,
        sortType: Int,
        goodClassifyId: Int? = null,
        forceNew: Boolean = false,
    ): Flow<FrsPageResponse> {
        // 缓存键编入 uid:frs 响应含账号态(is_follow / is_sign / 我的关注态),
        // 进程级缓存不区分账号,切号后首访同一吧会命中上一个账号的响应,
        // 关注/签到按钮显示别人的状态。
        // blockVideo 一并编入:被过滤后的响应整体进缓存,开关不进键则会话内
        // 切换"屏蔽视频"后,已缓存页仍按旧口径展示(Refresh 之外无法自愈)
        val hash = "${AccountUtil.getUid()}_${App.INSTANCE.appPreferences.blockVideo.value}_${forumName}_${page}_${loadType}_${sortType}_${goodClassifyId}"
        if (!forceNew) {
            cache.get(hash)?.let { return flowOf(it) }
        }
        // 屏蔽视频开关只读一次:旧代码把它写在 filter 谓词里,每个帖子都要读一次
        // SharedPreferences(虽为内存读,但纯属白付)
        val blockVideo = App.INSTANCE.appPreferences.blockVideo.value
        return TiebaApi.getInstance().frsPage(forumName, page, loadType, sortType, goodClassifyId)
            .map { response ->
                if (response.data_ == null) throw TiebaUnknownException
                // 作者映射先建索引:旧代码在 map 内对每个帖子做一次 userList.find{},
                // 单页代价 O(帖子数 × 用户数),且随用户列表增大线性恶化;改为 O(帖子数 + 用户数)
                val userById = response.data_!!.user_list.associateBy { it.id }
                val threadList = response.data_!!.thread_list
                    .map { threadInfo ->
                        threadInfo.copy(author = userById[threadInfo.authorId])
                    }
                    .filter { !blockVideo || it.videoInfo == null }
                    .filter { it.ala_info == null } // 去他妈的直播
                response.copy(data_ = response.data_!!.copy(thread_list = threadList))
            }
            .onEach { cache.put(hash, it) }
    }

    fun threadList(
        forumId: Long,
        forumName: String,
        page: Int,
        sortType: Int,
        threadIds: String = "",
    ): Flow<ThreadListResponse> {
        val blockVideo = App.INSTANCE.appPreferences.blockVideo.value
        return TiebaApi.getInstance()
            .threadList(forumId, forumName, page, sortType, threadIds)
            .map { response ->
                if (response.data_ == null) throw TiebaUnknownException
                val userById = response.data_!!.user_list.associateBy { it.id }
                val threadList = response.data_!!.thread_list
                    .map { threadInfo ->
                        threadInfo.copy(author = userById[threadInfo.authorId])
                    }
                    .filter { !blockVideo || it.videoInfo == null }
                    .filter { it.ala_info == null } // 去他妈的直播
                response.copy(data_ = response.data_!!.copy(thread_list = threadList))
            }
    }

    /** 仅供单测:清空会话级缓存(本对象是进程级单例,用例之间必须复位) */
    internal fun clearCacheForTest() = cache.clearForTest()
}
