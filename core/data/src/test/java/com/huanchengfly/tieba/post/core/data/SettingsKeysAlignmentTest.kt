package com.huanchengfly.tieba.post.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SettingsKeys 与迁移前基线（tools/settings-baseline.json）的**静态逐键比对**（Phase 5）。
 *
 * 纯 JVM、零 DataStore 写入——Windows JVM 上 DataStore 落盘 rename 不可靠（见
 * SettingsRepositoryTest 的说明），键名红线改由静态断言兜底：62 个键逐一与基线
 * （从迁移前 AppPreferencesUtils 提取）比对，多一个/少一个/值不一致直接红。
 */
class SettingsKeysAlignmentTest {

    private val baselineFile: File = findBaseline()

    @Test
    fun settingsKeysMatchPreMigrationBaseline() {
        val baseline = parseBaseline(baselineFile)
        assertEquals("基线条目数异常", 62, baseline.size)
        val keys = SettingsKeys::class.java.fields
            .filter { it.name.startsWith("KEY_") && it.type == String::class.java }
        assertEquals("SettingsKeys.KEY_* 数量与基线不一致", 62, keys.size)
        keys.forEach { field ->
            val value = field.get(null) as String
            val name = field.name
            assertTrue(
                "SettingsKeys.$name = '$value' 不在基线中",
                value in baseline,
            )
        }
        // 反向：每个基线键都必须被 SettingsKeys 覆盖（无遗漏）
        val covered = keys.map { it.get(null) as String }.toSet()
        val missing = baseline.filter { it !in covered }
        assertEquals("基线键未被 SettingsKeys 覆盖：$missing", emptyList<String>(), missing)
    }

    @Test
    fun settingsKeysValuesAreUnique() {
        val values = SettingsKeys::class.java.fields
            .filter { it.name.startsWith("KEY_") }
            .map { it.get(null) as String }
        assertEquals("键名有重复", values.size, values.toSet().size)
    }

    @Test
    fun defaultConstantsMatchBaselineDefaults() {
        val baseline = parseBaseline(baselineFile)
        assertTrue("基线缺 theme 键", "theme" in baseline)
        assertEquals("theme 默认值", "tieba", SettingsKeys.THEME_DEFAULT)
        assertEquals(0, SettingsKeys.TRANSLUCENT_THEME_LIGHT)
        assertEquals(
            "appIcon 默认值（LauncherIcons.DEFAULT_ICON 迁移）",
            "com.huanchengfly.tieba.post.MainActivityV2",
            SettingsKeys.DEFAULT_APP_ICON,
        )
    }

    private fun parseBaseline(file: File): List<String> {
        // 基线 JSON 条目形如 {"name": "...", "type": "...", "key": "...", "default": ...}
        val text = file.readText()
        val regex = Regex("\"key\":\\s*\"([^\"]+)\"")
        return regex.findAll(text).map { it.groupValues[1] }.toList()
    }

    private fun findBaseline(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            val candidate = File(dir, "tools/settings-baseline.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: error("向上找不到 tools/settings-baseline.json")
        }
    }
}
