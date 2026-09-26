package com.huanchengfly.tieba.post.api.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sync 模型缺键容错的 JVM 单测。
 *
 * gson 对无无参构造的 data class 走 Unsafe 分配:字段声明的非空拦不住"响应缺键留 null"。
 * 字段此前为非空无默认,`/c/s/sync` 响应异常时消费端在启动链裸协程里直接 NPE 崩进程。
 * 本用例锁定"缺键解析为 null 不崩、完整载荷正常解析"的容错语义。
 */
class SyncModelTest {

    @Test
    fun missingKeysParseToNullWithoutCrash() {
        val sync = Gson().fromJson("{}", Sync::class.java)
        assertNull(sync.client)
        assertNull(sync.wlConfig)
    }

    @Test
    fun fullPayloadParses() {
        val sync = Gson().fromJson(
            """{"client":{"client_id":"abc"},"wl_config":{"sample_id":"s1"}}""",
            Sync::class.java
        )
        assertEquals("abc", sync.client?.clientId)
        assertEquals("s1", sync.wlConfig?.sampleId)
    }
}
