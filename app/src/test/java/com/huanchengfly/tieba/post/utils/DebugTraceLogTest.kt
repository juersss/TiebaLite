package com.huanchengfly.tieba.post.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Debug 追踪日志(定位吧内浏览进度回退的诊断探针)的核心行为:
 * 未启动时零开销直通;启动后内存环按上限截断、条目实时落盘。
 */
class DebugTraceLogTest {

    @Test
    fun disabledIsNoOp_thenStartRingsAndWrites() {
        val tempDir = Files.createTempDirectory("trace_test").toFile()
        try {
            // 未 start:所有调用零副作用
            DebugTraceLog.log("T", "before-start")
            assertEquals(0, DebugTraceLog.snapshot().size)

            DebugTraceLog.start(tempDir, "test-header v1.0")
            DebugTraceLog.log("POS", "index=3 key=123")

            val snapshot = DebugTraceLog.snapshot()
            assertEquals(2, snapshot.size)
            assertTrue(snapshot[0].contains("session start: test-header v1.0"))
            assertTrue(snapshot[1].contains("POS: index=3 key=123"))

            // 内存环按 MAX_MEMORY_ENTRIES 截断(旧条目丢弃,新条目保留)
            repeat(DebugTraceLog.MAX_MEMORY_ENTRIES + 10) { DebugTraceLog.log("FILL", "$it") }
            assertEquals(DebugTraceLog.MAX_MEMORY_ENTRIES, DebugTraceLog.snapshot().size)

            // 文件异步落盘:轮询等待 executor 写入完成
            val deadline = System.currentTimeMillis() + 5000
            var content: String? = null
            while (System.currentTimeMillis() < deadline) {
                content = DebugTraceLog.sessionFile()?.readText()
                if (content?.contains("POS: index=3 key=123") == true) break
                Thread.sleep(20)
            }
            assertTrue("日志应在超时前落盘", content != null)
            assertTrue(content!!.contains("session start: test-header v1.0"))
            assertTrue(content.contains("POS: index=3 key=123"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
