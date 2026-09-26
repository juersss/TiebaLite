package com.huanchengfly.tieba.post.ui.page.forum.threadlist

import com.huanchengfly.tieba.post.ui.page.forum.generaltablist.GeneralTabListPartialChange
import com.huanchengfly.tieba.post.ui.page.forum.generaltablist.GeneralTabListUiState
import com.huanchengfly.tieba.post.ui.page.hottopic.detail.TopicDetailPartialChange
import com.huanchengfly.tieba.post.ui.page.hottopic.detail.TopicDetailUiState
import com.huanchengfly.tieba.post.ui.page.main.explore.concern.ConcernPartialChange
import com.huanchengfly.tieba.post.ui.page.main.explore.concern.ConcernUiState
import com.huanchengfly.tieba.post.ui.page.main.explore.hot.HotPartialChange
import com.huanchengfly.tieba.post.ui.page.main.explore.hot.HotUiState
import com.huanchengfly.tieba.post.ui.page.main.explore.personalized.PersonalizedPartialChange
import com.huanchengfly.tieba.post.ui.page.main.explore.personalized.PersonalizedUiState
import com.huanchengfly.tieba.post.ui.page.user.post.UserPostPartialChange
import com.huanchengfly.tieba.post.ui.page.user.post.UserPostUiState
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 七个列表页 Agree reducer 的"必须为 no-op"锁定测试。
 *
 * 背景:列表页赞踩完成差分模型迁移后,显示数字/亮灯统一由 FeedCard.ThreadAgreeBtn
 * 从 OpRecordStore.records 推导,记录更新(setPending/confirm/revertPending/rebase)
 * 全部集中在各页 dispatchEvent——与帖子页(§3.5,见 ThreadViewModelReducerTest)同一约定。
 * reducer 若再改写 proto 计数字段,就会重新引入"基准被本地 ±1 污染、刷新后无法归位"
 * 的整类计数漂移 bug,因此这里用 assertSame 锁死"不产生任何状态拷贝"。
 *
 * assertSame(而非 assertEquals):旧实现即便对空列表也会执行 oldState.copy(...) 产出
 * 新实例——断言同一实例才能把"no-op"与"改数为 0 的巧合相等"区分开。
 */
class ListPageAgreeReducerTest {

    private val err = RuntimeException("test")

    @Test
    fun forumThreadListAgreeReducerIsNoOp() {
        val s = ForumThreadListUiState()
        listOf(
            ForumThreadListPartialChange.Agree.Start(threadId = 1, hasAgree = 1),
            ForumThreadListPartialChange.Agree.Success(threadId = 1, hasAgree = 1),
            ForumThreadListPartialChange.Agree.Failure(threadId = 1, postId = 2, hasAgree = 1, error = err),
        ).forEach { assertSame("ForumThreadList.Agree 必须为 no-op", s, it.reduce(s)) }
    }

    @Test
    fun generalTabListAgreeReducerIsNoOp() {
        val s = GeneralTabListUiState()
        listOf(
            GeneralTabListPartialChange.Agree.Start(threadId = 1, hasAgree = 1),
            GeneralTabListPartialChange.Agree.Success(threadId = 1, hasAgree = 1),
            GeneralTabListPartialChange.Agree.Failure(threadId = 1, postId = 2, hasAgree = 1, error = err),
        ).forEach { assertSame("GeneralTabList.Agree 必须为 no-op", s, it.reduce(s)) }
    }

    @Test
    fun topicDetailAgreeReducerIsNoOp() {
        val s = TopicDetailUiState()
        listOf(
            TopicDetailPartialChange.Agree.Start(threadId = 1, hasAgree = 1),
            TopicDetailPartialChange.Agree.Success(threadId = 1, hasAgree = 1),
            TopicDetailPartialChange.Agree.Failure(threadId = 1, hasAgree = 1, error = err),
        ).forEach { assertSame("TopicDetail.Agree 必须为 no-op", s, it.reduce(s)) }
    }

    @Test
    fun hotAgreeReducerIsNoOp() {
        val s = HotUiState()
        listOf(
            HotPartialChange.Agree.Start(threadId = 1, hasAgree = 1),
            HotPartialChange.Agree.Success(threadId = 1, hasAgree = 1),
            HotPartialChange.Agree.Failure(threadId = 1, hasAgree = 1, error = err),
        ).forEach { assertSame("Hot.Agree 必须为 no-op", s, it.reduce(s)) }
    }

    @Test
    fun personalizedAgreeReducerIsNoOp() {
        val s = PersonalizedUiState()
        listOf(
            PersonalizedPartialChange.Agree.Start(threadId = 1, hasAgree = 1),
            PersonalizedPartialChange.Agree.Success(threadId = 1, hasAgree = 1),
            PersonalizedPartialChange.Agree.Failure(threadId = 1, hasAgree = 1, error = err),
        ).forEach { assertSame("Personalized.Agree 必须为 no-op", s, it.reduce(s)) }
    }

    @Test
    fun concernAgreeReducerIsNoOp() {
        val s = ConcernUiState()
        listOf(
            ConcernPartialChange.Agree.Start(threadId = 1, hasAgree = 1),
            ConcernPartialChange.Agree.Success(threadId = 1, hasAgree = 1),
            ConcernPartialChange.Agree.Failure(threadId = 1, hasAgree = 1, error = err),
        ).forEach { assertSame("Concern.Agree 必须为 no-op", s, it.reduce(s)) }
    }

    @Test
    fun userPostAgreeReducerIsNoOp() {
        val s = UserPostUiState()
        listOf(
            UserPostPartialChange.Agree.Start(threadId = 1, postId = 2, hasAgree = 1),
            UserPostPartialChange.Agree.Success(threadId = 1, postId = 2, hasAgree = 1),
            UserPostPartialChange.Agree.Failure(threadId = 1, postId = 2, hasAgree = 1, error = err),
        ).forEach { assertSame("UserPost.Agree 必须为 no-op", s, it.reduce(s)) }
    }
}
