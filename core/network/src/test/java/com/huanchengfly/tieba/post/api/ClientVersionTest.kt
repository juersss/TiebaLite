package com.huanchengfly.tieba.post.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 客户端版本伪装常量的**机械锁定**（结构大改 Phase 6，2026-09-17）。
 *
 * 红线（用户明示不可回退）：`V22 = "22.10.1.0"`——它是楼中楼图片的必要条件
 * （服务端自 22.8.5.0 起才对 pb 页面/楼层下发光楼中楼图片内容；伪装值低了只回
 * "[图片]" 占位）。`Enums.kt` 的 [ClientVersion.TIEBA_V22] 是单一事实源；
 * 本测试锁定字面值，任何改动（哪怕版本名微调）都会在这里红，杜绝"顺手升级/回退"。
 *
 * 同时锁定其余该防的版本值，防止协议错配静默漂移。
 */
class ClientVersionTest {

    @Test
    fun v22PseudoVersionIsLocked() {
        assertEquals("V22 伪装版本被改动——回退或升级都会破坏楼中楼图片链路", "22.10.1.0", ClientVersion.TIEBA_V22.version)
    }

    @Test
    fun v12AndV11VersionsAreLocked() {
        assertEquals("12.52.1.0", ClientVersion.TIEBA_V12.version)
        assertEquals("12.35.1.0", ClientVersion.TIEBA_V12_POST.version)
        assertEquals("11.10.8.6", ClientVersion.TIEBA_V11.version)
    }

    @Test
    fun v22MustNotBeDowngradedBelowV12() {
        // 协议常量跨版本零变化,但版本号回退仍有语义风险:锁住"V22 主版本必须是 22.x"
        val v22 = ClientVersion.TIEBA_V22.version
        assertEquals(22, v22.substringBefore('.').toInt())
    }
}