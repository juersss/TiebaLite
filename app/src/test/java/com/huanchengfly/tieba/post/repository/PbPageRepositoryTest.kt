package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.core.network.model.protos.Anti
import com.huanchengfly.tieba.post.core.network.model.protos.Page
import com.huanchengfly.tieba.post.core.network.model.protos.Post
import com.huanchengfly.tieba.post.core.network.model.protos.SimpleForum
import com.huanchengfly.tieba.post.core.network.model.protos.SubPost
import com.huanchengfly.tieba.post.core.network.model.protos.SubPostList
import com.huanchengfly.tieba.post.core.network.model.protos.ThreadInfo
import com.huanchengfly.tieba.post.core.network.model.protos.User
import com.huanchengfly.tieba.post.core.network.model.protos.pbPage.PbPageResponse
import com.huanchengfly.tieba.post.core.network.model.protos.pbPage.PbPageResponseData
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.ui.page.thread.ThreadPageFrom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * PbPageRepository 的 fake 数据源单测（B3，2026-09-14）。
 *
 * 不碰网络：注入 `PbPageRepository.source` 接缝，断言两类真实逻辑——
 * 1. **参数透传与 from→stType/mark 映射**（进网络的唯一一处组装，错一个参数就是请求跑偏）；
 * 2. **响应整形**（异常三分类、author 回填链、from_forum/tid/origin_thread_info 注入、
 *    `floor == 1` 优先的 first_floor_post 解析）——全部在 [PbPageRepository.transformPbPageResponse]，
 *    这里通过收集 `pbPage(...)` 的 Flow 走**生产完整链路**断言，而非单测内部函数。
 */
class PbPageRepositoryTest {

    private lateinit var originalSource: (PbPageRepository.PbPageQuery) -> kotlinx.coroutines.flow.Flow<PbPageResponse>

    @Before
    fun setUp() {
        originalSource = PbPageRepository.source
    }

    @After
    fun tearDown() {
        // source 是 object 上的进程级可变字段，必须还原，避免污染其他测试
        PbPageRepository.source = originalSource
    }

    // ── 最小合法响应脚手架 ────────────────────────────────────────────────

    private val threadAuthor = User(id = 500L, name = "楼主")
    private val forum = SimpleForum(id = 7L, name = "图拉丁吧")

    private fun validData(mutate: PbPageResponseData.() -> PbPageResponseData = { this }) =
        PbPageResponseData(
            page = Page(),
            anti = Anti(),
            forum = forum,
            thread = ThreadInfo(id = 99L, author = threadAuthor),
            post_list = listOf(Post(id = 1L, floor = 2, author_id = 600L)),
        ).mutate()

    private fun inject(response: PbPageResponse) {
        PbPageRepository.source = { flowOf(response) }
    }

    // ── 异常三分类 ────────────────────────────────────────────────────────

    @Test
    fun nullData_throwsTiebaUnknown() {
        inject(PbPageResponse(data_ = null))
        assertThrows(TiebaUnknownException::class.java) {
            runBlocking { PbPageRepository.pbPage(99L).first() }
        }
    }

    @Test
    fun emptyPostList_throwsEmptyData() {
        inject(PbPageResponse(data_ = validData { copy(post_list = emptyList()) }))
        assertThrows(EmptyDataException::class.java) {
            runBlocking { PbPageRepository.pbPage(99L).first() }
        }
    }

    /** page / thread / thread.author / forum / anti 缺任一 → TiebaUnknownException（整页判废） */
    @Test
    fun missingRequiredField_throwsTiebaUnknown() {
        val cases = mapOf(
            "page" to { d: PbPageResponseData -> d.copy(page = null) },
            "thread" to { d: PbPageResponseData -> d.copy(thread = null) },
            "thread.author" to { d: PbPageResponseData -> d.copy(thread = d.thread!!.copy(author = null)) },
            "forum" to { d: PbPageResponseData -> d.copy(forum = null) },
            "anti" to { d: PbPageResponseData -> d.copy(anti = null) },
        )
        for ((name, mutate) in cases) {
            inject(PbPageResponse(data_ = validData(mutate)))
            assertThrows(
                "$name 缺失时整页必须判废（下游 UI 依赖这些字段非空）",
                TiebaUnknownException::class.java,
            ) {
                runBlocking { PbPageRepository.pbPage(99L).first() }
            }
        }
    }

