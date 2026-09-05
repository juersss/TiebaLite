package com.huanchengfly.tieba.post.ui.page.subposts

import com.huanchengfly.tieba.post.api.models.protos.SubPostList
import com.huanchengfly.tieba.post.arch.wrapImmutable
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SubPostsViewModel 的 `reduce(oldState)` 纯函数 JVM 单测（§3.21 可测性改造第四块：ViewModel 层）。
 *
 * 与 ThreadViewModel 同理，§3.5 之后 Agree/Disagree reducer 为 no-op（锁定差分模型架构）；
 * DeletePost 在 subPostId 为空时也是 no-op。SubPostList/Post 为 Wire 生成模型，构造成本高，
 * 故只测无需重域模型的分支；DeletePost 的真实按 id 过滤路径（需 SubPostItemData fixture）留待
 * 与 Flow 接线 coroutines-test 一起补。
 */
class SubPostsViewModelReducerTest {

    private fun state() = SubPostsUiState()

    @Test
    fun agreeReducerDoesNotMutateState() {
        val s = state()
        listOf<SubPostsPartialChange>(
            SubPostsPartialChange.Agree.Start(postId = 1, subPostId = null, hasAgree = true),
            SubPostsPartialChange.Agree.Success(postId = 1, subPostId = null, hasAgree = true),
            SubPostsPartialChange.Agree.Failure(postId = 1, subPostId = null, hasAgree = true, throwable = RuntimeException()),
            SubPostsPartialChange.Agree.AuthoritativeReject(postId = 1, subPostId = null, code = "x", msg = "m"),
        ).forEach { assertEquals("SubPosts Agree 必须为 no-op(§3.5)", s, it.reduce(s)) }
    }

    @Test
    fun disagreeReducerDoesNotMutateState() {
        val s = state()
        listOf<SubPostsPartialChange>(
            SubPostsPartialChange.Disagree.Start(postId = 1, subPostId = null, hasDisagree = true),
            SubPostsPartialChange.Disagree.Success(postId = 1, subPostId = null, hasDisagree = true),
            SubPostsPartialChange.Disagree.Failure(postId = 1, subPostId = null, hasDisagree = true, throwable = RuntimeException()),
            SubPostsPartialChange.Disagree.AuthoritativeReject(postId = 1, subPostId = null, code = "x", msg = "m"),
        ).forEach { assertEquals("SubPosts Disagree 必须为 no-op(§3.5)", s, it.reduce(s)) }
    }

    @Test
    fun deletePostWithNullSubPostIdIsNoOp() {
        val s = state()
        assertEquals(s, SubPostsPartialChange.DeletePost.Success(postId = 1, subPostId = null).reduce(s))
    }

    // ---- §续：真实变更 reducer（需 Wire 域模型 fixture）----
    // DeletePost（带 subPostId）是 SubPosts 里真正改 subPosts 列表的 reducer。
    // 命中路径：按 subPostId 过滤掉对应楼中楼，其余 id 不变。

    private fun subPostItem(id: Long) = SubPostItemData(
        subPost = SubPostList(id = id).wrapImmutable(),
        subPostContentRenders = persistentListOf(),
        blocked = false,
    )

    @Test
    fun deletePostWithSubPostIdRemovesMatchingItem() {
        val sp1 = subPostItem(5L)
        val sp2 = subPostItem(6L)
        val sp3 = subPostItem(7L)
        val s = SubPostsUiState(subPosts = persistentListOf(sp1, sp2, sp3))
        val newState = SubPostsPartialChange.DeletePost.Success(postId = 1L, subPostId = 6L).reduce(s)
        assertEquals(2, newState.subPosts.size)
        assertEquals(listOf(5L, 7L), newState.subPosts.map { it.id })
    }

    @Test
    fun deletePostFailureIsNoOp() {
        val sp1 = subPostItem(5L)
        val s = SubPostsUiState(subPosts = persistentListOf(sp1))
        assertEquals(s, SubPostsPartialChange.DeletePost.Failure(errorCode = -1, errorMessage = "e").reduce(s))
    }
}
