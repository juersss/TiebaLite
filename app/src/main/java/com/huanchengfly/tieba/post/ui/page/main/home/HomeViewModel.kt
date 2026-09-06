package com.huanchengfly.tieba.post.ui.page.main.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import android.util.Log
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.ForumGuideBean
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.models.database.TopForum
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.DatabaseUtil
import com.huanchengfly.tieba.post.utils.HistoryUtil
import com.huanchengfly.tieba.post.utils.FollowedForumsCache
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.zip

@Stable
class HomeViewModel : BaseViewModel<HomeUiIntent, HomePartialChange, HomeUiState, HomeUiEvent>() {
    override fun createInitialState(): HomeUiState = HomeUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<HomeUiIntent, HomePartialChange, HomeUiState> =
        HomePartialChangeProducer

    override fun dispatchEvent(partialChange: HomePartialChange): UiEvent? =
        when (partialChange) {
            is HomePartialChange.TopForums.Delete.Failure -> CommonUiEvent.Toast(partialChange.errorMessage)
            is HomePartialChange.TopForums.Add.Failure -> CommonUiEvent.Toast(partialChange.errorMessage)
            else -> null
        }

    object HomePartialChangeProducer :
        PartialChangeProducer<HomeUiIntent, HomePartialChange, HomeUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<HomeUiIntent>): Flow<HomePartialChange> {
            return merge(
                intentFlow.filterIsInstance<HomeUiIntent.Refresh>()
                    .flatMapConcat { produceRefreshPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.RefreshHistory>()
                    .flatMapConcat { produceRefreshHistoryPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.TopForums.Delete>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.TopForums.Add>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.Unfollow>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.ToggleHistory>()
                    .flatMapConcat { it.toPartialChangeFlow() }
            )
        }

        @Suppress("USELESS_CAST")
        private fun produceRefreshPartialChangeFlow(): Flow<HomePartialChange.Refresh> =
            HistoryUtil.getFlow(HistoryUtil.TYPE_FORUM, 0).zip(
                TiebaApi.getInstance().forumGuideFirstPagesFlow()
            ) { historyForums, forumGuideBean ->
                val allLikeForums = forumGuideBean.likeForum
                val forums = allLikeForums.map { it.toForum() }

                // 增量并入缓存,不覆盖后台全量同步已写入的数据
                FollowedForumsCache.mergeAll(allLikeForums)

                val topForumsDB = DatabaseUtil.getTopForums().map { it.forumId }.toSet()
                val topForums = forums.filter { it.forumId in topForumsDB }
                HomePartialChange.Refresh.Success(
                    forums,
                    topForums,
                    historyForums
                ) as HomePartialChange.Refresh
            }
                // 慢速路径(§3.9):快速路径完成后再启动全量同步。
                // 原先两条路径仅隔 300ms 并发,慢路径的 page 1-4 与快路径完全重复,
                // 还会与首屏请求抢带宽;串行化后首屏独占带宽,全量同步在其后进行,
                // 且缓存的写入顺序确定化(先 mergeAll 增量、后 updateAll 全量)。
                // 仍从 page 1 完整拉取(结果对缓存权威,整体替换)而不做"从第 5 页续拉":
                // 快路径可能提前截断(任一页 hasMore=false),且两次拉取之间翻页边界可能漂移,
                // 盲目续拉会漏吧、极端时清空缓存。
                // 全量同步失败仅静默跳过,不影响已渲染的首屏。
                //
                // ★ 快路径的 Success 必须显式转发:flatMapConcat 只下发映射流的产物,
                // 上游值本身会被吞掉——若直接 flatMapConcat { 慢路径 },Success 永远到不了
                // reducer,isLoading 无法清除(转圈不停),这正是真机回归发现的回归。
                // 快路径失败(Failure)不进入本 lambda,由下游 .catch 兜住并转发,
                // 此时跳过全量同步(失败场景不再补 54 个串行请求)。
                .flatMapConcat { success ->
                    flow {
                        emit(success)
                        emitAll(
                            TiebaApi.getInstance().allForumGuideFlow()
                                .mapNotNull<ForumGuideBean, HomePartialChange.Refresh> { forumGuideBean ->
                                    when (val outcome = forumGuideBean.toForumGuideSyncOutcome(
                                        DatabaseUtil.getTopForums().map { it.forumId }.toSet()
                                    )) {
                                        is ForumGuideSyncOutcome.Truncated -> {
                                            // 截断保护(外部审查 1.2):全量同步达翻页上限/服务端异常
                                            // 中断时,本次结果不完整——不能用部分数据整体替换缓存与
                                            // 首页列表(isFollowed() 依赖缓存,静默丢吧会让关注状态误判),
                                            // 保留上一次完整数据
                                            Log.w(
                                                "HomeViewModel",
                                                "全量同步结果被截断(${outcome.fetchedCount} 个吧),跳过缓存与列表替换"
                                            )
                                            null
                                        }

                                        is ForumGuideSyncOutcome.Complete -> {
                                            // 全量数据,整体替换缓存
                                            FollowedForumsCache.updateAll(outcome.rawForums)
                                            HomePartialChange.Refresh.CacheSynced(
                                                outcome.forums,
                                                outcome.topForums
                                            )
                                        }
                                    }
                                }
                                .catch { }
                        )
                    }
                }
                .flowOn(Dispatchers.IO)
                .catch { emit(HomePartialChange.Refresh.Failure(it)) }
                .onStart { emit(HomePartialChange.Refresh.Start) }

        @Suppress("USELESS_CAST")
        private fun produceRefreshHistoryPartialChangeFlow(): Flow<HomePartialChange.RefreshHistory> =
            HistoryUtil.getFlow(HistoryUtil.TYPE_FORUM, 0)
                .map { HomePartialChange.RefreshHistory.Success(it) as HomePartialChange.RefreshHistory }
                .catch { emit(HomePartialChange.RefreshHistory.Failure(it)) }

        private fun HomeUiIntent.TopForums.Delete.toPartialChangeFlow() =
            flow<HomePartialChange.TopForums.Delete> {
                DatabaseUtil.deleteTopForum(forumId)
                emit(HomePartialChange.TopForums.Delete.Success(forumId))
            }.flowOn(Dispatchers.IO)
                .catch { emit(HomePartialChange.TopForums.Delete.Failure(it.getErrorMessage())) }

        private fun HomeUiIntent.TopForums.Add.toPartialChangeFlow() =
            flow<HomePartialChange.TopForums.Add> {
                DatabaseUtil.addTopForum(forum.forumId)
                emit(HomePartialChange.TopForums.Add.Success(forum))
            }.flowOn(Dispatchers.IO)
                .catch { emit(HomePartialChange.TopForums.Add.Failure(it.getErrorMessage())) }

        private fun HomeUiIntent.Unfollow.toPartialChangeFlow(): Flow<HomePartialChange.Unfollow> {
            // 未登录守卫(外部审查 1.4):getLoginInfo() 登出态返回 null,`!!` 把 NPE 抛在
            // 流构建期,内部的 .catch 兜不住(异常发生在流存在之前);上游 #113"登录后掉线"
            // 使该状态并不罕见——转为 Failure 走正常失败分支
            val tbs = AccountUtil.getLoginInfo()?.tbs
                ?: return flowOf(HomePartialChange.Unfollow.Failure("未登录"))
            return TiebaApi.getInstance()
                .unlikeForumFlow(forumId, forumName, tbs)
                .map<CommonResponse, HomePartialChange.Unfollow> {
                    HomePartialChange.Unfollow.Success(forumId)
                }
                .catch { emit(HomePartialChange.Unfollow.Failure(it.getErrorMessage())) }
        }

        private fun HomeUiIntent.ToggleHistory.toPartialChangeFlow() =
            flowOf(HomePartialChange.ToggleHistory(!currentExpand))
    }
}

