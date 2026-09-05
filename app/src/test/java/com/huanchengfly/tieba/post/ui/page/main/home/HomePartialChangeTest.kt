package com.huanchengfly.tieba.post.ui.page.main.home

import com.huanchengfly.tieba.post.models.database.History
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页 ViewModel 的 [HomePartialChange] reducer 纯函数 JVM 单测。
 *
 * 这些 `reduce(oldState)` 不依赖 Android / Dispatchers / StateFlow，是同步纯函数，
 * 因此无需 Robolectric / coroutines-test 即可在 JVM 上验证。
 *
 * 覆盖价值最高的是 §3.9 串行化的不变式：`CacheSynced`（后台全量同步完成）到达时，
 * 绝不能把正在进行的 `isLoading` / `error` 改回，否则会干扰已渲染的首屏。
 * 见 [cacheSyncedPreservesLoadingAndErrorState] 与 [serializedRefreshFlowDoesNotClearLoadingOnBackgroundSync]。
 */
class HomePartialChangeTest {

    private fun forum(id: String, name: String = "吧$id") = HomeUiState.Forum(
        avatar = "",
        forumId = id,
        forumName = name,
        isSign = false,
        levelId = "1",
        hotNum = 0,
    )

    private fun baseState() = HomeUiState(
        isLoading = true,
        hasLoaded = false,
        forums = persistentListOf(forum("1"), forum("2")),
        topForums = persistentListOf(forum("1")),
        historyForums = persistentListOf(),
        expandHistoryForum = false,
        error = null,
    )

    // ---------- Refresh.Start ----------

    @Test
    fun refreshStartSetsLoadingOnly() {
        val old = baseState()
        val next = HomePartialChange.Refresh.Start.reduce(old)
        assertEquals(true, next.isLoading)
        assertEquals(old.hasLoaded, next.hasLoaded)
        assertEquals(old.forums.toList(), next.forums.toList())
        assertEquals(old.topForums.toList(), next.topForums.toList())
        assertEquals(old.historyForums.toList(), next.historyForums.toList())
        assertEquals(old.expandHistoryForum, next.expandHistoryForum)
        assertEquals(old.error, next.error)
    }

    // ---------- Refresh.Success ----------

    @Test
    fun refreshSuccessFillsAllAndClearsLoading() {
        val old = baseState().copy(isLoading = true, error = RuntimeException("e"))
        val f = listOf(forum("a"), forum("b"))
        val t = listOf(forum("a"))
        val h = listOf(History(title = "h"))
        val next = HomePartialChange.Refresh.Success(f, t, h).reduce(old)
        assertEquals(false, next.isLoading)
        assertEquals(true, next.hasLoaded)
        assertNull(next.error)
        assertEquals(f, next.forums.toList())
        assertEquals(t, next.topForums.toList())
        assertEquals(h, next.historyForums.toList())
    }

    // ---------- Refresh.CacheSynced（★ §3.9 不变式核心） ----------

    @Test
    fun cacheSyncedPreservesLoadingAndErrorState() {
        // 模拟：首屏 Success 已渲染(isLoading=false, hasLoaded=true)，
        // 之后用户又下拉刷新(Start -> isLoading=true)，此时后台全量同步(CacheSynced)到达，
        // 绝不允许把它重置回去或清空 error —— 否则首屏会闪烁/回退。
        val duringSecondRefresh = baseState().copy(isLoading = true, error = RuntimeException("x"))
        val synced = HomePartialChange.Refresh.CacheSynced(
            listOf(forum("3")),
            listOf(forum("1"), forum("3")),
        ).reduce(duringSecondRefresh)

        // ★ §3.9 不变式:CacheSynced 只更新 forums/topForums,其余字段原样保留
        assertEquals(duringSecondRefresh.isLoading, synced.isLoading) // isLoading 不被后台同步改回
        assertEquals(duringSecondRefresh.error?.message, synced.error?.message) // error 不被清空
        assertEquals(duringSecondRefresh.hasLoaded, synced.hasLoaded) // 不变(本场景为 false,首屏未加载)
        assertEquals(duringSecondRefresh.historyForums.toList(), synced.historyForums.toList()) // 不变
        assertEquals(duringSecondRefresh.expandHistoryForum, synced.expandHistoryForum) // 不变
        // 列表确实被全量数据更新
        assertEquals(listOf(forum("3")), synced.forums.toList())
        assertEquals(listOf(forum("1"), forum("3")), synced.topForums.toList())
    }