    // ── author 回填链 ─────────────────────────────────────────────────────

    @Test
    fun authorPresent_isKeptUnchanged() = runBlocking {
        val own = User(id = 600L, name = "自带作者")
        inject(
            PbPageResponse(
                data_ = validData { copy(post_list = listOf(Post(id = 1L, floor = 2, author = own))) },
            ),
        )

        val post = PbPageRepository.pbPage(99L).first().data_!!.post_list.single()
        assertSame("post 自带 author 时不得被 user_list 覆盖", own, post.author)
        assertEquals(600L, post.author_id)
    }

    @Test
    fun authorMissing_backfillsFromUserList() = runBlocking {
        val listed = User(id = 600L, name = "档案用户")
        inject(
            PbPageResponse(
                data_ = validData {
                    copy(
                        post_list = listOf(Post(id = 1L, floor = 2, author_id = 600L)),
                        user_list = listOf(User(id = 111L, name = "别人"), listed),
                    )
                },
            ),
        )

        val post = PbPageRepository.pbPage(99L).first().data_!!.post_list.single()
        assertSame("author 为空必须回落到 user_list 按 author_id 匹配", listed, post.author)
    }

    /** 核心回归：作者档案被裁剪/系统楼（author_id 在 user_list 查无）时不得抛 NoSuchElementException */
    @Test
    fun authorMissingEverywhere_degradesToPlaceholderUser() = runBlocking {
        inject(
            PbPageResponse(
                data_ = validData {
                    copy(
                        post_list = listOf(Post(id = 1L, floor = 2, author_id = 0L)),
                        user_list = listOf(User(id = 111L, name = "别人")),
                    )
                },
            ),
        )

        val post = PbPageRepository.pbPage(99L).first().data_!!.post_list.single()
        assertEquals("降级为占位 User(id=author_id)", 0L, post.author?.id)
        assertEquals("", post.author?.name)
    }

    @Test
    fun subPostAuthor_backfilledThroughSameChain() = runBlocking {
        val listed = User(id = 700L, name = "楼中楼用户")
        val sub = SubPost(
            sub_post_list = listOf(
                SubPostList(author_id = 700L), // user_list 可查
                SubPostList(author_id = 800L), // 查无 → 占位
            ),
        )
        inject(
            PbPageResponse(
                data_ = validData {
                    copy(
                        post_list = listOf(Post(id = 1L, floor = 2, author_id = 600L, sub_post_list = sub)),
                        user_list = listOf(listed),
                    )
                },
            ),
        )

        val subPosts = PbPageRepository.pbPage(99L).first().data_!!.post_list.single()
            .sub_post_list!!.sub_post_list
        assertSame(listed, subPosts[0].author)
        assertEquals("查无此人时楼中楼降级为占位 User", 800L, subPosts[1].author?.id)
        assertEquals("", subPosts[1].author?.name)
    }

    // ── from_forum / tid / origin_thread_info 注入 ────────────────────────

    @Test
    fun perPostContextFields_injectedFromDataLevel() = runBlocking {
        inject(
            PbPageResponse(
                data_ = validData {
                    copy(post_list = listOf(Post(id = 1L, floor = 2, author_id = 600L, title = "裸 post")))
                },
            ),
        )

        val post = PbPageRepository.pbPage(99L).first().data_!!.post_list.single()
        assertSame("from_forum 必须来自 data.forum（回复归属吧）", forum, post.from_forum)
        assertEquals("tid 必须来自 data.thread.id", 99L, post.tid)
        assertEquals(
            "origin_thread_info.author 必须是楼主（引用/转发展示用）",
            threadAuthor,
            post.origin_thread_info?.author,
        )
    }

    // ── first_floor_post 解析（floor == 1 优先）───────────────────────────

