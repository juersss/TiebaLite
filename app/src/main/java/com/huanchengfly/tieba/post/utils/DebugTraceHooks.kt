package com.huanchengfly.tieba.post.utils

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.ui.models.ThreadItemData
import com.huanchengfly.tieba.post.ui.page.forum.threadlist.ForumThreadListPartialChange
import com.huanchengfly.tieba.post.ui.page.forum.threadlist.ForumThreadListType
import com.huanchengfly.tieba.post.ui.page.forum.threadlist.ForumThreadListUiIntent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

/*
 * ═══════════════════════════════════════════════════════════════════════════
 * DBG-TRACE 诊断钩子(全部为多行逻辑的收拢层)——问题修复后可整体移除
 *
 * 移除步骤:
 *   1) 删除 DebugTraceHooks.kt、DebugTraceLog.kt、DebugTraceLogTest.kt 三个文件;
 *   2) grep -rn "debugTrace\|DebugTraceLog" app/src/main,删掉剩余的单行调用与
 *      import(全部以 DebugTrace/debugTrace 命名,一搜即中);
 *   3) grep -rn "DBG-LOG" app/src/main,删掉此前遗留的调试 Log.d 行(共 5 处);
 *   4) 编译 + run-tests.sh 全绿即完成。
 *
 * 设计约束:钩子内部一律先查 [DebugTraceLog.isActive],Release 构建直接返回,
 * 不创建任何 Effect/监听器;调用方因此可以无条件单行调用,零分支零成本。
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Application 入口:Debug 构建安装追踪,Release 直通(App.onCreate 调用) */
fun Application.debugTraceInstall() {
    if (DebugTraceLog.isActive) return
    DebugTraceLog.install(this)
}

/** 导航切换追踪:记录每一次页面进入(路由+参数),与吧列表位置日志对账 */
@Composable
fun NavHostController.debugTraceNavigation() {
    if (!DebugTraceLog.isActive) return
    DisposableEffect(this) {
        val listener = NavController.OnDestinationChangedListener { _, destination, arguments ->
            val bundle = arguments
            val args = bundle?.keySet().orEmpty().joinToString(",") { key ->
                "$key=${runCatching { bundle?.get(key)?.toString()?.take(40) }.getOrNull()}"
            }
            DebugTraceLog.log("NAV", "→ ${destination.route} args=[$args]")
        }
        addOnDestinationChangedListener(listener)
        onDispose { removeOnDestinationChangedListener(listener) }
    }
}

/** 吧主页追踪:进出时记录浏览位置快照,pager 切换单独记录 */
@Composable
fun debugTraceForumPage(forumName: String, listState: LazyListState, pagerState: PagerState) {
    if (!DebugTraceLog.isActive) return
    val tracedListState by rememberUpdatedState(listState)
    val tag = "FORUM_PAGE[$forumName]"
    DisposableEffect(forumName) {
        DebugTraceLog.log(
            tag,
            "enter position=${tracedListState.firstVisibleItemIndex}/${tracedListState.firstVisibleItemScrollOffset}"
        )
        onDispose {
            val st = tracedListState
            DebugTraceLog.log(
                tag,
                "exit position=${st.firstVisibleItemIndex}/${st.firstVisibleItemScrollOffset} " +
                    "key=${st.layoutInfo.visibleItemsInfo.firstOrNull()?.key}"
            )
        }
    }
    LaunchedEffect(pagerState) {
        var lastPager = pagerState.currentPage
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page != lastPager) {
                lastPager = page
                DebugTraceLog.log(tag, "pager switch → $page")
            }
        }
    }
}

/** 吧列表页追踪:进出位置快照 + 滚动进度记录(首可见项索引/锚点键) + 列表内容变更对账。
 * 滚动位置由 rememberLazyListState(rememberSaveable)按索引存进导航栈,
 * 恢复时若列表内容已变,索引会锚到别的内容——本钩子的日志用于还原这个错位过程 */