    @Test
    fun serializedRefreshFlowDoesNotClearLoadingOnBackgroundSync() {
        // 端到端纯函数重现 §3.9 串行化流程：Start -> Success(首屏) -> Start(再次刷新) -> CacheSynced(后台同步)
        var s = HomeUiState()
        s = HomePartialChange.Refresh.Start.reduce(s)
        assertEquals(true, s.isLoading)

        s = HomePartialChange.Refresh.Success(
            listOf(forum("1")),
            listOf(forum("1")),
            emptyList(),
        ).reduce(s)
        assertEquals(false, s.isLoading)
        assertEquals(true, s.hasLoaded)

        // 第二次下拉刷新开始
        s = HomePartialChange.Refresh.Start.reduce(s)
        assertEquals(true, s.isLoading)

        // 后台全量同步到达，绝不把 isLoading 改回 false
        s = HomePartialChange.Refresh.CacheSynced(
            listOf(forum("1"), forum("2")),
            listOf(forum("1")),
        ).reduce(s)
        assertEquals(true, s.isLoading) // ★ 关键不变式
        assertEquals(listOf(forum("1"), forum("2")), s.forums.toList())
    }

    // ---------- Refresh.Failure ----------

    @Test
    fun refreshFailureStopsLoadingAndMarksLoaded() {
        val old = baseState().copy(isLoading = true)
        val next = HomePartialChange.Refresh.Failure(RuntimeException("boom")).reduce(old)
        assertEquals(false, next.isLoading)
        assertEquals(true, next.hasLoaded)
        assertEquals("boom", next.error?.message)
        assertEquals(old.forums.toList(), next.forums.toList()) // 列表不被清空
        assertEquals(old.topForums.toList(), next.topForums.toList())
    }

    // ---------- RefreshHistory ----------

    @Test
    fun refreshHistorySuccessUpdatesOnlyHistory() {
        val old = baseState()
        val hist = listOf(History(title = "h1"), History(title = "h2"))
        val next = HomePartialChange.RefreshHistory.Success(hist).reduce(old)
        assertEquals(hist, next.historyForums.toList())
        assertEquals(old.forums.toList(), next.forums.toList())
        assertEquals(old.topForums.toList(), next.topForums.toList())
        assertEquals(old.isLoading, next.isLoading)
    }

    @Test
    fun refreshHistoryFailureNoOp() {
        val old = baseState()
        val next = HomePartialChange.RefreshHistory.Failure(RuntimeException()).reduce(old)
        assertEquals(old.forums.toList(), next.forums.toList())
        assertEquals(old.topForums.toList(), next.topForums.toList())
        assertEquals(old.historyForums.toList(), next.historyForums.toList())
    }

    // ---------- Unfollow ----------

    @Test
    fun unfollowSuccessRemovesForumEverywhere() {
        val old = baseState() // forums=[1,2], topForums=[1]
        val next = HomePartialChange.Unfollow.Success("2").reduce(old)
        assertEquals(listOf(forum("1")), next.forums.toList())
        assertEquals(listOf(forum("1")), next.topForums.toList()) // 同时从头像置顶移除
    }

    @Test
    fun unfollowFailureNoOp() {
        val old = baseState()
        val next = HomePartialChange.Unfollow.Failure("err").reduce(old)
        assertEquals(old.forums.toList(), next.forums.toList())
        assertEquals(old.topForums.toList(), next.topForums.toList())
        assertEquals(old.isLoading, next.isLoading)
    }

    // ---------- TopForums.Delete ----------

    @Test
    fun topForumsDeleteRemovesOnlyFromTop() {
        val old = baseState() // forums=[1,2], topForums=[1]
        val next = HomePartialChange.TopForums.Delete.Success("1").reduce(old)
        assertEquals(listOf(forum("1"), forum("2")), next.forums.toList()) // forums 不动
        assertEquals(emptyList<HomeUiState.Forum>(), next.topForums.toList())
    }

    // ---------- TopForums.Add（从 oldState.forums 过滤，而非直接追加） ----------

    @Test
    fun topForumsAddIncludesNewForumWhenPresentInForums() {
        val old = baseState() // forums=[1,2], topForums=[1]
        val next = HomePartialChange.TopForums.Add.Success(forum("2")).reduce(old)
        assertEquals(listOf(forum("1"), forum("2")), next.topForums.toList())
        assertEquals(old.forums.toList(), next.forums.toList()) // forums 不变
    }

    @Test
    fun topForumsAddExcludesForumNotInForums() {
        // Add 的 forum 不在 oldState.forums 中 -> 按设计被过滤掉,不应凭空进入 topForums
        val old = baseState()
        val next = HomePartialChange.TopForums.Add.Success(forum("9")).reduce(old)
        assertEquals(listOf(forum("1")), next.topForums.toList())
    }

    @Test
    fun topForumsAddFailureNoOp() {
        val old = baseState()
        val next = HomePartialChange.TopForums.Add.Failure("err").reduce(old)
        assertEquals(old.forums.toList(), next.forums.toList())
        assertEquals(old.topForums.toList(), next.topForums.toList())
        assertEquals(old.isLoading, next.isLoading)
    }

    // ---------- ToggleHistory ----------

    @Test
    fun toggleHistoryFlipsExpand() {
        val old = baseState().copy(expandHistoryForum = false)
        val next = HomePartialChange.ToggleHistory(true).reduce(old)
        assertEquals(true, next.expandHistoryForum)
        assertEquals(old.forums.toList(), next.forums.toList()) // 其余不变
    }
}
