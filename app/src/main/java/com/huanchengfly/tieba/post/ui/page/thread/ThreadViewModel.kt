package com.huanchengfly.tieba.post.ui.page.thread

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.AnnotatedString
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.AgreeRateLimiter
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.protos.Anti
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorMessage
import com.huanchengfly.tieba.post.api.models.protos.MyAgreeOp
import com.huanchengfly.tieba.post.api.models.protos.OpRecord
import com.huanchengfly.tieba.post.api.models.protos.Agree
import com.huanchengfly.tieba.post.api.models.protos.reverted
import com.huanchengfly.tieba.post.api.models.protos.serverEchoOp
import com.huanchengfly.tieba.post.api.models.protos.OpAgreeResult
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorCode
import com.huanchengfly.tieba.post.api.models.protos.toOpAgreeResult
import com.huanchengfly.tieba.post.api.models.protos.Post
import com.huanchengfly.tieba.post.api.models.protos.SimpleForum
import com.huanchengfly.tieba.post.api.models.protos.SubPostList
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.User
import com.huanchengfly.tieba.post.api.models.protos.addPollPost.AddPollPostReponse
import com.huanchengfly.tieba.post.api.models.protos.contentRenders
import com.huanchengfly.tieba.post.api.models.protos.pbPage.PbPageResponse
import com.huanchengfly.tieba.post.api.models.protos.renders
import com.huanchengfly.tieba.post.api.models.protos.subPosts
import com.huanchengfly.tieba.post.api.models.protos.updateCollectStatus
import com.huanchengfly.tieba.post.api.models.protos.withForumFallback
import com.huanchengfly.tieba.post.utils.OpRecordStore
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.removeAt
import com.huanchengfly.tieba.post.repository.EmptyDataException
import com.huanchengfly.tieba.post.repository.PbPageRepository
import com.huanchengfly.tieba.post.ui.common.PbContentRender
import com.huanchengfly.tieba.post.utils.BlockManager.shouldBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

private fun ThreadInfo.getNextPagePostId(
    postIds: List<Long> = emptyList(),
    sortType: Int = ThreadSortType.SORT_TYPE_DEFAULT
): Long {
    val fetchedPostIds = pids.split(",")
        .filterNot { it.isBlank() }
        .map { it.toLong() }
    if (sortType == ThreadSortType.SORT_TYPE_DESC) {
        return fetchedPostIds.firstOrNull() ?: 0
    }
    val nextPostIds = fetchedPostIds.filterNot { pid -> postIds.contains(pid) }
    return if (nextPostIds.isNotEmpty()) nextPostIds.last() else 0
}

