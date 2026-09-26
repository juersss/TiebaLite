package com.huanchengfly.tieba.post.selfcheck

import android.content.Context
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.core.common.upgradeImageUrlToHttps
import com.huanchengfly.tieba.post.core.network.model.protos.frsPage.FrsPageResponse
import com.huanchengfly.tieba.post.core.network.model.protos.pbPage.PbPageResponse
import com.huanchengfly.tieba.post.repository.FrsPageRepository
import com.huanchengfly.tieba.post.repository.PbPageRepository
import com.huanchengfly.tieba.post.ui.common.PicContentRender
import com.huanchengfly.tieba.post.ui.common.renders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * 网络**只读**链路组。
 *
 * 全部走生产入口（[FrsPageRepository] / [PbPageRepository] / `TiebaApi`），
 * 断言的是"生产代码认得的那些字段确实到位"——请求参数被改坏、响应结构变了、
 * 帖子串了号，都会在这里红。
 *
 * ★ 红线：本文件只发查询请求。赞/踩只跑本地差分模型（纯逻辑组），
 *   发帖/回复/投票提交一律不测。
 * ★ 时间上限：任何单次请求不超过 [NET_TIMEOUT_MS]，避免自检面板挂死在网络等待上。
 */
internal const val NET_TIMEOUT_MS = 20_000L

/**
 * 楼中楼图片链用例最多试几个帖 / 每帖几层"带楼中楼的楼层"。
 *
 * 楼中楼有没有图取决于运气（很多楼纯文字），试一个经常只能跳过；跨帖多试提高命中率，
 * 但不能无限试——每次都是一条真实网络请求，面板要能在一分钟内跑完。
 */
private const val MAX_THREAD_PROBES = 3
private const val MAX_FLOOR_PROBES = 2

/** 低版本（未伪装 V22）时服务端给楼中楼图片回的占位文字（README §7.2） */
private const val PIC_PLACEHOLDER = "[图片]"

/**
 * 固定探针帖：用户 2026-09-26 提供的"楼中楼里有图"的帖子
 * （`https://tieba.baidu.com/p/10855934269`）。
 *
 * 为什么需要它：只靠"吧页前几个帖"探测时，绝大多数帖的楼中楼是纯文字，这条用例会
 * **常年跳过**（实测试了 3 帖 / 5 层仍无图）——等于没有回归网。固定一个已知有图的帖，
 * 用例才能真正断言；探针帖失效时自动回落到吧页探测。
 */
private const val PROBE_SUBPOST_THREAD_ID = 10_855_934_269L

/**
 * 探针吧候选。用固定吧名而不是"取关注列表第一个"：
 * 只读用例不该依赖账号状态；候选表让单个吧被封/清空时不至于整组红。
 */
private val PROBE_FORUMS = listOf("李毅", "显卡", "steam")

/** 搜索探针：用几乎不可能零结果的宽泛词，否则"0 结果"会把断言变成噪音 */
private const val PROBE_KEYWORD = "贴吧"

/** 搜索排序：2 = 相关程度（见 AppHybridTiebaApi.searchThreadFlow 的 st 参数说明） */
private const val PROBE_SEARCH_SORT = 2

/**
 * 本次运行内的只读响应缓存。
 *
 * 吧页响应被三条用例共用（吧页、帖子页的前置、图片组的兜底），重复拉一遍纯属浪费；
 * 进程级缓存（FrsPageRepository 的 PageResponseCache）能挡一部分，但它按 uid+开关编键，
 * 且被 forceNew 绕过，所以这里再存一层"本次运行"的副本。
 */
internal object ProbeData {
    private var frsCache: Pair<String, FrsPageResponse>? = null
    private var pbCache: PbPageResponse? = null

    /** 每次点"一键跑全部"都重置，避免上一次运行的残留数据把这次判绿 */
    fun reset() {
        frsCache = null
        pbCache = null
    }