    @Test
    fun firstFloorPreferredFromPostList_overridesStandaloneField() = runBlocking {
        val inList = Post(id = 10L, floor = 1, author = User(id = 500L, name = "楼主"))
        val standalone = Post(id = 999L, floor = 0, author = User(id = 1L, name = "独立字段"))
        inject(
            PbPageResponse(
                data_ = validData {
                    copy(post_list = listOf(Post(id = 2L, floor = 2, author = inList.author), inList), first_floor_post = standalone)
                },
            ),
        )

        val out = PbPageRepository.pbPage(99L).first().data_!!
        assertEquals("post_list 里已有 floor==1 时必须优先取它", 10L, out.first_floor_post?.id)
    }

    @Test
    fun firstFloorFallsBackToStandaloneField_andGetsFullTransform() = runBlocking {
        val sub = SubPost(sub_post_list = listOf(SubPostList(author_id = 800L)))
        val standalone = Post(id = 999L, floor = 0, sub_post_list = sub)
        inject(
            PbPageResponse(
                data_ = validData {
                    copy(post_list = listOf(Post(id = 2L, floor = 2, author_id = 600L)), first_floor_post = standalone)
                },
            ),
        )

        val out = PbPageRepository.pbPage(99L).first().data_!!
        val firstPost = out.first_floor_post!!
        assertEquals(999L, firstPost.id)
        assertEquals("独立主楼也要回填楼主", 500L, firstPost.author?.id)
        assertEquals(forum, firstPost.from_forum)
        assertEquals(99L, firstPost.tid)
        assertEquals("楼中楼同样走回填链", 800L, firstPost.sub_post_list!!.sub_post_list.single().author?.id)
    }

    @Test
    fun noFirstFloorAnywhere_staysNull() = runBlocking {
        inject(
            PbPageResponse(
                data_ = validData { copy(post_list = listOf(Post(id = 2L, floor = 2, author_id = 600L))) },
            ),
        )
        assertNull(PbPageRepository.pbPage(99L).first().data_!!.first_floor_post)
    }

    // ── from → stType/mark 映射 + 参数透传 ────────────────────────────────

    @Test
    fun from_mapsToStTypeAndMark() = runBlocking {
        var captured: PbPageRepository.PbPageQuery? = null
        val response = PbPageResponse(data_ = validData())
        PbPageRepository.source = { q -> captured = q; flowOf(response) }

        // 1) from="" → 都不带
        PbPageRepository.pbPage(99L, from = "").toList()
        assertEquals("", captured!!.stType)
        assertEquals(0, captured!!.mark)

        // 2) mention → stType 透传、mark 不变
        PbPageRepository.pbPage(99L, from = PbPageRepository.ST_TYPE_MENTION).toList()
        assertEquals("mention", captured!!.stType)
        assertEquals(0, captured!!.mark)

        // 3) store_thread → stType 与 mark 同时
        PbPageRepository.pbPage(99L, from = ThreadPageFrom.FROM_STORE).toList()
        assertEquals("store_thread", captured!!.stType)
        assertEquals(1, captured!!.mark)

        // 4) 非枚举值 → 丢弃（防把任意 UI 来源字符串发给服务端）
        PbPageRepository.pbPage(99L, from = "whatever").toList()
        assertEquals("", captured!!.stType)
        assertEquals(0, captured!!.mark)
    }

    @Test
    fun allQueryParams_passThroughToSource() = runBlocking {
        var captured: PbPageRepository.PbPageQuery? = null
        val response = PbPageResponse(data_ = validData())
        PbPageRepository.source = { q -> captured = q; flowOf(response) }

        PbPageRepository.pbPage(
            threadId = 99L,
            page = 3,
            postId = 42L,
            forumId = 7L,
            seeLz = true,
            sortType = 2,
            back = true,
            lastPostId = 555L,
        ).toList()

        assertEquals(PbPageRepository.PbPageQuery(99L, 3, 42L, 7L, true, 2, true, "", 0, 555L), captured)
    }
}