@Stable
@HiltViewModel
class ThreadViewModel @Inject constructor() :
    BaseViewModel<ThreadUiIntent, ThreadPartialChange, ThreadUiState, ThreadUiEvent>() {
    override fun createInitialState(): ThreadUiState = ThreadUiState()

    // 赞踩差分计数模型的记录表(进程级单例,与楼中楼详情页共享)
    init {
        OpRecordStore.init(App.INSTANCE)
    }

    val opRecords: StateFlow<Map<String, OpRecord>> get() = OpRecordStore.records

    private fun updateOpRecord(objType: Int, id: Long, transform: (OpRecord) -> OpRecord) =
        OpRecordStore.update(App.INSTANCE, objType, id, transform)

    /**
     * 为**本次重载涉及**的对象(主帖 + 本页楼层)按服务端回显批量播种初始记录
     * (历史点赞/其他端的点赞恢复)。已有记录的对象一律跳过——本地记录此后为准;
     * 无态度的对象不写记录。整页只写一次 prefs、只发射一次状态流
     * (逐条 confirm 会让 30 楼变成 30 轮写盘 + 全卡片失效)。
     */
    private fun seedFromPage(threadInfo: ThreadInfo, posts: List<PostItemData>) {
        val seeds = HashMap<String, MyAgreeOp>(posts.size + 1)
        threadInfo.agree.serverEchoOp().let { op ->
            if (op != MyAgreeOp.NONE) seeds[OpRecordStore.key(AgreeParams.OBJ_THREAD, threadInfo.id)] = op
        }
        posts.forEach { item ->
            val op = item.post.get { agree }.serverEchoOp()
            if (op != MyAgreeOp.NONE) {
                seeds[OpRecordStore.key(AgreeParams.OBJ_POST, item.post.get { id })] = op
            }
        }
        OpRecordStore.seedMissing(App.INSTANCE, seeds)
    }

    /**
     * 数据重载后只对齐**本次重载实际涉及**的对象(主帖 + 本页楼层)。
     *
     * 不能用全表无差别对齐:会把基准从未重载的历史对象
     * 也一并对齐,造成两类问题——
     * ① 在途请求随后失败时 `revertPending` 变成空操作(`my` 已等于 `server`),计数永久偏移;
     * ② 跨对象污染:在 A 帖点赞后进 B 帖翻页,A 的记录被对齐但基准没更新,
     *    出现"图标亮着但计数对不上"。
     */
    private fun rebaseLoaded(threadInfo: ThreadInfo, posts: List<PostItemData>) {
        val keys = HashSet<String>(posts.size + 1)
        keys.add(OpRecordStore.key(AgreeParams.OBJ_THREAD, threadInfo.id))
        posts.forEach { item ->
            keys.add(OpRecordStore.key(AgreeParams.OBJ_POST, item.post.get { id }))
        }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }

    override fun createPartialChangeProducer(): PartialChangeProducer<ThreadUiIntent, ThreadPartialChange, ThreadUiState> =
        ThreadPartialChangeProducer

    override fun dispatchEvent(partialChange: ThreadPartialChange): UiEvent? {
        return when (partialChange) {
            // ---- 赞踩:差分计数模型的记录更新,状态数字由 UI 从记录推导,reducer 不再改计数 ----
            is ThreadPartialChange.DisagreeThread.Start -> {
                updateOpRecord(AgreeParams.OBJ_THREAD, partialChange.threadId) {
                    it.copy(my = if (partialChange.hasDisagree) MyAgreeOp.DISAGREE else MyAgreeOp.NONE)
                }
                null
            }

            is ThreadPartialChange.DisagreePost.Start -> {
                updateOpRecord(AgreeParams.OBJ_POST, partialChange.postId) {
                    it.copy(my = if (partialChange.hasDisagree) MyAgreeOp.DISAGREE else MyAgreeOp.NONE)
                }
                null
            }

            is ThreadPartialChange.AgreeThread.Start -> {
                updateOpRecord(AgreeParams.OBJ_THREAD, partialChange.threadId) {
                    it.copy(my = if (partialChange.hasAgree) MyAgreeOp.AGREE else MyAgreeOp.NONE)
                }
                null
            }

            is ThreadPartialChange.AgreePost.Start -> {
                updateOpRecord(AgreeParams.OBJ_POST, partialChange.postId) {
                    it.copy(my = if (partialChange.hasAgree) MyAgreeOp.AGREE else MyAgreeOp.NONE)
                }
                null
            }

            // 点踩任意失败都明确提示;记录回退到服务端已确认状态
            is ThreadPartialChange.DisagreeThread.Failure -> {
                if (partialChange.errorCode != AgreeParams.RATE_LIMIT_ERROR_CODE) {
                    updateOpRecord(AgreeParams.OBJ_THREAD, partialChange.threadId) { it.reverted() }
                }
                CommonUiEvent.Toast(partialChange.errorMessage.ifBlank { "操作失败" })
            }

            is ThreadPartialChange.DisagreePost.Failure -> {
                if (partialChange.errorCode != AgreeParams.RATE_LIMIT_ERROR_CODE) {
                    updateOpRecord(AgreeParams.OBJ_POST, partialChange.postId) { it.reverted() }
                }
                CommonUiEvent.Toast(partialChange.errorMessage.ifBlank { "操作失败" })
            }

            is ThreadPartialChange.AgreeThread.Failure -> {
                if (partialChange.errorCode != AgreeParams.RATE_LIMIT_ERROR_CODE) {
                    updateOpRecord(AgreeParams.OBJ_THREAD, partialChange.threadId) { it.reverted() }
                }
                if (partialChange.errorCode == AgreeParams.RATE_LIMIT_ERROR_CODE) {
                    CommonUiEvent.Toast(partialChange.errorMessage)
                } else null
            }

            is ThreadPartialChange.AgreePost.Failure -> {
                if (partialChange.errorCode != AgreeParams.RATE_LIMIT_ERROR_CODE) {
                    updateOpRecord(AgreeParams.OBJ_POST, partialChange.postId) { it.reverted() }
                }
                if (partialChange.errorCode == AgreeParams.RATE_LIMIT_ERROR_CODE) {
                    CommonUiEvent.Toast(partialChange.errorMessage)
                } else null
            }

            // 赞的服务端权威拒绝静默对齐(点赞失败历来静默,仅对齐记录)
            is ThreadPartialChange.AgreeThread.AuthoritativeReject -> {
                val op = serverOpFromErrorCode(partialChange.code)
                updateOpRecord(AgreeParams.OBJ_THREAD, partialChange.threadId) {
                    it.copy(my = op, server = op)
                }
                null
            }

            is ThreadPartialChange.AgreePost.AuthoritativeReject -> {
                val op = serverOpFromErrorCode(partialChange.code)
                updateOpRecord(AgreeParams.OBJ_POST, partialChange.postId) {
                    it.copy(my = op, server = op)
                }
                null
            }

            // 点踩的服务端权威拒绝:无条件采纳服务端陈述的我的状态,并告知服务端原话
            is ThreadPartialChange.DisagreeThread.AuthoritativeReject -> {
                val op = serverOpFromErrorCode(partialChange.code)
                updateOpRecord(AgreeParams.OBJ_THREAD, partialChange.threadId) {
                    it.copy(my = op, server = op)
                }
                CommonUiEvent.Toast(partialChange.msg.ifBlank { partialChange.code })
            }

            is ThreadPartialChange.DisagreePost.AuthoritativeReject -> {
                val op = serverOpFromErrorCode(partialChange.code)
                updateOpRecord(AgreeParams.OBJ_POST, partialChange.postId) {
                    it.copy(my = op, server = op)
                }
                CommonUiEvent.Toast(partialChange.msg.ifBlank { partialChange.code })
            }

            // 数据重载:基准计数已包含本地已确认操作,对齐标记对齐当前意图
            is ThreadPartialChange.Load.Success -> {
                rebaseLoaded(partialChange.threadInfo, partialChange.data)
                // 楼层与主帖(OBJ_THREAD)统一批量播种(含主题,否则历史点赞的主题首次进入必显示未赞)
                seedFromPage(partialChange.threadInfo, partialChange.data)
                ThreadUiEvent.LoadSuccess(partialChange.currentPage)
            }

            is ThreadPartialChange.LoadFirstPage.Success -> {
                rebaseLoaded(partialChange.threadInfo, partialChange.data)
                seedFromPage(partialChange.threadInfo, partialChange.data)
                null
            }

            is ThreadPartialChange.LoadPrevious.Success -> {
                rebaseLoaded(partialChange.threadInfo, partialChange.data)
                seedFromPage(partialChange.threadInfo, partialChange.data)
                null
            }

            else -> null
        }
    }

    private object ThreadPartialChangeProducer :
        PartialChangeProducer<ThreadUiIntent, ThreadPartialChange, ThreadUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<ThreadUiIntent>): Flow<ThreadPartialChange> =
            merge(
                intentFlow.filterIsInstance<ThreadUiIntent.Init>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.Load>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.LoadMore>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.LoadFirstPage>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.LoadPrevious>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.LoadLatestPosts>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.LoadMyLatestReply>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.ToggleImmersiveMode>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.AddFavorite>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.RemoveFavorite>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.AgreeThread>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.PollThread>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.AgreePost>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.DisagreeThread>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.DisagreePost>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.DeletePost>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadUiIntent.DeleteThread>()
                    .flatMapConcat { it.producePartialChange() },
            )

        fun ThreadUiIntent.Init.producePartialChange(): Flow<ThreadPartialChange.Init> =
            flowOf<ThreadPartialChange.Init>(
                ThreadPartialChange.Init.Success(
                    threadInfo?.title.orEmpty(),
                    threadInfo?.author,
                    threadInfo,
                    threadInfo?.firstPostContent?.renders ?: emptyList(),
                    postId,
                    seeLz,
                    sortType,
                )
            ).catch { emit(ThreadPartialChange.Init.Failure(it)) }

        fun ThreadUiIntent.Load.producePartialChange(): Flow<ThreadPartialChange.Load> =
            PbPageRepository
                .pbPage(
                    threadId, page, postId, forumId, seeLz, sortType,
                    from = from.takeIf { it == ThreadPageFrom.FROM_STORE }.orEmpty()
                )
                .map<PbPageResponse, ThreadPartialChange.Load> { response ->
                    if (response.data_?.page == null
                        || response.data_.thread?.author == null
                        || response.data_.forum == null
                        || response.data_.anti == null
                    ) throw TiebaUnknownException
                    val postList = response.data_.post_list
                    // first_floor_post 常缺 from_forum,补齐后主楼层多图才能绑定大图浏览数据
                    val firstPost = response.data_.first_floor_post
                        ?.withForumFallback(response.data_.forum)
                    val notFirstPosts = postList.filterNot { it.floor == 1 }
                    ThreadPartialChange.Load.Success(
                        response.data_.thread.title,
                        response.data_.thread.author,
                        response.data_.user ?: User(),
                        firstPost,
                        notFirstPosts.map { PostItemData(it.wrapImmutable()) },
                        response.data_.thread,
                        response.data_.forum,
                        response.data_.anti,
                        response.data_.page.current_page,
                        response.data_.page.new_total_page,
                        response.data_.page.has_more != 0,
                        response.data_.thread.getNextPagePostId(
                            postList.map { it.id },
                            sortType
                        ),
                        response.data_.page.has_prev != 0,
                        firstPost?.contentRenders,
                        postId,
                        seeLz,
                        sortType,
                    )
                }
                .onStart { emit(ThreadPartialChange.Load.Start) }
                .catch { emit(ThreadPartialChange.Load.Failure(it)) }

        fun ThreadUiIntent.LoadFirstPage.producePartialChange(): Flow<ThreadPartialChange.LoadFirstPage> =
            PbPageRepository
                .pbPage(threadId, 0, 0, forumId, seeLz, sortType)
                .map<PbPageResponse, ThreadPartialChange.LoadFirstPage> { response ->
                    if (response.data_?.page == null
                        || response.data_.thread?.author == null
                        || response.data_.forum == null
                        || response.data_.anti == null
                    ) throw TiebaUnknownException
                    val postList = response.data_.post_list
                    // 同上:补齐 from_forum,避免主楼层多图大图翻页整体失效
                    val firstPost = response.data_.first_floor_post
                        ?.withForumFallback(response.data_.forum)
                    val notFirstPosts = postList.filterNot { it.floor == 1 }
                    ThreadPartialChange.LoadFirstPage.Success(
                        response.data_.thread.title,
                        response.data_.thread.author,
                        notFirstPosts.map { PostItemData(it.wrapImmutable()) },
                        response.data_.thread,
                        response.data_.page.current_page,
                        response.data_.page.new_total_page,
                        response.data_.page.has_more != 0,
                        response.data_.thread.getNextPagePostId(
                            postList.map { it.id },
                            sortType
                        ),
                        response.data_.page.has_prev != 0,
                        firstPost?.contentRenders ?: emptyList(),
                        postId = 0,
                        seeLz,
                        sortType,
                    )
                }
                .onStart { emit(ThreadPartialChange.LoadFirstPage.Start) }
                .catch { emit(ThreadPartialChange.LoadFirstPage.Failure(it)) }

        fun ThreadUiIntent.LoadMore.producePartialChange(): Flow<ThreadPartialChange.LoadMore> =
            PbPageRepository
                .pbPage(threadId, page, postId, forumId, seeLz, sortType)
                .map<PbPageResponse, ThreadPartialChange.LoadMore> { response ->
                    if (response.data_?.page == null
                        || response.data_.thread?.author == null
                        || response.data_.forum == null
                        || response.data_.anti == null
                    ) throw TiebaUnknownException
                    val postList = response.data_.post_list
                    val posts = postList.filterNot { it.floor == 1 || postIds.contains(it.id) }
                    ThreadPartialChange.LoadMore.Success(
                        response.data_.thread.author,
                        posts.map { PostItemData(it.wrapImmutable()) },
                        response.data_.thread,
                        response.data_.page.current_page,
                        response.data_.page.new_total_page,
                        response.data_.page.has_more != 0,
                        response.data_.thread.getNextPagePostId(
                            postIds + posts.map { it.id },
                            sortType
                        ),
                    )
                }
                .onStart { emit(ThreadPartialChange.LoadMore.Start) }
                .catch {
                    emit(
                        ThreadPartialChange.LoadMore.Failure(
                            it.getErrorCode(),
                            it.getErrorMessage()
                        )
                    )
                }

        fun ThreadUiIntent.LoadPrevious.producePartialChange(): Flow<ThreadPartialChange.LoadPrevious> =
            PbPageRepository
                .pbPage(threadId, page, postId, forumId, seeLz, sortType, back = true)
                .map<PbPageResponse, ThreadPartialChange.LoadPrevious> { response ->
                    if (response.data_?.page == null
                        || response.data_.thread?.author == null
                        || response.data_.forum == null
                        || response.data_.anti == null
                    ) throw TiebaUnknownException
                    val postList = response.data_.post_list
                    val posts = postList.filterNot { it.floor == 1 || postIds.contains(it.id) }
                    ThreadPartialChange.LoadPrevious.Success(
                        response.data_.thread.author,
                        posts.map { PostItemData(it.wrapImmutable()) },
                        response.data_.thread,
                        response.data_.page.current_page,
                        response.data_.page.new_total_page,
                        response.data_.page.has_prev != 0,
                    )
                }
                .onStart { emit(ThreadPartialChange.LoadPrevious.Start) }
                .catch {
                    emit(
                        ThreadPartialChange.LoadPrevious.Failure(
                            it.getErrorCode(),
                            it.getErrorMessage()
                        )
                    )
                }

        fun ThreadUiIntent.LoadLatestPosts.producePartialChange(): Flow<ThreadPartialChange.LoadLatestPosts> =
            PbPageRepository
                .pbPage(
                    threadId = threadId,
                    page = 0,
                    postId = curLatestPostId,
                    forumId = forumId,
                    seeLz = seeLz,
                    sortType = sortType,
                    lastPostId = curLatestPostId
                )
                .map { response ->
                    checkNotNull(response.data_)
                    checkNotNull(response.data_.thread)
                    checkNotNull(response.data_.thread.author)
                    checkNotNull(response.data_.page)
                    val postList = response.data_.post_list.filterNot { it.floor == 1 }
                    if (postList.isEmpty()) {
                        ThreadPartialChange.LoadLatestPosts.SuccessWithNoNewPost
                    } else {
                        ThreadPartialChange.LoadLatestPosts.Success(
                            author = response.data_.thread.author,
                            data = postList.map { PostItemData(it.wrapImmutable()) },
                            threadInfo = response.data_.thread,
                            currentPage = response.data_.page.current_page,
                            totalPage = response.data_.page.new_total_page,
                            hasMore = response.data_.page.has_more != 0,
                            nextPagePostId = response.data_.thread.getNextPagePostId(
                                postList.map { it.id },
                                sortType
                            ),
                        )
                    }
                }
                .onStart { emit(ThreadPartialChange.LoadLatestPosts.Start) }
                .catch {
                    if (it is EmptyDataException) {
                        emit(ThreadPartialChange.LoadLatestPosts.SuccessWithNoNewPost)
                    } else {
                        emit(ThreadPartialChange.LoadLatestPosts.Failure(it))
                    }
                }

        fun ThreadUiIntent.LoadMyLatestReply.producePartialChange(): Flow<ThreadPartialChange.LoadMyLatestReply> =
            PbPageRepository
                .pbPage(threadId, page = 0, postId = postId, forumId = forumId)
                .map<PbPageResponse, ThreadPartialChange.LoadMyLatestReply> { response ->
                    if (response.data_?.page == null
                        || response.data_.thread?.author == null
                        || response.data_.forum == null
                        || response.data_.anti == null
                    ) throw TiebaUnknownException
                    val firstLatestPost = response.data_.post_list.first()
                    ThreadPartialChange.LoadMyLatestReply.Success(
                        anti = response.data_.anti,
                        posts = response.data_.post_list.map { PostItemData(it.wrapImmutable()) },
                        page = response.data_.page.current_page,
                        isContinuous = firstLatestPost.floor == curLatestPostFloor + 1,
                        isDesc = isDesc,
                        hasNewPost = response.data_.post_list.any { !curPostIds.contains(it.id) },
                    )
                }
                .onStart { emit(ThreadPartialChange.LoadMyLatestReply.Start) }
                .catch { emit(ThreadPartialChange.LoadMyLatestReply.Failure(it)) }

        fun ThreadUiIntent.ToggleImmersiveMode.producePartialChange(): Flow<ThreadPartialChange.ToggleImmersiveMode> =
            flowOf(ThreadPartialChange.ToggleImmersiveMode.Success(isImmersiveMode))

        fun ThreadUiIntent.AddFavorite.producePartialChange(): Flow<ThreadPartialChange.AddFavorite> =
            TiebaApi.getInstance()
                .addStoreFlow(threadId, postId)
                .map { response ->
                    if (response.errorCode == 0) {
                        ThreadPartialChange.AddFavorite.Success(
                            postId, floor
                        )
                    } else ThreadPartialChange.AddFavorite.Failure(
                        response.errorCode,
                        response.errorMsg
                    )
                }
                .onStart { emit(ThreadPartialChange.AddFavorite.Start) }
                .catch {
                    emit(
                        ThreadPartialChange.AddFavorite.Failure(
                            it.getErrorCode(),
                            it.getErrorMessage()
                        )
                    )
                }

        fun ThreadUiIntent.RemoveFavorite.producePartialChange(): Flow<ThreadPartialChange.RemoveFavorite> =
            TiebaApi.getInstance()
                .removeStoreFlow(threadId, forumId, tbs)
                .map { response ->
                    if (response.errorCode == 0) {
                        ThreadPartialChange.RemoveFavorite.Success
                    } else ThreadPartialChange.RemoveFavorite.Failure(
                        response.errorCode,
                        response.errorMsg
                    )
                }
                .onStart { emit(ThreadPartialChange.RemoveFavorite.Start) }
                .catch {
                    emit(
                        ThreadPartialChange.RemoveFavorite.Failure(
                            it.getErrorCode(),
                            it.getErrorMessage()
                        )
                    )
                }

        fun ThreadUiIntent.AgreeThread.producePartialChange(): Flow<ThreadPartialChange.AgreeThread> {
            // 限流检查先于任何状态变更:被拦截的请求不产生 Start,也不改本地状态
            val acquired = AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(AgreeParams.OBJ_THREAD, threadId))
            // 配对撤销只在主操作被服务端接受/权威对齐时执行(下方 Ok/Authoritative 分支调用)。
            // 主操作被字符串错误码拒绝(Business)时 HTTP 200 不抛异常,撤销若照发会把一次
            // "拒绝"放大成反向漂移:服务端另一侧记录被真删、本地却回滚,两边各自漂移
            suspend fun undoDisagreeIfAccepted() {
                if (!undoDisagree) return
                // 服务端赞踩相互独立,点赞时需显式撤销已有的踩;撤销失败不回滚点赞
                runCatching {
                    if (!AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(AgreeParams.OBJ_THREAD, threadId), checkPerObject = false)) {
                        return@runCatching
                    }
                    TiebaApi.getInstance()
                        .opDisagreeFlow(
                            threadId.toString(),
                            postId.toString(),
                            objType = AgreeParams.OBJ_THREAD,
                            opType = AgreeParams.OP_UNDO
                        )
                        .collect { }
                }
            }
            return flow {
                if (!acquired) {
                    throw TiebaRateLimitedException()
                }
                emitAll(
                    TiebaApi.getInstance()
                        .opAgreeFlow(
                            threadId.toString(),
                            postId.toString(),
                            opType = if (agree) 0 else 1,
                            objType = 3
                        )
                )
            }
                .map<AgreeBean, ThreadPartialChange.AgreeThread> { bean ->
                    when (val result = bean.toOpAgreeResult(postId, agree)) {
                        is OpAgreeResult.Ok -> {
                            undoDisagreeIfAccepted()
                            ThreadPartialChange.AgreeThread.Success(threadId, agree)
                        }

                        is OpAgreeResult.Authoritative -> {
                            undoDisagreeIfAccepted()
                            ThreadPartialChange.AgreeThread.AuthoritativeReject(
                                threadId = threadId,
                                code = result.code,
                                msg = result.msg,
                            )
                        }

                        is OpAgreeResult.Business ->
                            ThreadPartialChange.AgreeThread.Failure(
                                threadId = threadId,
                                hasAgree = !agree,
                                errorCode = result.code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN,
                                errorMessage = result.msg,
                            )
                    }
                }
                .onStart {
                    if (acquired) emit(ThreadPartialChange.AgreeThread.Start(threadId, agree))
                }
                .catch {
                    val msg = it.getErrorMessage()
                    val authoritative = serverOpFromErrorMessage(msg)
                    emit(
                        if (authoritative != null) {
                            ThreadPartialChange.AgreeThread.AuthoritativeReject(
                                threadId = threadId,
                                code = msg,
                                msg = msg,
                            )
                        } else {
                            ThreadPartialChange.AgreeThread.Failure(
                                threadId,
                                !agree,
                                it.toOpAgreeErrorCode(),
                                msg
                            )
                        }
                    )
                }
        }

        fun ThreadUiIntent.PollThread.producePartialChange(): Flow<ThreadPartialChange.PollThread> =
            TiebaApi.getInstance()
                .addPollPostProtobuf(
                    forumId,
                    threadId,
                    options
                )
                .map<AddPollPostReponse, ThreadPartialChange.PollThread> {
                    ThreadPartialChange.PollThread.Success(
                        true
                    )
                }
                .catch {
                    emit(
                        ThreadPartialChange.PollThread.Failure(
                            false,
                            it.getErrorCode(),
                            it.getErrorMessage()
                        )
                    )
                }

        fun ThreadUiIntent.AgreePost.producePartialChange(): Flow<ThreadPartialChange.AgreePost> {
            // 限流检查先于任何状态变更:被拦截的请求不产生 Start,也不改本地状态
            // (tryAcquire 必须写在 flow{} 之外,否则 .onStart 会先发 Start 造成永久"幽灵赞")
            val acquired = AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(AgreeParams.OBJ_POST, postId))
            // 配对撤销只在主操作被服务端接受/权威对齐时执行(Business 拒绝不撤销,
            // 理由同 AgreeThread:字符串错误码路径不抛异常,撤销照发会反向漂移)
            suspend fun undoDisagreeIfAccepted() {
                if (!undoDisagree) return
                // 服务端赞踩相互独立,点赞时需显式撤销已有的踩;撤销失败不回滚点赞
                runCatching {
                    if (!AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(AgreeParams.OBJ_POST, postId), checkPerObject = false)) {
                        return@runCatching
                    }
                    TiebaApi.getInstance()
                        .opDisagreeFlow(
                            threadId.toString(),
                            postId.toString(),
                            objType = AgreeParams.OBJ_POST,
                            opType = AgreeParams.OP_UNDO
                        )
                        .collect { }
                }
            }
            return flow {
                // 赞与踩共用 /c/c/agree/opAgree 端点,风控限流必须覆盖两条路径
                if (!acquired) {
                    throw TiebaRateLimitedException()
                }
                emitAll(
                    TiebaApi.getInstance()
                        .opAgreeFlow(
                            threadId.toString(),
                            postId.toString(),
                            if (agree) 0 else 1,
                            objType = 1
                        )
                )
            }
                .map<AgreeBean, ThreadPartialChange.AgreePost> { bean ->
                    // 与 AgreeThread 同构的三态判定:服务端明确拒绝(如"你已赞过")不能记成成功
                    when (val result = bean.toOpAgreeResult(postId, agree)) {
                        is OpAgreeResult.Ok -> {
                            undoDisagreeIfAccepted()
                            ThreadPartialChange.AgreePost.Success(postId, agree)
                        }

                        is OpAgreeResult.Authoritative -> {
                            undoDisagreeIfAccepted()
                            ThreadPartialChange.AgreePost.AuthoritativeReject(
                                postId = postId,
                                code = result.code,
                                msg = result.msg,
                            )
                        }

                        is OpAgreeResult.Business ->
                            ThreadPartialChange.AgreePost.Failure(
                                postId = postId,
                                hasAgree = !agree,
                                errorCode = result.code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN,
                                errorMessage = result.msg,
                            )
                    }
                }
                .onStart { if (acquired) emit(ThreadPartialChange.AgreePost.Start(postId, agree)) }
                .catch {
                    // 错误可能由 FailureResponseInterceptor 以异常形式抛出(error_code 为数字时),
                    // 此时 .map 不会被调用,必须在这里识别服务端权威陈述,否则会盲目回滚
                    val msg = it.getErrorMessage()
                    val authoritative = serverOpFromErrorMessage(msg)
                    emit(
                        if (authoritative != null) {
                            ThreadPartialChange.AgreePost.AuthoritativeReject(
                                postId = postId,
                                code = msg,
                                msg = msg,
                            )
                        } else {
                            ThreadPartialChange.AgreePost.Failure(
                                postId,
                                !agree,
                                it.toOpAgreeErrorCode(),
                                msg
                            )
                        }
                    )
                }
        }

        fun ThreadUiIntent.DisagreeThread.producePartialChange(): Flow<ThreadPartialChange.DisagreeThread> {
            // 限流检查先于任何状态变更:被拦截的请求不产生 Start,也不改本地状态
            val acquired = AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(AgreeParams.OBJ_THREAD, threadId))
            // 配对撤销只在主操作被服务端接受/权威对齐时执行(Business 拒绝不撤销,
            // 理由同 AgreeThread:字符串错误码路径不抛异常,撤销照发会反向漂移)
            suspend fun undoAgreeIfAccepted() {
                if (!undoAgree) return
                // 点踩时显式撤销已有的赞;撤销失败不回滚踩
                runCatching {
                    if (!AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(AgreeParams.OBJ_THREAD, threadId), checkPerObject = false)) {
                        return@runCatching
                    }
                    TiebaApi.getInstance()
                        .opAgreeFlow(
                            threadId.toString(),
                            postId.toString(),
                            opType = AgreeParams.OP_UNDO,
                            objType = AgreeParams.OBJ_THREAD
                        )
                        .collect { }
                }
            }
            return flow {
                if (!acquired) {
                    throw TiebaRateLimitedException()
                }
                emitAll(
                    TiebaApi.getInstance()
                        .opDisagreeFlow(
                            threadId.toString(),
                            postId.toString(),
                            objType = AgreeParams.OBJ_THREAD,
                            opType = if (disagree) AgreeParams.OP_DO else AgreeParams.OP_UNDO
                        )
                )
            }
                .map<AgreeBean, ThreadPartialChange.DisagreeThread> { bean ->
                    when (val result = bean.toOpAgreeResult(postId, disagree)) {
                        is OpAgreeResult.Ok -> {
                            undoAgreeIfAccepted()
                            ThreadPartialChange.DisagreeThread.Success(threadId, disagree)
                        }

                        is OpAgreeResult.Authoritative -> {
                            undoAgreeIfAccepted()
                            ThreadPartialChange.DisagreeThread.AuthoritativeReject(
                                threadId = threadId,
                                code = result.code,
                                msg = result.msg,
                            )
                        }

                        is OpAgreeResult.Business ->
                            ThreadPartialChange.DisagreeThread.Failure(
                                threadId = threadId,
                                hasDisagree = !disagree,
                                errorCode = result.code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN,
                                errorMessage = result.msg,
                            )
                    }
                }
                .onStart { if (acquired) emit(ThreadPartialChange.DisagreeThread.Start(threadId, disagree)) }
                .catch {
                    // 错误可能由 FailureResponseInterceptor 以异常形式抛出（error_code 为数字时），
                    // 此时 .map 不会被调用，必须在这里识别服务端权威陈述，
                    // 否则会走通用回滚——把状态还原成「踩」，与服务端「已取消踩」相反。
                    val msg = it.getErrorMessage()
                    val authoritative = serverOpFromErrorMessage(msg)
                    emit(
                        if (authoritative != null) {
                            ThreadPartialChange.DisagreeThread.AuthoritativeReject(
                                threadId = threadId,
                                code = msg,
                                msg = msg,
                            )
                        } else {
                            ThreadPartialChange.DisagreeThread.Failure(
                                threadId,
                                !disagree,
                                it.toOpAgreeErrorCode(),
                                msg
                            )
                        }
                    )
                }
        }

        fun ThreadUiIntent.DisagreePost.producePartialChange(): Flow<ThreadPartialChange.DisagreePost> {
            // 限流检查先于任何状态变更:被拦截的请求不产生 Start,也不改本地状态
            val acquired = AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(AgreeParams.OBJ_POST, postId))
            // 配对撤销只在主操作被服务端接受/权威对齐时执行(Business 拒绝不撤销,
            // 理由同 AgreeThread:字符串错误码路径不抛异常,撤销照发会反向漂移)
            suspend fun undoAgreeIfAccepted() {
                if (!undoAgree) return
                // 点踩时显式撤销已有的赞;撤销失败不回滚踩
                runCatching {
                    if (!AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(AgreeParams.OBJ_POST, postId), checkPerObject = false)) {
                        return@runCatching
                    }
                    TiebaApi.getInstance()
                        .opAgreeFlow(
                            threadId.toString(),
                            postId.toString(),
                            opType = AgreeParams.OP_UNDO,
                            objType = AgreeParams.OBJ_POST
                        )
                        .collect { }
                }
            }
            return flow {
                if (!acquired) {
                    throw TiebaRateLimitedException()
                }
                emitAll(
                    TiebaApi.getInstance()
                        .opDisagreeFlow(
                            threadId.toString(),
                            postId.toString(),
                            objType = AgreeParams.OBJ_POST,
                            opType = if (disagree) AgreeParams.OP_DO else AgreeParams.OP_UNDO
                        )
                )
            }
                .map<AgreeBean, ThreadPartialChange.DisagreePost> { bean ->
                    when (val result = bean.toOpAgreeResult(postId, disagree)) {
                        is OpAgreeResult.Ok -> {
                            undoAgreeIfAccepted()
                            ThreadPartialChange.DisagreePost.Success(postId, disagree)
                        }

                        is OpAgreeResult.Authoritative -> {
                            undoAgreeIfAccepted()
                            ThreadPartialChange.DisagreePost.AuthoritativeReject(
                                postId = postId,
                                code = result.code,
                                msg = result.msg,
                            )
                        }

                        is OpAgreeResult.Business ->
                            ThreadPartialChange.DisagreePost.Failure(
                                postId = postId,
                                hasDisagree = !disagree,
                                errorCode = result.code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN,
                                errorMessage = result.msg,
                            )
                    }
                }
                .onStart { if (acquired) emit(ThreadPartialChange.DisagreePost.Start(postId, disagree)) }
                .catch {
                    // 同上：异常路径下也要识别服务端权威陈述，避免盲目回滚成「踩」
                    val msg = it.getErrorMessage()
                    val authoritative = serverOpFromErrorMessage(msg)
                    emit(
                        if (authoritative != null) {
                            ThreadPartialChange.DisagreePost.AuthoritativeReject(
                                postId = postId,
                                code = msg,
                                msg = msg,
                            )
                        } else {
                            ThreadPartialChange.DisagreePost.Failure(
                                postId,
                                !disagree,
                                it.toOpAgreeErrorCode(),
                                msg
                            )
                        }
                    )
                }
        }

        /**
         * 限流异常使用约定的错误码，便于 UI 层识别
         */
        fun Throwable.toOpAgreeErrorCode(): Int =
            if (this is TiebaRateLimitedException) AgreeParams.RATE_LIMIT_ERROR_CODE
            else getErrorCode()

        fun ThreadUiIntent.DeletePost.producePartialChange(): Flow<ThreadPartialChange.DeletePost> =
            TiebaApi.getInstance()
                .delPostFlow(forumId, forumName, threadId, postId, tbs, false, deleteMyPost)
                .map<CommonResponse, ThreadPartialChange.DeletePost> {
                    ThreadPartialChange.DeletePost.Success(postId)
                }
                .catch {
                    emit(
                        ThreadPartialChange.DeletePost.Failure(
                            it.getErrorCode(),
                            it.getErrorMessage()
                        )
                    )
                }

        fun ThreadUiIntent.DeleteThread.producePartialChange(): Flow<ThreadPartialChange.DeleteThread> =
            TiebaApi.getInstance()
                .delThreadFlow(forumId, forumName, threadId, tbs, deleteMyThread, false)
                .map<CommonResponse, ThreadPartialChange.DeleteThread> {
                    ThreadPartialChange.DeleteThread.Success
                }
                .catch {
                    emit(
                        ThreadPartialChange.DeleteThread.Failure(
                            it.getErrorCode(),
                            it.getErrorMessage()
                        )
                    )
                }
    }
}