    /** @return 吧名 → 响应 */
    suspend fun frsPage(): Pair<String, FrsPageResponse> {
        frsCache?.let { return it }
        val failures = mutableListOf<String>()
        for (forum in PROBE_FORUMS) {
            val response = try {
                withTimeout(NET_TIMEOUT_MS) {
                    FrsPageRepository.frsPage(forum, 1, 1, -1, null, forceNew = true).first()
                }
            } catch (t: Throwable) {
                failures += "「$forum」${t.shortMessage()}"
                continue
            }
            if (response.data_?.thread_list.orEmpty().none { it.id > 0L }) {
                failures += "「$forum」thread_list 里没有可用 id"
                continue
            }
            return (forum to response).also { frsCache = it }
        }
        throw IllegalStateException("候选吧均未取到可用吧页 —— ${failures.joinToString("；")}")
    }

    /**
     * 帖子页首屏。参数逐字对标 ThreadViewModel 的首屏路径
     * （`pbPage(threadId, 0, 0, forumId, seeLz=false, sortType=0)`）。
     */
    suspend fun pbPage(): PbPageResponse {
        pbCache?.let { return it }
        val (forumName, frs) = frsPage()
        val frsData = frs.data_
            ?: throw IllegalStateException("吧「$forumName」吧页响应缺 data 段")
        val threadId = frsData.thread_list.firstOrNull { it.id > 0L }?.id
            ?: throw IllegalStateException("吧「$forumName」未给出可用 threadId")
        val response = withTimeout(NET_TIMEOUT_MS) {
            PbPageRepository.pbPage(
                threadId = threadId,
                page = 0,
                postId = 0,
                forumId = frsData.forum?.id,
            ).first()
        }
        return response.also { pbCache = it }
    }
}

/** 吧页（frs/page）：断言生产判成功用的那几个字段都在。 */
object ForumPageReadCase : SelfCheckCase {
    override val group = "网络只读"
    override val name = "吧页 frs/page"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val (forumName, response) = try {
            ProbeData.frsPage()
        } catch (t: Throwable) {
            return skipOrFail("吧页请求失败 —— ${t.shortMessage()}")
        }
        val data = response.data_
            ?: return fail("吧页响应 data 为空（FrsPageRepository 以此为致命错误）")
        expect(data.page != null) {
            "吧页响应缺 page 字段 —— ForumThreadListViewModel 以此为加载成功判据"
        }
        expect(data.forum != null) {
            "吧页响应缺 forum 字段 —— ForumViewModel 以此为加载成功判据"
        }
        val threads = data.thread_list
        expect(threads.isNotEmpty()) { "吧「$forumName」thread_list 为空" }
        val badIds = threads.take(20).filter { it.id <= 0L }
        expect(badIds.isEmpty()) {
            "吧「$forumName」thread_list 前 20 项里有 ${badIds.size} 项 id<=0（点进去必崩）"
        }
        return pass()
    }
}

/** 帖子页（pb/page，V22 协议）：断言能拿到结构完整、且确实是"这个帖"的首屏。 */
object ThreadPageReadCase : SelfCheckCase {
    override val group = "网络只读"
    override val name = "帖子页 pb/page（V22）"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val (forumName, frs) = try {
            ProbeData.frsPage()
        } catch (t: Throwable) {
            return skipOrFail("吧页前置请求失败 —— ${t.shortMessage()}")
        }
        val threadId = frs.data_?.thread_list?.firstOrNull { it.id > 0L }?.id
            ?: return skip("吧「$forumName」未给出可用 threadId")
        val response = try {
            ProbeData.pbPage()
        } catch (t: Throwable) {
            // 本条同时覆盖 PbPageRepository.transformPbPageResponse 的抛错口径
            // （data 空 / post_list 空 / page·thread.author·forum·anti 缺一 → 抛）
            return skipOrFail("帖子页 pb 请求失败（tid=$threadId）—— ${t.shortMessage()}")
        }
        val data = response.data_ ?: return fail("pb 响应 data 为空")
        expectEquals(data.thread?.id, threadId, "pb 返回的 tid 应等于请求的 threadId（防串帖）")
        expect(data.post_list.isNotEmpty()) { "pb 首屏（tid=$threadId）post_list 为空" }
        expect(data.first_floor_post != null) {
            "pb 首屏缺 first_floor_post —— 首楼不渲染"
        }
        expect(data.forum != null) { "pb 首屏缺 forum 字段 —— ThreadViewModel 以此为致命错误" }
        return pass()
    }
}

