package com.huanchengfly.tieba.post.api.adapters

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.huanchengfly.tieba.post.api.models.SearchForumBean
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模糊匹配 gson 适配器缺键容错的 JVM 单测。
 *
 * 模糊匹配是服务端按 uid/键名建 key 的任意条目集合,此前任一条目缺 forum_id/has_concerned
 * (或字符串字段值类型异常)会让适配器抛异常、整次搜索失败。锁定"缺键/错型降级为缺省值,
 * 条目其余字段正常解析"的容错语义。
 */
class FuzzyMatchAdapterTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(
            object : TypeToken<List<SearchForumBean.ForumInfoBean>>() {}.type,
            ForumFuzzyMatchAdapter()
        )
        .create()

    private val listType = object : TypeToken<List<SearchForumBean.ForumInfoBean>>() {}.type

    @Test
    fun missingKeysFallBackToDefaultsInsteadOfThrowing() {
        val beans: List<SearchForumBean.ForumInfoBean> = gson.fromJson(
            """{"1001":{"forum_name":"吧名"}}""",
            listType
        )
        assertEquals(1, beans.size)
        assertEquals(0L, beans[0].forumId)
        assertEquals("吧名", beans[0].forumName)
        assertEquals(0, beans[0].hasConcerned)
    }

    @Test
    fun wrongTypedValueDoesNotKillSiblingEntries() {
        val beans: List<SearchForumBean.ForumInfoBean> = gson.fromJson(
            """{"a":{"forum_id":1,"forum_name":"正常条目"},"b":{"forum_name":{},"has_concerned":[]}}""",
            listType
        )
        // forum_name/has_concerned 值类型异常(对象/数组)→ 按缺省值降级,不影响同响应其他条目
        assertEquals(2, beans.size)
        assertEquals("正常条目", beans[0].forumName)
        assertEquals(1L, beans[0].forumId)
        assertEquals(null, beans[1].forumName)
        assertEquals(0, beans[1].hasConcerned)
    }

    @Test
    fun nonObjectPayloadYieldsEmptyList() {
        val beans: List<SearchForumBean.ForumInfoBean> = gson.fromJson("\"\"", listType)
        assertEquals(0, beans.size)
    }
}