sealed interface ThreadUiIntent : UiIntent {
    data class Init(
        val threadId: Long,
        val forumId: Long? = null,
        val postId: Long = 0,
        val threadInfo: ThreadInfo? = null,
        val seeLz: Boolean = false,
        val sortType: Int = 0,
    ) : ThreadUiIntent

    data class Load(
        val threadId: Long,
        val page: Int = 1,
        val postId: Long = 0,
        val forumId: Long? = null,
        val seeLz: Boolean = false,
        val sortType: Int = 0,
        val from: String = ""
    ) : ThreadUiIntent

    data class LoadFirstPage(
        val threadId: Long,
        val forumId: Long? = null,
        val seeLz: Boolean = false,
        val sortType: Int = 0
    ) : ThreadUiIntent

    data class LoadMore(
        val threadId: Long,
        val page: Int,
        val forumId: Long? = null,
        val postId: Long = 0,
        val seeLz: Boolean = false,
        val sortType: Int = 0,
        val postIds: List<Long> = emptyList(),
    ) : ThreadUiIntent

    data class LoadPrevious(
        val threadId: Long,
        val page: Int,
        val forumId: Long? = null,
        val postId: Long = 0,
        val seeLz: Boolean = false,
        val sortType: Int = 0,
        val postIds: List<Long> = emptyList(),
    ) : ThreadUiIntent

