package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.AgreeRateLimiter
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.core.network.model.protos.MyAgreeOp
import com.huanchengfly.tieba.post.core.network.model.protos.OpAgreeResult
import com.huanchengfly.tieba.post.core.network.model.protos.serverOpFromErrorMessage
import com.huanchengfly.tieba.post.core.network.model.protos.serverOpFromErrorCode
import com.huanchengfly.tieba.post.core.network.model.protos.toOpAgreeResult
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * 列表页主帖点赞链路的公共执行器(B1)与赞踩记录收口的唯一权威(E2)。
 *
 * ## 为什么抽这个类
 *
 * 吧内列表 / 关注流 / 热榜 / 个人主页 / 话题详情 / 精选tab 共 7 个 ViewModel
 * 各自复制了同一段"限流预检 → 配对撤销判定 → opAgreeFlow → errorCode 三态判定 →
 * 记录表收尾"的链路(合计 ~600 行字面级重复)。7 份复制曾出现同一修复
 * (权威码异常路径采纳)被打 7 遍补丁的状况;E2 的"请求 Ok 清在途标志"若不抽公共,
 * 又要改 7 处。本类把链路收敛为单一实现,各 ViewModel 只剩"中性结果 →
 * 自己的 PartialChange/UiEvent"的类型映射。
 *
 * 帖子页([com.huanchengfly.tieba.post.ui.page.thread.ThreadViewModel])与楼中楼页
 * ([com.huanchengfly.tieba.post.ui.page.subposts.SubPostsViewModel])**不在**本类收口范围:
 * 两者的赞踩对象多(objType 混用 THREAD/POST/SUB_POST)、PartialChange 结构页面特化
 * (独立的 AuthoritativeReject 分支与失败提示口径),强行抽象会产生参数爆炸。
 * 二者的 E2 语义修正(Ok 清在途标志)直接在各自链路内完成。
 *
 * ## E2 语义(与 [OpRecordStore] 的文档一致)
 *
 * - `OpAgreeResult.Ok` → [OpRecordStore.clearInFlight]:请求成功收尾,清在途标志,
 *   意图/对齐标记都不动——乐观偏移保留到下一次数据重载时由 rebase 对齐,
 *   这是旧模型"刷新后 ±1 漂移不自愈"死锁的解除点;
 * - `Authoritative` → confirm:服务端权威陈述,无条件采纳;
 * - `Business` / 网络失败 → 不写记录,回滚交给各页 dispatchEvent([onFailure]);
 * - 限流拦截 → 请求从未进入记录表,绝不回退。
 */
sealed interface ListAgreeOutcome {

    /** 限流预检通过,主请求已发出:乐观意图进记录表(dispatchEvent 的 Start 分支调用 [onStart]) */
    data class Started(val newHasAgree: Int) : ListAgreeOutcome

    /** 服务端接受(Ok,已清在途标志)或权威对齐(Authoritative,已 confirm) */
    data class Accepted(val authoritative: Boolean) : ListAgreeOutcome

    /** 失败:网络异常 / 业务拒绝 / 限流。[rateLimited]=true 表示请求从未进入记录表 */
    data class Rejected(val error: Throwable, val rateLimited: Boolean) : ListAgreeOutcome
}

object AgreeOpRunner {

    /** dispatchEvent 收到 Start 时调用:乐观意图进记录表(与帖子页同一张表) */
    fun onStartAgree(threadId: Long, hasAgreeFlag: Int) {
        OpRecordStore.setPending(
            App.INSTANCE,
            AgreeParams.OBJ_THREAD,
            threadId,
            if (hasAgreeFlag == 1) MyAgreeOp.AGREE else MyAgreeOp.NONE
        )
    }

    /**
     * dispatchEvent 收到 Failure 时调用:回滚未确认的乐观意图。
     * 限流拦截的异常不是 TiebaException,必须按类型判定——被拦截的请求从未
     * setPending,绝不 revertPending(否则凭空写出 my=server=NONE 的记录,
     * 永久屏蔽服务端回显)。
     */
    fun onFailureAgree(threadId: Long, error: Throwable) {
        if (error !is TiebaRateLimitedException) {
            OpRecordStore.revertPending(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId)
        }
    }

    /** 列表重载后调用:把本次重载实际涉及的对象 keys 交给 [OpRecordStore.rebase] */
    fun rebaseLoaded(objType: Int, objIds: Collection<Long>) {
        if (objIds.isEmpty()) return
        val keys = HashSet<String>(objIds.size)
        objIds.forEach { keys.add(OpRecordStore.key(objType, it)) }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }

    /**
     * 列表页主帖点赞完整链路(单一实现,7 个列表页共用):
     * 限流预检 → 配对撤销踩判定 → opAgreeFlow → errorCode 三态判定 → 记录表收尾。
     *
     * [forumId] 为 E1 协议补齐项:官方 opAgree 跨约 10 个大版本均必带 forum_id,
     * 调用方从列表项 ThreadInfo 透传;为 null 时不发送该字段(与旧行为一致,服务端
     * 实测仍记账,S5 实证)。配对撤销踩的请求同样携带。
     *
     * 配对撤销:此前在帖子页点过踩的对象,列表页点赞会先撤销踩(服务端赞踩相互
     * 独立,不撤销则踩变孤儿)。撤销仅在主操作被服务端接受/权威对齐时执行,
     * Business 拒绝不撤销(防把拒绝放大成反向漂移)。意图判定直读 prefs 真值:
     * 异步 init 窗口内内存表未加载,读内存表会把 prefs 已有的踩判成无 →
     * 配对撤销失效 → 服务端孤儿踩。
     */
    fun threadAgree(
        threadId: Long,
        postId: Long,
        hasAgree: Int,
        forumId: Long? = null,
    ): Flow<ListAgreeOutcome> {
        // 限流预检在任何状态变更之前:被拦截不产生 Started、记录表零变更
        val acquired = AgreeRateLimiter.tryAcquire(
            AgreeRateLimiter.keyFor(AgreeParams.OBJ_THREAD, threadId)
        )
        val undoDisagree =
            OpRecordStore.currentMy(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId) ==
                MyAgreeOp.DISAGREE
        suspend fun undoDisagreeIfAccepted() {
            if (!undoDisagree) return
            runCatching {
                if (!AgreeRateLimiter.tryAcquire(
                        AgreeRateLimiter.keyFor(AgreeParams.OBJ_THREAD, threadId),
                        checkPerObject = false
                    )
                ) return@runCatching
                TiebaApi.getInstance()
                    .opDisagreeFlow(
                        threadId.toString(),
                        postId.toString(),
                        objType = AgreeParams.OBJ_THREAD,
                        opType = AgreeParams.OP_UNDO,
                        forumId = forumId?.toString(),
                    )
                    .collect { }
            }
        }
        return flow {
            if (!acquired) throw TiebaRateLimitedException()
            // opType 语义注意:hasAgree 是**点击前**旗标(0=未赞→0 执行赞;1=已赞→1 撤销赞),
            // 与 opType 的 0/1 编码恰好互补等价——7 个列表页历史上即如此传参,勿"修正"成恒 DO
            emitAll(
                TiebaApi.getInstance().opAgreeFlow(
                    threadId.toString(),
                    postId.toString(),
                    hasAgree,
                    objType = AgreeParams.OBJ_THREAD,
                    forumId = forumId?.toString(),
                )
            )
        }.map<AgreeBean, ListAgreeOutcome> { bean ->
            // HTTP 200 不等于业务成功:先按 errorCode 做三态判定,再决定记录表动作
            when (val result = bean.toOpAgreeResult(threadId, (hasAgree xor 1) == 1)) {
                // Ok:请求成功收尾——清在途标志,意图/对齐标记都不动(见类文档 E2 语义)。
                // 此处不 confirm:列表基准尚未包含本次操作,对齐会抵消乐观偏移(数字弹回)
                is OpAgreeResult.Ok -> {
                    OpRecordStore.clearInFlight(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId)
                    undoDisagreeIfAccepted()
                    ListAgreeOutcome.Accepted(authoritative = false)
                }

                is OpAgreeResult.Authoritative -> {
                    // 服务端权威陈述("你已赞过"等):无条件采纳,my 与 server 一并对齐过去
                    OpRecordStore.confirm(
                        App.INSTANCE,
                        AgreeParams.OBJ_THREAD,
                        threadId,
                        serverOpFromErrorCode(result.code)
                    )
                    undoDisagreeIfAccepted()
                    ListAgreeOutcome.Accepted(authoritative = true)
                }

                is OpAgreeResult.Business -> {
                    // 普通业务拒绝:不 confirm(否则 my=server 落盘永不自愈),
                    // 回滚统一交给各页 dispatchEvent(onFailureAgree,与网络失败同路)
                    ListAgreeOutcome.Rejected(
                        TiebaApiException(
                            CommonResponse(
                                result.code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN,
                                result.msg
                            )
                        ),
                        rateLimited = false
                    )
                }
            }
        }
            .catch {
                // 数字 error_code 经 FailureResponseInterceptor 抛异常时 .map 不被调用;
                // 服务端权威陈述(如"你已赞过")必须采纳而非回滚。限流异常消息不匹配
                // 任何权威模式,仍走普通失败
                val authoritative = serverOpFromErrorMessage(it.getErrorMessage())
                if (authoritative == null) {
                    emit(ListAgreeOutcome.Rejected(it, it is TiebaRateLimitedException))
                } else {
                    OpRecordStore.confirm(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId, authoritative)
                    undoDisagreeIfAccepted()
                    emit(ListAgreeOutcome.Accepted(authoritative = true))
                }
            }
            // 收集被取消(返回键销毁 ViewModel / 页面重建)也要终结:
            // Started 经 dispatchEvent 落到 setPending(inFlight=true) 后,若本流被取消,
            // map/catch 两条终结路径都不执行——该对象之后的每次 rebase 都把它当在途跳过,
            // 服务端已记账时显示偏移永不自愈(只有杀进程恢复)。以**内存表**的
            // inFlight=true 为守卫:只有"确实已置在途"(Started 已被消费)才回滚,
            // 避免取消过早时凭空写出 my=server=NONE 的幽灵压制记录(与 onFailureAgree
            // 的限流防护同理);内存表随账号切换整体重载,守卫天然落在当前账号上。
            .onCompletion { cause ->
                if (cause != null && acquired &&
                    OpRecordStore.records.value[OpRecordStore.key(AgreeParams.OBJ_THREAD, threadId)]
                        ?.inFlight == true
                ) {
                    OpRecordStore.revertPending(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId)
                }
            }
            .onStart { if (acquired) emit(ListAgreeOutcome.Started(hasAgree xor 1)) }
    }
}
