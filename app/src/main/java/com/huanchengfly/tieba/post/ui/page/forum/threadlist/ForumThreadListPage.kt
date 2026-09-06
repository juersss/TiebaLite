package com.huanchengfly.tieba.post.ui.page.forum.threadlist

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.SnackbarResult
import androidx.compose.material.Text
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.utils.OpRecordStore
import com.huanchengfly.tieba.post.utils.DebugTraceLog
import com.huanchengfly.tieba.post.utils.debugTraceForumList
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.models.protos.OriginThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.User
import com.huanchengfly.tieba.post.api.models.protos.abstractText
import com.huanchengfly.tieba.post.api.models.protos.frsPage.Classify
import com.huanchengfly.tieba.post.arch.BaseComposeActivity.Companion.LocalWindowSizeClass
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.onEvent
import com.huanchengfly.tieba.post.arch.onGlobalEvent
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.theme.compose.pullRefreshIndicator
import com.huanchengfly.tieba.post.ui.common.windowsizeclass.WindowWidthSizeClass
import com.huanchengfly.tieba.post.ui.models.ThreadItemData
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ForumRuleDetailPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.ThreadPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.UserProfilePageDestination
import com.huanchengfly.tieba.post.ui.page.forum.getSortType
import com.huanchengfly.tieba.post.ui.widgets.compose.BlockTip
import com.huanchengfly.tieba.post.ui.widgets.compose.BlockableContent
import com.huanchengfly.tieba.post.ui.widgets.compose.Chip
import com.huanchengfly.tieba.post.ui.widgets.compose.FeedCard
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.LoadMoreLayout
import com.huanchengfly.tieba.post.ui.widgets.compose.LocalSnackbarHostState
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.VerticalDivider
import com.huanchengfly.tieba.post.ui.widgets.compose.debounceClickable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private fun getFirstLoadIntent(
    context: Context,
    forumName: String,
    isGood: Boolean = false,
): ForumThreadListUiIntent {
    return if (isGood) ForumThreadListUiIntent.Refresh(forumName, -1, 0)
    else ForumThreadListUiIntent.FirstLoad(forumName, getSortType(context, forumName), null)
}

private fun getRefreshIntent(
    context: Context,
    forumName: String,
    isGood: Boolean = false,
    sortType: Int = getSortType(context, forumName),
    goodClassifyId: Int? = if (isGood) 0 else null,
    preserveList: Boolean = false,
): ForumThreadListUiIntent {
    return if (isGood) ForumThreadListUiIntent.Refresh(forumName, -1, goodClassifyId, preserveList)
    else ForumThreadListUiIntent.Refresh(forumName, sortType, null, preserveList)
}

private fun getLoadMoreIntent(
    context: Context,
    forumId: Long,
    forumName: String,
    page: Int,
    threadListIds: List<Long>,
    isGood: Boolean = false,
): ForumThreadListUiIntent {
    return if (isGood) ForumThreadListUiIntent.LoadMore(forumId, forumName, page, threadListIds, 0)
    else ForumThreadListUiIntent.LoadMore(
        forumId,
        forumName,
        page,
        threadListIds,
        getSortType(context, forumName)
    )
}

private enum class ItemType {
    Top, PlainText, SingleMedia, MultiMedia, Video
}

@Composable
private fun GoodClassifyTabs(
    goodClassifyHolders: ImmutableList<ImmutableHolder<Classify>>,
    selectedItem: Int?,
    onSelected: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = goodClassifyHolders,
            key = { it.get { "${class_id}_$class_name" } }
        ) { holder ->
            Chip(
                text = holder.get { class_name },
                invertColor = selectedItem == holder.get { class_id },
                onClick = { onSelected(holder.get { class_id }) }
            )
        }
    }
}

