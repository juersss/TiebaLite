package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.core.network.model.protos.addThread.AddThreadResponse
import com.huanchengfly.tieba.post.core.network.model.protos.addPost.AddPostResponse
import com.huanchengfly.tieba.post.arch.GlobalEvent
import com.huanchengfly.tieba.post.arch.emitGlobalEvent
import com.huanchengfly.tieba.post.core.common.AppScope
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
    ): Flow<AddThreadResponse> =
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
                // （上游本提交把这里写回裸 GlobalScope + checkNotNull,本 fork 保留自己的加固,
                //   只跟随响应形态换成 AddThreadResponse:data_ 缺失/字段畸形一律静默跳过,不发事件）
                AppScope.launch {
                    runCatching {
                        val data = it.data_ ?: return@runCatching
                        val threadId = data.tid.toLongOrNull() ?: return@runCatching
                        val postId = data.pid.toLongOrNull() ?: return@runCatching
                        val msg = data.toast?.content
                            ?.joinToString("") { item -> item.text }
                            ?.takeIf { text -> text.isNotEmpty() }
                            ?: data.msg
                        emitGlobalEvent(GlobalEvent.AddThreadSuccess(threadId, postId, msg))
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