    /**
     * 加载当前贴子的最新回复
     */
    data class LoadLatestPosts(
        val threadId: Long,
        val curLatestPostId: Long,
        val forumId: Long? = null,
        val seeLz: Boolean = false,
        val sortType: Int = 0,
    ) : ThreadUiIntent

    /**
     * 当前用户发送新的回复时，加载用户发送的回复
     */
    data class LoadMyLatestReply(
        val threadId: Long,
        val postId: Long,
        val forumId: Long? = null,
        val isDesc: Boolean = false,
        val curLatestPostFloor: Int = 0,
        val curPostIds: List<Long> = emptyList(),
    ) : ThreadUiIntent

    data class ToggleImmersiveMode(
        val isImmersiveMode: Boolean,
    ) : ThreadUiIntent

    data class AddFavorite(
        val threadId: Long,
        val postId: Long,
        val floor: Int
    ) : ThreadUiIntent

    data class RemoveFavorite(
        val threadId: Long,
        val forumId: Long,
        val tbs: String?
    ) : ThreadUiIntent

    data class AgreeThread(
        val threadId: Long,
        val postId: Long,
        val agree: Boolean,
        val undoDisagree: Boolean = false
    ) : ThreadUiIntent

    data class PollThread(
        val forumId: Long?,
        val threadId: Long,
        val options: String,
    ) : ThreadUiIntent