@Composable
private fun TopThreadItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    type: String = stringResource(id = R.string.content_top),
) {
    Row(
        modifier = modifier
            .debounceClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Chip(
            text = type,
            shape = RoundedCornerShape(3.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.subtitle2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp
        )
    }
}

@Composable
private fun ThreadList(
    state: LazyListState,
    items: ImmutableList<ThreadItemData>,
    onItemClicked: (ThreadInfo) -> Unit,
    onItemReplyClicked: (ThreadInfo) -> Unit,
    onAgree: (ThreadInfo) -> Unit,
    forumRuleTitle: String? = null,
    onOpenForumRule: (() -> Unit)? = null,
    onOriginThreadClicked: (OriginThreadInfo) -> Unit = {},
    onUserClicked: (User) -> Unit = {},
) {
    val windowSizeClass = LocalWindowSizeClass.current
    val itemFraction = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 0.5f
        else -> 1f
    }
    MyLazyColumn(
        state = state,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = WindowInsets.navigationBars.asPaddingValues()
    ) {
        if (!forumRuleTitle.isNullOrEmpty()) {
            item(key = "ForumRule") {
                TopThreadItem(
                    title = forumRuleTitle,
                    onClick = {
                        onOpenForumRule?.invoke()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    type = stringResource(id = R.string.desc_forum_rule)
                )
            }
        }
        itemsIndexed(
            items = items,
            // key 必须与位置无关:带上 index 后,任何一次刷新/替换都会让全部 key 失效,
            // LazyColumn 的滚动锚点随之丢失(刷新后跳回顶部)。id 在 distinctById 后唯一
            key = { _, (holder) ->
                val (item) = holder
                item.id
            },
            contentType = { _, (holder) ->
                val (item) = holder
                if (item.isTop == 1) ItemType.Top
                else {
                    if (item.media.isNotEmpty())
                        if (item.media.size == 1) ItemType.SingleMedia else ItemType.MultiMedia
                    else if (item.videoInfo != null)
                        ItemType.Video
                    else ItemType.PlainText
                }
            }
        ) { index, (holder, blocked) ->
            BlockableContent(
                blocked = blocked,
                blockedTip = { BlockTip(text = { Text(text = stringResource(id = R.string.tip_blocked_thread)) }) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp),
            ) {
                val (item) = holder
                Column(
                    modifier = Modifier.fillMaxWidth(itemFraction)
                ) {
                    if (item.isTop == 1) {
                        val title = item.title.takeUnless { it.isBlank() } ?: item.abstractText
                        TopThreadItem(
                            title = title,
                            onClick = { onItemClicked(item) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        if (index > 0) {
                            if (items[index - 1].thread.get { isTop } == 1) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            VerticalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        FeedCard(
                            item = holder,
                            onClick = onItemClicked,
                            onClickReply = onItemReplyClicked,
                            onAgree = onAgree,
                            onClickOriginThread = onOriginThreadClicked,
                            onClickUser = onUserClicked
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ForumThreadListPage(
    forumId: Long,
    forumName: String,
    isGood: Boolean = false,
    viewModel: ForumThreadListViewModel = if (isGood) pageViewModel<GoodThreadListViewModel>() else pageViewModel<LatestThreadListViewModel>(),
    lazyListState: LazyListState = rememberLazyListState()
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val snackbarHostState = LocalSnackbarHostState.current

    // DBG-TRACE:吧列表进出/滚动进度/列表变更对账(诊断"进度偶尔回退",修复后移除)
    val traceTag = if (isGood) "GOOD_LIST[$forumName]" else "LATEST_LIST[$forumName]"

    // ── 浏览进度保持(生产修复,勿随 DBG-TRACE 一并移除;仅下方 log 行属诊断)──────
    // 病根(trace_20260907_030646.log 行 909 实证):从帖子页返回,saveable 恢复的
    // 滚动索引遇上"数据晚一帧"的空列表测量,被钳回顶部(27/1065 → 0)。
    // 对策:滚动时持续记录锚点(锚点帖 id+偏移),离开时暂存;数据落地后按锚点帖
    // 重新定位(与索引数字无关,不受空帧影响)。
    // 行为约定(2026-09-07 用户拍板):退主页再重进 = 新浏览归零(FirstLoad 时丢弃
    // 锚点);导航栈内返回 = 恢复位置。
    val browseCacheKey = ForumBrowseCache.key(forumName, isGood, if (isGood) -1 else getSortType(context, forumName))
    var lastAnchor by remember { mutableStateOf<ForumBrowseCache.Anchor?>(null) }
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }.collect {
            val first = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull()
            val key = first?.key
            if (key is Long) {
                lastAnchor = ForumBrowseCache.Anchor(key, lazyListState.firstVisibleItemScrollOffset)
            }
        }
    }
    DisposableEffect(lazyListState) {
        onDispose {
            ForumBrowseCache.markPendingRestore(browseCacheKey, lastAnchor)
        }
    }

    LazyLoad(loaded = viewModel.initialized) {
        val firstLoadIntent = getFirstLoadIntent(context, forumName, isGood)
        DebugTraceLog.log(traceTag, "SEND $firstLoadIntent")
        // 全新进入 = 新的一次浏览(用户拍板):丢弃旧锚点,从头开始
        ForumBrowseCache.consumeRestoreAnchor(browseCacheKey)
        viewModel.send(firstLoadIntent)
        viewModel.initialized = true
    }
    onGlobalEvent<ForumThreadListUiEvent.Refresh>(
        filter = { it.isGood == isGood },
    ) {
        DebugTraceLog.log(
            traceTag,
            "GOT RefreshEvent sortType=${it.sortType} preserveList=${it.preserveList}"
        )
        viewModel.send(getRefreshIntent(context, forumName, isGood, it.sortType, preserveList = it.preserveList))
    }
    onGlobalEvent<ForumThreadListUiEvent.BackToTop>(
        filter = { it.isGood == isGood },
    ) {
        DebugTraceLog.log(traceTag, "GOT BackToTop → animateScrollToItem(0)")
        lazyListState.animateScrollToItem(0)
    }
    viewModel.onEvent<ForumThreadListUiEvent.AgreeFail> {
        val snackbarResult = snackbarHostState.showSnackbar(
            message = context.getString(
                R.string.snackbar_agree_fail,
                it.errorCode,
                it.errorMsg
            ),
            actionLabel = context.getString(R.string.button_retry)
        )

        if (snackbarResult == SnackbarResult.ActionPerformed) {
            viewModel.send(
                ForumThreadListUiIntent.Agree(
                    it.threadId,
                    it.postId,
                    it.hasAgree
                )
            )
        }
    }
    val isRefreshing by viewModel.uiState.collectPartialAsState(
        prop1 = ForumThreadListUiState::isRefreshing,
        initial = false
    )
    val isLoadingMore by viewModel.uiState.collectPartialAsState(
        prop1 = ForumThreadListUiState::isLoadingMore,
        initial = false
    )
    val hasMore by viewModel.uiState.collectPartialAsState(
        prop1 = ForumThreadListUiState::hasMore,
        initial = true
    )
    val currentPage by viewModel.uiState.collectPartialAsState(
        prop1 = ForumThreadListUiState::currentPage,
        initial = 1
    )
    val forumRuleTitle by viewModel.uiState.collectPartialAsState(
        prop1 = ForumThreadListUiState::forumRuleTitle,
        initial = null
    )
    val threadList by viewModel.uiState.collectPartialAsState(
        prop1 = ForumThreadListUiState::threadList,
        initial = persistentListOf()
    )
    val threadListIds by viewModel.uiState.collectPartialAsState(
        prop1 = ForumThreadListUiState::threadListIds,
        initial = persistentListOf()
    )
    val goodClassifyId by viewModel.uiState.collectPartialAsState(
        prop1 = ForumThreadListUiState::goodClassifyId,
        initial = null
    )
    val goodClassifies by viewModel.uiState.collectPartialAsState(
        prop1 = ForumThreadListUiState::goodClassifies,
        initial = persistentListOf()
    )
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        // 下拉刷新保留已加载的旧列表(新帖合并到顶部),用户当前浏览位置不被顶走
        onRefresh = {
            DebugTraceLog.log(traceTag, "SEND Refresh(pull-to-refresh) preserveList=true")
            viewModel.send(getRefreshIntent(context, forumName, isGood, preserveList = true))
        }
    )
    // DBG-TRACE:列表内容变更对账 + 位置快照(滚动记录在钩子内)
    debugTraceForumList(
        traceTag,
        lazyListState,
        threadList,
        isRefreshing,
        isLoadingMore,
        currentPage
    )
    // RESTORE(生产修复,勿随 DBG-TRACE 移除):列表数据落地后按锚点帖恢复滚动位置,
    // 只消费一次。saveable 恢复的索引可能已被空帧钳掉,这里按"锚点帖"重新定位,
    // 与索引无关,不受首屏数据晚到影响。精品区不参与(重新开始语义)
    LaunchedEffect(threadList) {
        if (isGood || threadList.isEmpty()) return@LaunchedEffect
        val anchor = ForumBrowseCache.consumeRestoreAnchor(browseCacheKey) ?: return@LaunchedEffect
        val listIndex = threadList.indexOfFirst { it.thread.get { id } == anchor.key }
        if (listIndex >= 0) {
            val lazyIndex = listIndex + if (forumRuleTitle != null) 1 else 0
            DebugTraceLog.log(traceTag, "RESTORE anchor=${anchor.key} → lazyIndex=$lazyIndex offset=${anchor.offset}")
            lazyListState.scrollToItem(lazyIndex, anchor.offset)
        } else {
            DebugTraceLog.log(traceTag, "RESTORE miss anchor=${anchor.key}(不在列表中),放弃")
        }
    }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            if (isGood) {
                GoodClassifyTabs(
                    goodClassifyHolders = goodClassifies,
                    selectedItem = goodClassifyId,
                    onSelected = {
                        viewModel.send(
                            getRefreshIntent(
                                context,
                                forumName,
                                true,
                                goodClassifyId = it
                            )
                        )
                    }
                )
            }

            LoadMoreLayout(
                isLoading = isLoadingMore,
                onLoadMore = {
                    val loadMoreIntent = getLoadMoreIntent(
                        context,
                        forumId,
                        forumName,
                        currentPage,
                        threadListIds,
                        isGood
                    )
                    DebugTraceLog.log(traceTag, "SEND $loadMoreIntent")
                    viewModel.send(loadMoreIntent)
                },
                loadEnd = !hasMore,
                lazyListState = lazyListState,
                isEmpty = threadList.isEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                ThreadList(
                    state = lazyListState,
                    items = threadList,
                    onItemClicked = {
                        DebugTraceLog.log(traceTag, "NAVIGATE thread=${it.threadId}")
                        navigator.navigate(
                            ThreadPageDestination(
                                it.threadId,
                                forumId = it.forumId,
                                threadInfo = it
                            )
                        )
                    },
                    onItemReplyClicked = {
                        navigator.navigate(
                            ThreadPageDestination(
                                it.threadId,
                                forumId = it.forumId,
                                scrollToReply = true
                            )
                        )
                    },
                    onAgree = {
                        viewModel.send(
                            ForumThreadListUiIntent.Agree(
                                it.threadId,
                                it.firstPostId,
                                OpRecordStore.agreeFlag(
                                    AgreeParams.OBJ_THREAD, it.threadId,
                                    it.agree?.hasAgree ?: 0
                                )
                            )
                        )
                    },
                    forumRuleTitle = forumRuleTitle,
                    onOpenForumRule = {
                        navigator.navigate(ForumRuleDetailPageDestination(forumId))
                    },
                    onOriginThreadClicked = {
                        navigator.navigate(
                            ThreadPageDestination(
                                threadId = it.tid.toLong(),
                                forumId = it.fid,
                            )
                        )
                    }
                ) { navigator.navigate(UserProfilePageDestination(it.id)) }
            }
        }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = ExtendedTheme.colors.pullRefreshIndicator,
            contentColor = ExtendedTheme.colors.primary,
        )
    }
}