/** 搜索（mo/q/search/thread，hybrid 接口）：断言结果结构可解析。 */
object SearchReadCase : SelfCheckCase {
    override val group = "网络只读"
    override val name = "搜索 mo/q/search/thread"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val bean = try {
            withTimeout(NET_TIMEOUT_MS) {
                TiebaApi.getInstance()
                    .searchThreadFlow(PROBE_KEYWORD, 1, PROBE_SEARCH_SORT)
                    .first()
            }
        } catch (t: Throwable) {
            return skipOrFail("搜索请求失败 —— ${t.shortMessage()}")
        }
        // 服务端业务错误按"跳过"处理：风控/频控不是客户端链路的问题
        if (bean.errorCode != 0) {
            return skip("服务端 errorCode=${bean.errorCode}（${bean.errorMsg}）")
        }
        val posts = try {
            bean.data.postList
        } catch (t: Throwable) {
            // data 是 Kotlin 非空声明，但 Gson 反序列化可以塞进 null —— 这里如实报出来
            return skipOrFail("搜索响应缺 data 段 —— ${t.shortMessage()}")
        }
        if (posts.isEmpty()) {
            return skip("关键词「$PROBE_KEYWORD」返回 0 条（无匹配或未登录受限）")
        }
        val badTids = posts.map { it.tid }.filter { it.toLongOrNull()?.let { id -> id > 0 } != true }
        expect(badTids.isEmpty()) { "post_list 存在非法 tid：${badTids.take(5)}（点进去必崩）" }
        expect(posts.any { it.title.isNotBlank() }) { "post_list 全部标题为空（结构变了？）" }
        return pass()
    }
}

/**
 * V22 楼中楼图片渲染链（2026-09-26 新增）。
 *
 * 为什么必要：楼中楼图片只在 `_client_version ≥ 22.8.5.0` 时服务端才下发**真实图片**
 * （低版本回"[图片]"占位）——这是 README §7.2 的核心机制，靠 `api/Enums.kt` 的
 * `TIEBA_V22 = "22.10.1.0"` 伪装常量维持。**常量被改坏既不会编译错、也不会有单测红**，
 * 现象只是楼中楼图全变成"[图片]"文字（另有 `V22ImageGateSentinelTest` 守常量字面值，
 * 本用例补的是"真实响应里确实有图"这一环）。
 *
 * 走生产链路：`ITiebaApi.pbFloorFlow`（参数与 `SubPostsViewModel` 的 Load 分支逐字对齐）
 * → `content.renders` → [PicContentRender]。
 *
 * ⚠️ 断言口径：`renders` 里的 `picUrl/originUrl` 是**服务端原始 URL**（可能是 http），
 * 升 https 发生在取 URL 出口（`PhotoViewItem.displayTarget/downloadTarget` →
 * `ImageUrlUtil.upgradeImageUrlToHttps`）。所以这里断言的是
 * "**过一遍生产升级口径后**必须是 https 且非空"——直接断言原始 URL 是 https 会误红。
 *
 * 只读：只发 pb/floor 查询。
 */
object SubPostImageChainCase : SelfCheckCase {
    override val group = "图片链路"
    override val name = "楼中楼图片链（V22 · renders → https）"