    data class AgreePost(
        val threadId: Long,
        val postId: Long,
        val agree: Boolean,
        val undoDisagree: Boolean = false
    ) : ThreadUiIntent

    data class DisagreeThread(
        val threadId: Long,
        val postId: Long,
        val disagree: Boolean,
        val undoAgree: Boolean = false
    ) : ThreadUiIntent

    data class DisagreePost(
        val threadId: Long,
        val postId: Long,
        val disagree: Boolean,
        val undoAgree: Boolean = false
    ) : ThreadUiIntent

    data class DeletePost(
        val forumId: Long,
        val forumName: String,
        val threadId: Long,
        val postId: Long,
        val deleteMyPost: Boolean,
        val tbs: String? = null
    ) : ThreadUiIntent

    data class DeleteThread(
        val forumId: Long,
        val forumName: String,
        val threadId: Long,
        val deleteMyThread: Boolean,
        val tbs: String? = null
    ) : ThreadUiIntent
}

sealed interface ThreadPartialChange : PartialChange<ThreadUiState> {
    sealed class Init : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState = when (this) {
            is Success -> oldState.copy(
                isRefreshing = true,
                isError = false,
                error = null,
                title = title,
                author = if (author != null) wrapImmutable(author) else null,
                threadInfo = threadInfo?.wrapImmutable(),
                firstPost = if (threadInfo != null && author != null)
                    wrapImmutable(
                        Post(
                            title = title,
                            author = author,
                            floor = 1,
                            time = threadInfo.createTime
                        )
                    ) else null,
                firstPostContentRenders = firstPostContentRenders.toImmutableList(),
                postId = postId,
                seeLz = seeLz,
                sortType = sortType,
            )