sealed interface HomeUiIntent : UiIntent {
    data object Refresh : HomeUiIntent

    data object RefreshHistory : HomeUiIntent

    data class Unfollow(val forumId: String, val forumName: String) : HomeUiIntent

    sealed interface TopForums : HomeUiIntent {
        data class Delete(val forumId: String) : TopForums

        data class Add(val forum: HomeUiState.Forum) : TopForums
    }

    data class ToggleHistory(val currentExpand: Boolean) : HomeUiIntent
}

sealed interface HomePartialChange : PartialChange<HomeUiState> {
    sealed class Unfollow : HomePartialChange {
        override fun reduce(oldState: HomeUiState): HomeUiState =
            when (this) {
                is Success -> {
                    oldState.copy(
                        forums = oldState.forums.filterNot { it.forumId == forumId }
                            .toImmutableList(),
                        topForums = oldState.topForums.filterNot { it.forumId == forumId }
                            .toImmutableList(),
                    )
                }

                is Failure -> oldState
            }

        data class Success(val forumId: String) : Unfollow()

        data class Failure(val errorMessage: String) : Unfollow()
    }

    sealed class Refresh : HomePartialChange {
        override fun reduce(oldState: HomeUiState): HomeUiState =
            when (this) {
                is Success -> oldState.copy(
                    isLoading = false,
                    hasLoaded = true,
                    forums = forums.toImmutableList(),
                    topForums = topForums.toImmutableList(),
                    historyForums = historyForums.toImmutableList(),
                    error = null
                )

                is CacheSynced -> oldState.copy(
                    forums = forums.toImmutableList(),
                    topForums = topForums.toImmutableList(),
                )

                is Failure -> oldState.copy(isLoading = false, hasLoaded = true, error = error)
                Start -> oldState.copy(isLoading = true)
            }