    override suspend fun check(context: Context): SelfCheckOutcome {
        // 候选帖（有序、有界）：
        // 1) 固定探针帖 [PROBE_SUBPOST_THREAD_ID] —— 用户提供的"楼中楼确实有图"的帖子，
        //    有它这条用例才能**真正断言**而不是常年跳过（真实服务端数据，JVM 测试拿不到）；
        // 2) 回落：吧页前 MAX_THREAD_PROBES 个帖 —— 探针帖被删/被改内容时仍有覆盖。
        val fallbackIds: List<Long> = try {
            ProbeData.frsPage().second.data_?.thread_list.orEmpty()
                .filter { it.id > 0L }
                .take(MAX_THREAD_PROBES)
                .map { it.id }
        } catch (t: Throwable) {
            emptyList()
        }
        val threadIds = (listOf(PROBE_SUBPOST_THREAD_ID) + fallbackIds).distinct()

        val pics = mutableListOf<PicContentRender>()
        var probedThreads = 0
        var probedFloors = 0
        var scannedSubPosts = 0
        // 低版本占位命中数：V22 伪装一旦失效，服务端会把楼中楼图回成 "[图片]" 文字。
        // 这里不直接判失败（用户也可能自己打了这三个字），但**必须报出来**——
        // 面板上出现非 0 就该去查 Enums.kt 的 TIEBA_V22。
        var placeholderHits = 0
        var lastError: String? = null

        threadLoop@ for (threadId in threadIds) {
            val data = try {
                withTimeout(NET_TIMEOUT_MS) {
                    // forumId 不传：探针帖吧 id 事先不知道，从响应里取（见 threadForumId）
                    PbPageRepository.pbPage(threadId = threadId, page = 0, postId = 0).first()
                }.data_
            } catch (t: Throwable) {
                lastError = t.shortMessage()
                continue
            } ?: continue
            probedThreads += 1
            val threadForumId = data.forum?.id ?: 0L
            val candidates = data.post_list.filter { it.sub_post_number > 0 }.take(MAX_FLOOR_PROBES)
            for (target in candidates) {
                val floor = try {
                    withTimeout(NET_TIMEOUT_MS) {
                        TiebaApi.getInstance()
                            .pbFloorFlow(threadId, target.id, threadForumId, 1, 0L)
                            .first()
                    }.data_
                } catch (t: Throwable) {
                    lastError = t.shortMessage()
                    continue
                } ?: continue
                probedFloors += 1
                val contents = floor.subpost_list.flatMap { it.content }
                scannedSubPosts += contents.size
                placeholderHits += contents.count { it.text.contains(PIC_PLACEHOLDER) }
                // 注意：renders 是 List<PbContent> 上的扩展，必须作用在**每层的 content 列表**上
                // （contents 已经被 flatMap 成元素级 PbContent，不能再 .renders）
                val found = floor.subpost_list
                    .flatMap { it.content.renders }
                    .filterIsInstance<PicContentRender>()
                if (found.isNotEmpty()) {
                    pics += found
                    break@threadLoop
                }
            }
        }
        if (probedThreads == 0) {
            return skipOrFail(
                "pb/page 请求失败（试了 ${threadIds.size} 个帖）—— ${lastError ?: "响应 data 为空"}"
            )
        }
        // 楼中楼本来就可能全是文字：这次判不了，如实跳过，不放宽断言。
        // 但把诊断数字一并报出（尤其是 "[图片]" 占位数）——跳过不等于"没问题"。
        if (pics.isEmpty()) {
            return skip(
                "试了 $probedThreads 个帖 / $probedFloors 层楼中楼" +
                    "（共扫 $scannedSubPosts 条楼中楼）都没有图片；" +
                    "其中含 \"$PIC_PLACEHOLDER\" 占位 $placeholderHits 处" +
                    if (placeholderHits > 0) "（非 0 就要查 Enums.kt 的 TIEBA_V22）" else ""
            )
        }

        val blank = pics.filter { it.picUrl.isBlank() || it.originUrl.isBlank() }
        expect(blank.isEmpty()) {
            "楼中楼有 ${blank.size}/${pics.size} 张图的 picUrl/originUrl 为空 —— 渲染链断了"
        }
        val insecure = pics.filter {
            !upgradeImageUrlToHttps(it.picUrl).startsWith("https://") ||
                !upgradeImageUrlToHttps(it.originUrl).startsWith("https://")
        }
        expect(insecure.isEmpty()) {
            "楼中楼图片过生产升级口径后仍不是 https（禁明文策略下会在联网前被拒 = 整屏纯黑）：" +
                insecure.take(3).map { "${it.picUrl} | ${it.originUrl}" }
        }
        return pass()
    }
}