            is Failure -> oldState.copy(
                isError = true,
                error = error.wrapImmutable()
            )
        }

        data class Success(
            val title: String,
            val author: User?,
            val threadInfo: ThreadInfo?,
            val firstPostContentRenders: List<PbContentRender>,
            val postId: Long = 0,
            val seeLz: Boolean = false,
            val sortType: Int = 0,
        ) : Init()

        data class Failure(
            val error: Throwable
        ) : Init()
    }

    sealed class Load : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState = when (this) {
            is Start -> oldState.copy(isRefreshing = true)

            is Success -> oldState.copy(
                isRefreshing = false,
                isError = false,
                error = null,
                title = title,
                author = wrapImmutable(author),
                user = wrapImmutable(user),
                data = data.toImmutableList(),
                threadInfo = threadInfo.wrapImmutable(),
                firstPost = if (firstPost != null) wrapImmutable(firstPost) else oldState.firstPost,
                forum = wrapImmutable(forum),
                anti = wrapImmutable(anti),
                currentPageMin = currentPage,
                currentPageMax = currentPage,
                totalPage = totalPage,
                hasMore = hasMore,
                nextPagePostId = nextPagePostId,
                hasPrevious = hasPrevious,
                firstPostContentRenders = firstPostContentRenders?.toImmutableList()
                    ?: oldState.firstPostContentRenders,
                latestPosts = persistentListOf(),
                postId = postId,
                seeLz = seeLz,
                sortType = sortType,
            )

            is Failure -> oldState.copy(
                isRefreshing = false,
                isError = true,
                error = error.wrapImmutable()
            )
        }

        data object Start : Load()

        data class Success(
            val title: String,
            val author: User,
            val user: User,
            val firstPost: Post?,
            val data: List<PostItemData>,
            val threadInfo: ThreadInfo,
            val forum: SimpleForum,
            val anti: Anti,
            val currentPage: Int,
            val totalPage: Int,
            val hasMore: Boolean,
            val nextPagePostId: Long,
            val hasPrevious: Boolean,
            val firstPostContentRenders: List<PbContentRender>?,
            val postId: Long = 0,
            val seeLz: Boolean = false,
            val sortType: Int = 0,
        ) : Load()

        data class Failure(
            val error: Throwable,
        ) : Load()
    }

    sealed class LoadFirstPage : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState = when (this) {
            is Start -> oldState.copy(isRefreshing = true)
            is Success -> oldState.copy(
                isRefreshing = false,
                isError = false,
                error = null,
                title = title,
                author = wrapImmutable(author),
                data = data.toImmutableList(),
                threadInfo = threadInfo.wrapImmutable(),
                currentPageMin = currentPage,
                currentPageMax = currentPage,
                totalPage = totalPage,
                hasMore = hasMore,
                nextPagePostId = nextPagePostId,
                hasPrevious = hasPrevious,
                firstPostContentRenders = firstPostContentRenders.toImmutableList(),
                latestPosts = persistentListOf(),
                postId = postId,
                seeLz = seeLz,
                sortType = sortType,
            )

            is Failure -> oldState.copy(
                isRefreshing = false,
                isError = true,
                error = error.wrapImmutable(),
            )
        }

        data object Start : LoadFirstPage()

        data class Success(
            val title: String,
            val author: User,
            val data: List<PostItemData>,
            val threadInfo: ThreadInfo,
            val currentPage: Int,
            val totalPage: Int,
            val hasMore: Boolean,
            val nextPagePostId: Long,
            val hasPrevious: Boolean,
            val firstPostContentRenders: List<PbContentRender>,
            val postId: Long,
            val seeLz: Boolean,
            val sortType: Int,
        ) : LoadFirstPage()

        data class Failure(
            val error: Throwable
        ) : LoadFirstPage()
    }

    sealed class LoadMore : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState = when (this) {
            is Start -> oldState.copy(isLoadingMore = true)
            is Success -> {
                val uniqueData = data.filterNot { item ->
                    oldState.data.any { it.post.get { id } == item.post.get { id } }
                }
                oldState.copy(
                    isLoadingMore = false,
                    author = wrapImmutable(author),
                    data = (oldState.data + uniqueData).toImmutableList(),
                    threadInfo = threadInfo.wrapImmutable(),
                    currentPageMax = currentPage,
                    totalPage = totalPage,
                    hasMore = hasMore,
                    nextPagePostId = nextPagePostId,
                    latestPosts = persistentListOf(),
                )
            }

            is Failure -> oldState.copy(isLoadingMore = false)
        }

        data object Start : LoadMore()

        data class Success(
            val author: User,
            val data: List<PostItemData>,
            val threadInfo: ThreadInfo,
            val currentPage: Int,
            val totalPage: Int,
            val hasMore: Boolean,
            val nextPagePostId: Long,
        ) : LoadMore()

        data class Failure(
            val errorCode: Int,
            val errorMessage: String
        ) : LoadMore()
    }

    sealed class LoadPrevious : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState = when (this) {
            is Start -> oldState.copy(isRefreshing = true)
            is Success -> oldState.copy(
                isRefreshing = false,
                author = wrapImmutable(author),
                data = (data + oldState.data).toImmutableList(),
                threadInfo = threadInfo.wrapImmutable(),
                currentPageMin = currentPage,
                totalPage = totalPage,
                hasPrevious = hasPrevious,
            )

            is Failure -> oldState.copy(isRefreshing = false)
        }

        data object Start : LoadPrevious()

        data class Success(
            val author: User,
            val data: List<PostItemData>,
            val threadInfo: ThreadInfo,
            val currentPage: Int,
            val totalPage: Int,
            val hasPrevious: Boolean,
        ) : LoadPrevious()

        data class Failure(
            val errorCode: Int,
            val errorMessage: String,
        ) : LoadPrevious()
    }

    sealed class LoadLatestPosts : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState = when (this) {
            Start -> oldState.copy(isLoadingMore = true)
            is Success -> {
                val uniqueData = data.filterNot { item ->
                    oldState.data.any { it.post.get { id } == item.post.get { id } }
                }
                oldState.copy(
                    isLoadingMore = false,
                    author = wrapImmutable(author),
                    data = (oldState.data + uniqueData).toImmutableList(),
                    threadInfo = threadInfo.wrapImmutable(),
                    currentPageMax = currentPage,
                    totalPage = totalPage,
                    hasMore = hasMore,
                    nextPagePostId = nextPagePostId,
                    latestPosts = persistentListOf(),
                )
            }

            SuccessWithNoNewPost -> oldState.copy(isLoadingMore = false)
            is Failure -> oldState.copy(isLoadingMore = false)
        }

        data object Start : LoadLatestPosts()

        data class Success(
            val author: User,
            val data: List<PostItemData>,
            val threadInfo: ThreadInfo,
            val currentPage: Int,
            val totalPage: Int,
            val hasMore: Boolean,
            val nextPagePostId: Long,
        ) : LoadLatestPosts()

        data object SuccessWithNoNewPost : LoadLatestPosts()

        data class Failure(
            val error: Throwable,
        ) : LoadLatestPosts()
    }

    sealed class LoadMyLatestReply : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState =
            when (this) {
                Start -> oldState.copy(isLoadingLatestReply = true)
                is Success -> {
                    val continuous = isContinuous || page == oldState.currentPageMax
                    val replacePostIndexes = oldState.data.mapIndexedNotNull { index, item ->
                        val replaceItemIndex =
                            posts.indexOfFirst { it.post.get { id } == item.post.get { id } }
                        if (replaceItemIndex != -1) index to replaceItemIndex else null
                    }
                    val newPost = oldState.data.mapIndexed { index, oldItem ->
                        val replaceIndex = replacePostIndexes.firstOrNull { it.first == index }
                        if (replaceIndex != null) posts[replaceIndex.second] else oldItem
                    }
                    val addPosts = posts.filter {
                        !newPost.any { item -> item.post.get { id } == it.post.get { id } }
                    }
                    when {
                        hasNewPost && continuous && isDesc -> {
                            oldState.copy(
                                isLoadingLatestReply = false,
                                isError = false,
                                error = null,
                                anti = anti.wrapImmutable(),
                                data = (addPosts.reversed() + newPost).toImmutableList(),
                                latestPosts = persistentListOf(),
                            )
                        }

                        hasNewPost && continuous && !isDesc -> {
                            oldState.copy(
                                isLoadingLatestReply = false,
                                isError = false,
                                error = null,
                                anti = anti.wrapImmutable(),
                                data = (newPost + addPosts).toImmutableList(),
                                latestPosts = persistentListOf(),
                            )
                        }

                        hasNewPost -> {
                            oldState.copy(
                                isLoadingLatestReply = false,
                                isError = false,
                                error = null,
                                anti = anti.wrapImmutable(),
                                data = newPost.toImmutableList(),
                                latestPosts = posts.toImmutableList(),
                            )
                        }

                        !hasNewPost -> {
                            oldState.copy(
                                isLoadingLatestReply = false,
                                isError = false,
                                error = null,
                                anti = anti.wrapImmutable(),
                                data = newPost.toImmutableList(),
                                latestPosts = persistentListOf(),
                            )
                        }

                        else -> {
                            oldState.copy(
                                isLoadingLatestReply = false,
                                isError = false,
                                error = null,
                            )
                        }
                    }
                }

                is Failure -> oldState.copy(
                    isLoadingLatestReply = false,
                    isError = true,
                    error = error.wrapImmutable(),
                )
            }

        object Start : LoadMyLatestReply()

        data class Success(
            val anti: Anti,
            val posts: List<PostItemData>,
            val page: Int,
            val isContinuous: Boolean,
            val isDesc: Boolean,
            val hasNewPost: Boolean,
        ) : LoadMyLatestReply()

        data class Failure(
            val error: Throwable,
        ) : LoadMyLatestReply()
    }

    sealed class ToggleImmersiveMode : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState = when (this) {
            is Success -> oldState.copy(isImmersiveMode = isImmersiveMode)
        }

        data class Success(
            val isImmersiveMode: Boolean
        ) : ToggleImmersiveMode()
    }

    sealed class AddFavorite : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState {
            return when (this) {
                Start -> oldState
                is Success -> oldState.copy(
                    threadInfo = oldState.threadInfo?.getImmutable {
                        updateCollectStatus(
                            newStatus = 1,
                            markPostId = markPostId
                        )
                    }
                )

                is Failure -> oldState
            }
        }

        object Start : AddFavorite()

        data class Success(
            val markPostId: Long,
            val floor: Int
        ) : AddFavorite()

        data class Failure(
            val errorCode: Int,
            val errorMessage: String
        ) : AddFavorite()
    }

    sealed class RemoveFavorite : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState {
            return when (this) {
                Start -> oldState
                Success -> oldState.copy(
                    threadInfo = oldState.threadInfo?.getImmutable {
                        updateCollectStatus(
                            newStatus = 0,
                            markPostId = 0
                        )
                    }
                )

                is Failure -> oldState
            }
        }

        object Start : RemoveFavorite()

        object Success : RemoveFavorite()

        data class Failure(
            val errorCode: Int,
            val errorMessage: String
        ) : RemoveFavorite()
    }

    sealed class AgreeThread : ThreadPartialChange {
        // 计数与我的态度均由 opRecords 差分推导,reducer 不再改动状态
        override fun reduce(oldState: ThreadUiState): ThreadUiState = oldState

        data class Start(
            val threadId: Long,
            val hasAgree: Boolean
        ) : AgreeThread()

        data class Success(
            val threadId: Long,
            val hasAgree: Boolean
        ) : AgreeThread()

        data class Failure(
            val threadId: Long,
            val hasAgree: Boolean,
            val errorCode: Int,
            val errorMessage: String
        ) : AgreeThread()

        data class AuthoritativeReject(
            val threadId: Long,
            val code: String,
            val msg: String,
        ) : AgreeThread()
    }

    sealed class PollThread : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState {
            return when (this) {

                is Success -> oldState.copy(
                    threadInfo = oldState.threadInfo?.getImmutable {
                        this.copy(
                            poll_info = this.poll_info?.copy(
                                is_polled = if (isPolled) 1 else 0
                            )
                        )
                    }
                )

                is Failure -> oldState
            }
        }

        data class Success(
            val isPolled: Boolean
        ) : PollThread()

        data class Failure(
            val isPolled: Boolean,
            val errorCode: Int,
            val errorMessage: String
        ) : PollThread()
    }

    sealed class AgreePost : ThreadPartialChange {
        // 计数与我的态度均由 opRecords 差分推导,reducer 不再改动状态
        override fun reduce(oldState: ThreadUiState): ThreadUiState = oldState

        data class Start(
            val postId: Long,
            val hasAgree: Boolean
        ) : AgreePost()

        data class Success(
            val postId: Long,
            val hasAgree: Boolean
        ) : AgreePost()

        data class Failure(
            val postId: Long,
            val hasAgree: Boolean,
            val errorCode: Int,
            val errorMessage: String
        ) : AgreePost()

        data class AuthoritativeReject(
            val postId: Long,
            val code: String,
            val msg: String,
        ) : AgreePost()
    }

    sealed class DisagreeThread : ThreadPartialChange {
        // 计数与我的态度均由 opRecords 差分推导,reducer 不再改动状态
        override fun reduce(oldState: ThreadUiState): ThreadUiState = oldState


        data class Start(
            val threadId: Long,
            val hasDisagree: Boolean
        ) : DisagreeThread()

        data class Success(
            val threadId: Long,
            val hasDisagree: Boolean
        ) : DisagreeThread()

        data class Failure(
            val threadId: Long,
            val hasDisagree: Boolean,
            val errorCode: Int,
            val errorMessage: String
        ) : DisagreeThread()

        /**
         * 服务端权威拒绝（ERR_USER_HAS_CANCEL_DISAGREE 等）。
         * 与 Failure 的区别：Failure 是「请求没成功，回滚」；
         * AuthoritativeReject 是「服务端陈述了真实状态，采纳」。
         */
        data class AuthoritativeReject(
            val threadId: Long,
            val code: String,
            val msg: String,
        ) : DisagreeThread()
    }

    sealed class DisagreePost : ThreadPartialChange {
        // 计数与我的态度均由 opRecords 差分推导,reducer 不再改动状态
        override fun reduce(oldState: ThreadUiState): ThreadUiState = oldState


        data class Start(
            val postId: Long,
            val hasDisagree: Boolean
        ) : DisagreePost()

        data class Success(
            val postId: Long,
            val hasDisagree: Boolean
        ) : DisagreePost()

        data class Failure(
            val postId: Long,
            val hasDisagree: Boolean,
            val errorCode: Int,
            val errorMessage: String
        ) : DisagreePost()

        /**
         * 服务端权威拒绝（ERR_USER_HAS_CANCEL_DISAGREE 等）。
         * 与 Failure 的区别：Failure 是「请求没成功，回滚」；
         * AuthoritativeReject 是「服务端陈述了真实状态，采纳」。
         */
        data class AuthoritativeReject(
            val postId: Long,
            val code: String,
            val msg: String,
        ) : DisagreePost()
    }

    sealed class DeletePost : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState = when (this) {
            is Success -> {
                val deletedPostIndex = oldState.data.indexOfFirst { it.post.get { id } == postId }
                // 未命中(楼层已被并发删除/重复事件)时保持原列表:removeAt(-1) 会抛越界
                oldState.copy(
                    data = if (deletedPostIndex >= 0) oldState.data.removeAt(deletedPostIndex) else oldState.data,
                )
            }

            is Failure -> oldState
        }

        data class Success(
            val postId: Long
        ) : DeletePost()

        data class Failure(
            val errorCode: Int,
            val errorMessage: String
        ) : DeletePost()
    }

    sealed class DeleteThread : ThreadPartialChange {
        override fun reduce(oldState: ThreadUiState): ThreadUiState = oldState

        object Success : DeleteThread()

        data class Failure(
            val errorCode: Int,
            val errorMessage: String
        ) : DeleteThread()
    }
}