        data object Start : Refresh()

        data class Success(
            val forums: List<HomeUiState.Forum>,
            val topForums: List<HomeUiState.Forum>,
            val historyForums: List<History>,
        ) : Refresh()

        /**
         * 后台全量同步完成,补全关注吧列表与置顶吧(不改变加载状态)
         */
        data class CacheSynced(
            val forums: List<HomeUiState.Forum>,
            val topForums: List<HomeUiState.Forum>,
        ) : Refresh()

        data class Failure(
            val error: Throwable,
        ) : Refresh()
    }

    sealed class RefreshHistory : HomePartialChange {
        override fun reduce(oldState: HomeUiState): HomeUiState =
            when (this) {
                is Success -> oldState.copy(
                    historyForums = historyForums.toImmutableList(),
                )

                else -> oldState
            }

        data class Success(
            val historyForums: List<History>,
        ) : RefreshHistory()

        data class Failure(
            val error: Throwable,
        ) : RefreshHistory()
    }

    sealed interface TopForums : HomePartialChange {
        sealed interface Delete : HomePartialChange {
            override fun reduce(oldState: HomeUiState): HomeUiState =
                when (this) {
                    is Success -> oldState.copy(topForums = oldState.topForums.filterNot { it.forumId == forumId }
                        .toImmutableList())

                    is Failure -> oldState
                }

            data class Success(val forumId: String) : Delete

            data class Failure(val errorMessage: String) : Delete
        }

        sealed interface Add : HomePartialChange {
            override fun reduce(oldState: HomeUiState): HomeUiState =
                when (this) {
                    is Success -> {
                        val topForumsId = oldState.topForums.map { it.forumId }.toMutableList()
                        topForumsId.add(forum.forumId)
                        oldState.copy(
                            topForums = oldState.forums.filter { topForumsId.contains(it.forumId) }
                                .toImmutableList()
                        )
                    }

                    is Failure -> oldState
                }

            data class Success(val forum: HomeUiState.Forum) : Add

            data class Failure(val errorMessage: String) : Add
        }
    }

    data class ToggleHistory(val expand: Boolean) : HomePartialChange {
        override fun reduce(oldState: HomeUiState): HomeUiState =
            oldState.copy(expandHistoryForum = expand)
    }
}

@Immutable
data class HomeUiState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val forums: ImmutableList<Forum> = persistentListOf(),
    val topForums: ImmutableList<Forum> = persistentListOf(),
    val historyForums: ImmutableList<History> = persistentListOf(),
    val expandHistoryForum: Boolean = true,
    val error: Throwable? = null,
) : UiState {
    @Immutable
    data class Forum(
        val avatar: String,
        val forumId: String,
        val forumName: String,
        val isSign: Boolean,
        val levelId: String,
        val hotNum: Int,
    )
}

sealed interface HomeUiEvent : UiEvent
/** 全量同步结果转化为首页刷新产物的出口(外部审查 v2-R2:抽成纯函数以便 JVM 单测锁定截断守卫) */
internal sealed interface ForumGuideSyncOutcome {
    /** 结果被截断:禁止替换缓存与首页列表,保留上一次完整数据 */
    data class Truncated(val fetchedCount: Int) : ForumGuideSyncOutcome

    data class Complete(
        val rawForums: List<ForumGuideBean.LikeForum>,
        val forums: List<HomeUiState.Forum>,
        val topForums: List<HomeUiState.Forum>,
    ) : ForumGuideSyncOutcome
}

internal fun ForumGuideBean.LikeForum.toForum(): HomeUiState.Forum =
    HomeUiState.Forum(
        avatar,
        forumId.toString(),
        forumName,
        isSign == 1,
        levelId.toString(),
        hotNum
    )

internal fun ForumGuideBean.toForumGuideSyncOutcome(topForumIds: Set<String>): ForumGuideSyncOutcome =
    if (truncated) ForumGuideSyncOutcome.Truncated(fetchedCount = likeForum.size)
    else {
        val forums = likeForum.map { it.toForum() }
        ForumGuideSyncOutcome.Complete(
            rawForums = likeForum,
            forums = forums,
            topForums = forums.filter { it.forumId in topForumIds },
        )
    }
