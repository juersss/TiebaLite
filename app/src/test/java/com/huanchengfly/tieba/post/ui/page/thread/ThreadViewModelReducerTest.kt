package com.huanchengfly.tieba.post.ui.page.thread

import com.huanchengfly.tieba.post.api.models.protos.Post
import com.huanchengfly.tieba.post.arch.wrapImmutable
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThreadViewModel 的 `reduce(oldState)` 纯函数 JVM 单测（§3.21 可测性改造第四块：ViewModel 层）。
 *
 * 背景：§3.5 把赞踩计数/我的态度改为由 OpRecordStore 差分推导，因此本 VM 的
 * Agree/Disagree reducer 一律为 no-op（直接返回 oldState），真正的状态变化发生在
 * OpRecordStoreTest 覆盖的差分模型里。本测试锁定这一架构约定，并覆盖几个真正改状态的 reducer。
 *
 * 不依赖 Android / Dispatchers。ThreadInfo/Post 等为 Wire 生成模型、构造成本高，
 * 故只测「无需构造重域模型」的分支（no-op、ToggleImmersiveMode、threadInfo 为空时的收藏路径）；
 * 需要 PostItemData/ThreadInfo 的真实变更 reducer（DeletePost、Load*、PollThread）留待
 * 与 Flow 接线 coroutines-test 一起补 fixture。
 */
class ThreadViewModelReducerTest {

    private fun state() = ThreadUiState()

    private val agreeThreadCases = listOf<ThreadPartialChange>(
        ThreadPartialChange.AgreeThread.Start(threadId = 1, hasAgree = true),
        ThreadPartialChange.AgreeThread.Success(threadId = 1, hasAgree = true),
        ThreadPartialChange.AgreeThread.Failure(threadId = 1, hasAgree = true, errorCode = -1, errorMessage = "e"),
        ThreadPartialChange.AgreeThread.AuthoritativeReject(threadId = 1, code = "x", msg = "m"),
    )

    private val agreePostCases = listOf<ThreadPartialChange>(
        ThreadPartialChange.AgreePost.Start(postId = 1, hasAgree = true),
        ThreadPartialChange.AgreePost.Success(postId = 1, hasAgree = true),
        ThreadPartialChange.AgreePost.Failure(postId = 1, hasAgree = true, errorCode = -1, errorMessage = "e"),
        ThreadPartialChange.AgreePost.AuthoritativeReject(postId = 1, code = "x", msg = "m"),
    )

    private val disagreeThreadCases = listOf<ThreadPartialChange>(
        ThreadPartialChange.DisagreeThread.Start(threadId = 1, hasDisagree = true),
        ThreadPartialChange.DisagreeThread.Success(threadId = 1, hasDisagree = true),
        ThreadPartialChange.DisagreeThread.Failure(threadId = 1, hasDisagree = true, errorCode = -1, errorMessage = "e"),
        ThreadPartialChange.DisagreeThread.AuthoritativeReject(threadId = 1, code = "x", msg = "m"),
    )

    private val disagreePostCases = listOf<ThreadPartialChange>(
        ThreadPartialChange.DisagreePost.Start(postId = 1, hasDisagree = true),
        ThreadPartialChange.DisagreePost.Success(postId = 1, hasDisagree = true),
        ThreadPartialChange.DisagreePost.Failure(postId = 1, hasDisagree = true, errorCode = -1, errorMessage = "e"),
        ThreadPartialChange.DisagreePost.AuthoritativeReject(postId = 1, code = "x", msg = "m"),
    )

    @Test
    fun agreeThreadReducerDoesNotMutateState() {
        val s = state()
        agreeThreadCases.forEach { assertEquals("AgreeThread 必须为 no-op(§3.5)", s, it.reduce(s)) }
    }

    @Test
    fun agreePostReducerDoesNotMutateState() {
        val s = state()
        agreePostCases.forEach { assertEquals("AgreePost 必须为 no-op(§3.5)", s, it.reduce(s)) }
    }

    @Test
    fun disagreeThreadReducerDoesNotMutateState() {
        val s = state()
        disagreeThreadCases.forEach { assertEquals("DisagreeThread 必须为 no-op(§3.5)", s, it.reduce(s)) }
    }

    @Test
    fun disagreePostReducerDoesNotMutateState() {
        val s = state()
        disagreePostCases.forEach { assertEquals("DisagreePost 必须为 no-op(§3.5)", s, it.reduce(s)) }
    }

    @Test
    fun toggleImmersiveModeUpdatesOnlyFlag() {
        val off = state().copy(isImmersiveMode = false)
        val on = ThreadPartialChange.ToggleImmersiveMode.Success(true).reduce(off)
        assertTrue(on.isImmersiveMode)
        assertEquals(off.isRefreshing, on.isRefreshing)
        assertEquals(off.data.toList(), on.data.toList())

        val back = ThreadPartialChange.ToggleImmersiveMode.Success(false).reduce(on)
        assertFalse(back.isImmersiveMode)
    }

    @Test
    fun addFavoriteWithNullThreadInfoIsNoOp() {
        val s = state().copy(threadInfo = null)
        assertEquals(s, ThreadPartialChange.AddFavorite.Success(markPostId = 5, floor = 3).reduce(s))
    }

    @Test
    fun removeFavoriteWithNullThreadInfoIsNoOp() {
        val s = state().copy(threadInfo = null)
        assertEquals(s, ThreadPartialChange.RemoveFavorite.Success.reduce(s))
    }

    // ---- §续：真实变更 reducer（需 Wire 域模型 fixture）----
    // DeletePost 是 Thread 里真正改 data 列表的 reducer（之前因 Post/PostItemData 构造成本高而未测）。
    // 命中路径：按 postId 删除对应楼层，列表长度 -1 且其余楼层 id 不变。

    private fun postItem(id: Long) = PostItemData(
        post = Post(id = id).wrapImmutable(),
        blocked = false,
        contentRenders = persistentListOf(),
        subPosts = persistentListOf(),
    )

    @Test
    fun deletePostSuccessRemovesMatchingPostById() {
        val p1 = postItem(10L)
        val p2 = postItem(20L)
        val p3 = postItem(30L)
        val s = state().copy(data = persistentListOf(p1, p2, p3))
        val newState = ThreadPartialChange.DeletePost.Success(postId = 20L).reduce(s)
        assertEquals(2, newState.data.size)
        assertEquals(listOf(10L, 30L), newState.data.map { it.post.get { id } })
    }

    @Test
    fun deletePostFailureIsNoOp() {
        val p1 = postItem(10L)
        val s = state().copy(data = persistentListOf(p1))
        assertEquals(s, ThreadPartialChange.DeletePost.Failure(errorCode = -1, errorMessage = "e").reduce(s))
    }

    @Test
    fun deletePostWithUnknownPostIdIsNoOp() {
        // §4.1.6 观测项回归:postId 不在 data 中时 indexOfFirst 返回 -1,
        // 无守卫的 removeAt(-1) 会抛 IndexOutOfBoundsException,此处必须保持原状态
        val p1 = postItem(10L)
        val s = state().copy(data = persistentListOf(p1))
        assertEquals(s, ThreadPartialChange.DeletePost.Success(postId = 99L).reduce(s))
    }
}