data class ThreadUiState(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingLatestReply: Boolean = false,
    val isError: Boolean = false,
    val error: ImmutableHolder<Throwable>? = null,

    val hasMore: Boolean = true,
    val nextPagePostId: Long = 0,
    val hasPrevious: Boolean = false,
    val currentPageMin: Int = 0,
    val currentPageMax: Int = 0,
    val totalPage: Int = 0,

    val seeLz: Boolean = false,
    val sortType: Int = ThreadSortType.SORT_TYPE_DEFAULT,
    val postId: Long = 0,

    val title: String = "",
    val author: ImmutableHolder<User>? = null,
    val user: ImmutableHolder<User> = wrapImmutable(User()),
    val threadInfo: ImmutableHolder<ThreadInfo>? = null,
    val firstPost: ImmutableHolder<Post>? = null,
    val forum: ImmutableHolder<SimpleForum>? = null,
    val anti: ImmutableHolder<Anti>? = null,

    val firstPostContentRenders: ImmutableList<PbContentRender> = persistentListOf(),
    val data: ImmutableList<PostItemData> = persistentListOf(),
    val latestPosts: ImmutableList<PostItemData> = persistentListOf(),

    val isImmersiveMode: Boolean = false,

) : UiState

sealed interface ThreadUiEvent : UiEvent {
    data object ScrollToFirstReply : ThreadUiEvent

