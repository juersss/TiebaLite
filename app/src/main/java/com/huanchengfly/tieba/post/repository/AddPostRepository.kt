package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.AddThreadBean
import com.huanchengfly.tieba.post.api.models.protos.addPost.AddPostResponse
import com.huanchengfly.tieba.post.arch.GlobalEvent
import com.huanchengfly.tieba.post.arch.emitGlobalEvent
import com.huanchengfly.tieba.post.utils.AppScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

object AddPostRepository {
    fun addThread(
        content: String,
        forumId: Long,
        forumName: String,
        title: String? = "",
        isHide: Int? = 1,
        isTitle: Int? = 1
    ): Flow<AddThreadBean> =
        TiebaApi.getInstance()
            .addThreadFlow(
                content,
                forumName,
                forumId.toString(),
                title.orEmpty(),
                requireNotNull(isHide),
                requireNotNull(isTitle)
            ).onEach {
                // 兜底(R7-⑤,09-06 收口):裸 GlobalScope 协程体内 checkNotNull/事件发射异常会崩进程
                AppScope.launch {
                    runCatching {
                        emitGlobalEvent(
                            GlobalEvent.AddThreadSuccess(
                                checkNotNull(it.tid?.toLong()),
                                checkNotNull(it.pid?.toLong()),
                                checkNotNull(it.errorMsg),
                            )
                        )
                    }
                }
            }

    fun addPost(
        content: String,
        forumId: Long,
        forumName: String,
        threadId: Long,
        tbs: String? = null,
        nameShow: String? = null,
        postId: Long? = null,
        subPostId: Long? = null,
        replyUserId: Long? = null,
    ): Flow<AddPostResponse> =
        TiebaApi.getInstance()
            .addPostFlow(
                content,
                forumId.toString(),
                forumName,
                threadId.toString(),
                tbs,
                nameShow,
                postId?.toString(),
                subPostId?.toString(),
                replyUserId?.toString()
            )
            .onEach {
                // 兜底(R7-⑤,09-06 收口):checkNotNull 对畸形响应(缺 pid)抛
                // IllegalStateException,裸 GlobalScope 协程会把进程一起带走
                AppScope.launch {
                    runCatching {
                        val newPostId = checkNotNull(it.data_?.pid?.toLongOrNull())
                        if (postId != null) {
                            emitGlobalEvent(
                                GlobalEvent.ReplySuccess(
                                    threadId,
                                    postId,
                                    postId,
                                    subPostId,
                                    newPostId
                                )
                            )
                        } else {
                            emitGlobalEvent(GlobalEvent.ReplySuccess(threadId, newPostId))
                        }
                    }
                }
            }
}