@Composable
fun debugTraceForumList(
    traceTag: String,
    listState: LazyListState,
    threadList: ImmutableList<ThreadItemData>,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    currentPage: Int,
) {
    if (!DebugTraceLog.isActive) return
    val tracedListState by rememberUpdatedState(listState)
    DisposableEffect(listState) {
        DebugTraceLog.log(
            traceTag,
            "enter savedPosition=${tracedListState.firstVisibleItemIndex}/${tracedListState.firstVisibleItemScrollOffset}"
        )
        onDispose {
            DebugTraceLog.log(
                traceTag,
                "exit position=${tracedListState.firstVisibleItemIndex}/${tracedListState.firstVisibleItemScrollOffset}"
            )
        }
    }
    // 进度记录:首可见项索引一变即记录(带锚点键与列表总数)
    LaunchedEffect(listState) {
        var lastIndex = -1
        snapshotFlow { tracedListState.firstVisibleItemIndex }.collect { index ->
            if (index != lastIndex) {
                lastIndex = index
                val first = tracedListState.layoutInfo.visibleItemsInfo.firstOrNull()
                DebugTraceLog.log(
                    traceTag,
                    "scroll index=$index offset=${tracedListState.firstVisibleItemScrollOffset} " +
                        "key=${first?.key} total=${tracedListState.layoutInfo.totalItemsCount}"
                )
            }
        }
    }
    // 列表内容变更:恢复锚点按索引对位,"索引 ↔ 帖子"对应关系靠这里对账
    LaunchedEffect(threadList) {
        DebugTraceLog.log(
            traceTag,
            "list size=${threadList.size} firstId=${threadList.firstOrNull()?.thread?.get { id }} " +
                "lastId=${threadList.lastOrNull()?.thread?.get { id }} " +
                "refreshing=$isRefreshing loadingMore=$isLoadingMore page=$currentPage"
        )
    }
}

/** 吧列表意图追踪分支:只记日志不发任何变更,并入 PartialChangeProducer 的 merge。
 * (Flow<Nothing> 是 Flow<PC> 的子类型;四个数据分支各自独立收集共享 intentFlow,
 * 追踪必须放独立分支,否则每条意图会随收集次数记多次) */
fun debugTraceIntentBranch(
    type: ForumThreadListType,
    intentFlow: Flow<ForumThreadListUiIntent>,
): Flow<ForumThreadListPartialChange> {
    if (!DebugTraceLog.isActive) return emptyFlow()
    return intentFlow
        .onEach { DebugTraceLog.log("FORUM_VM[$type]", "INTENT $it") }
        .mapNotNull<ForumThreadListUiIntent, ForumThreadListPartialChange> { null }
}

/** 吧列表数据变更的权威记录(dispatchEvent 处调用,页面日志只反映 uiState 投影)。
 * Refresh.Success 的 preserveList/尺寸直接关系列表替换与否,回退时先看这里 */
fun debugTraceForumListChange(vmTraceTag: String, partialChange: ForumThreadListPartialChange) {
    if (!DebugTraceLog.isActive) return
    when (partialChange) {
        is ForumThreadListPartialChange.FirstLoad.Success ->
            DebugTraceLog.log(
                vmTraceTag,
                "FirstLoad.Success size=${partialChange.threadList.size} " +
                    "firstId=${partialChange.threadList.firstOrNull()?.thread?.get { id }} " +
                    "hasMore=${partialChange.hasMore}"
            )

        is ForumThreadListPartialChange.Refresh.Success ->
            DebugTraceLog.log(
                vmTraceTag,
                "Refresh.Success preserveList=${partialChange.preserveList} " +
                    "newSize=${partialChange.threadList.size} " +
                    "firstId=${partialChange.threadList.firstOrNull()?.thread?.get { id }} " +
                    "hasMore=${partialChange.hasMore}"
            )

        is ForumThreadListPartialChange.LoadMore.Success ->
            DebugTraceLog.log(
                vmTraceTag,
                "LoadMore.Success page=${partialChange.currentPage} " +
                    "added=${partialChange.threadList.size} hasMore=${partialChange.hasMore}"
            )

        is ForumThreadListPartialChange.FirstLoad.Failure ->
            DebugTraceLog.log(vmTraceTag, "FirstLoad.Failure ${partialChange.error.getErrorMessage().take(100)}")

        is ForumThreadListPartialChange.Refresh.Failure ->
            DebugTraceLog.log(vmTraceTag, "Refresh.Failure ${partialChange.error.getErrorMessage().take(100)}")

        is ForumThreadListPartialChange.LoadMore.Failure ->
            DebugTraceLog.log(vmTraceTag, "LoadMore.Failure ${partialChange.error.getErrorMessage().take(100)}")

        else -> {}
    }
}
