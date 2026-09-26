package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.core.network.model.protos.OriginThreadInfo
import com.huanchengfly.tieba.post.core.network.model.protos.User
import com.huanchengfly.tieba.post.core.network.model.protos.pbPage.PbPageResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaException
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.ui.page.thread.ThreadPageFrom
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object EmptyDataException : TiebaException("data is empty!") {
    override val code: Int
        get() = -2
}

object PbPageRepository {
    const val ST_TYPE_MENTION = "mention"
    const val ST_TYPE_STORE_THREAD = "store_thread"
    private val ST_TYPES = persistentListOf(ST_TYPE_MENTION, ST_TYPE_STORE_THREAD)

    /** pbPage 请求参数（B3 接缝：`from` 到 stType/mark 的映射在此完成，见 [pbPage]） */
    internal data class PbPageQuery(
        val threadId: Long,
        val page: Int,
        val postId: Long,
        val forumId: Long?,
        val seeLz: Boolean,
        val sortType: Int,
        val back: Boolean,
        val stType: String,
        val mark: Int,
        val lastPostId: Long?,
    )

    /**
     * 数据源接缝（B3，2026-09-14）。默认走 `TiebaApi.getInstance()`；
     * 单测注入 fake 以断言参数透传与 [transformPbPageResponse] 的整形逻辑。
     */
    internal var source: (PbPageQuery) -> Flow<PbPageResponse> = { query ->
        TiebaApi.getInstance()
            .pbPageFlow(
                query.threadId,
                query.page,
                postId = query.postId,
                seeLz = query.seeLz,
                sortType = query.sortType,
                back = query.back,
                forumId = query.forumId,
                stType = query.stType,
                mark = query.mark,
                lastPostId = query.lastPostId,
            )
    }

    fun pbPage(
        threadId: Long,
        page: Int = 1,
        postId: Long = 0,
        forumId: Long? = null,
        seeLz: Boolean = false,
        sortType: Int = 0,
        back: Boolean = false,
        from: String = "",
        lastPostId: Long? = null,
    ): Flow<PbPageResponse> =
        source(
            PbPageQuery(
                threadId = threadId,
                page = page,
                postId = postId,
                forumId = forumId,
                seeLz = seeLz,
                sortType = sortType,
                back = back,
                stType = from.takeIf { ST_TYPES.contains(it) }.orEmpty(),
                mark = if (from == ThreadPageFrom.FROM_STORE) 1 else 0,
                lastPostId = lastPostId,
            )
        ).map { transformPbPageResponse(it) }

    internal fun transformPbPageResponse(response: PbPageResponse): PbPageResponse {
        if (response.data_ == null) {
            throw TiebaUnknownException
        }
        if (response.data_!!.post_list.isEmpty()) {
            throw EmptyDataException
        }
        if (
            response.data_!!.page == null
            || response.data_!!.thread?.author == null
            || response.data_!!.forum == null
            || response.data_!!.anti == null
        ) {
            throw TiebaUnknownException
        }
        val userList = response.data_!!.user_list
        val postList = response.data_!!.post_list.map {
            // 作者档案被服务端裁剪/系统楼(author_id=0)时 user_list 里找不到:
            // first{} 会抛 NoSuchElementException 把整页加载打成失败,
            // 降级为占位 User(名字为空,UI 按 null 作者口径展示)
            val author = it.author
                ?: userList.firstOrNull { user -> user.id == it.author_id }
                ?: User(id = it.author_id)
            it.copy(
                author_id = author.id,
                author = author,
                from_forum = response.data_!!.forum,
                tid = response.data_!!.thread!!.id,
                sub_post_list = it.sub_post_list?.copy(
                    sub_post_list = it.sub_post_list!!.sub_post_list.map { subPost ->
                        subPost.copy(
                            author = subPost.author
                                ?: userList.firstOrNull { user -> user.id == subPost.author_id }
                                ?: User(id = subPost.author_id)
                        )
                    }
                ),
                origin_thread_info = OriginThreadInfo(
                    author = response.data_!!.thread!!.author
                )
            )
        }
        val firstPost = postList.firstOrNull { it.floor == 1 }
            ?: response.data_!!.first_floor_post?.copy(
                author_id = response.data_!!.thread!!.author!!.id,
                author = response.data_!!.thread!!.author,
                from_forum = response.data_!!.forum,
                tid = response.data_!!.thread!!.id,
                sub_post_list = response.data_!!.first_floor_post!!.sub_post_list?.copy(
                    sub_post_list = response.data_!!.first_floor_post!!.sub_post_list!!.sub_post_list.map { subPost ->
                        subPost.copy(
                            author = subPost.author
                                ?: userList.firstOrNull { user -> user.id == subPost.author_id }
                                ?: User(id = subPost.author_id)
                        )
                    }
                )
            )

        return response.copy(
            data_ = response.data_!!.copy(
                post_list = postList,
                first_floor_post = firstPost,
            )
        )
    }
}