    data object ScrollToLatestReply : ThreadUiEvent

    data class LoadSuccess(
        val page: Int
    ) : ThreadUiEvent

    data class AddFavoriteSuccess(val floor: Int) : ThreadUiEvent

    data object RemoveFavoriteSuccess : ThreadUiEvent
}

object ThreadSortType {
    const val SORT_TYPE_ASC = 0
    const val SORT_TYPE_DESC = 1
    const val SORT_TYPE_HOT = 2
    const val SORT_TYPE_DEFAULT = SORT_TYPE_ASC
}

@Immutable
data class PostItemData(
    val post: ImmutableHolder<Post>,
    val blocked: Boolean = post.get { shouldBlock() },
    val contentRenders: ImmutableList<PbContentRender> = post.get { this.contentRenders },
    val subPosts: ImmutableList<SubPostItemData> = post.get { this.subPosts },
)

@Immutable
data class SubPostItemData(
    val subPost: ImmutableHolder<SubPostList>,
    val subPostContent: AnnotatedString,
    val contentRenders: ImmutableList<PbContentRender> = persistentListOf(),
    val blocked: Boolean = subPost.get { shouldBlock() },
) {
    val id: Long
        get() = subPost.get { id }

    val author: ImmutableHolder<User>?
        get() = subPost.get { author }?.wrapImmutable()
}