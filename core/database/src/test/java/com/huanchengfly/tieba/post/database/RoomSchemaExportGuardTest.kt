package com.huanchengfly.tieba.post.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room schema 导出与声明的守卫（2026-09-14 新增）。
 *
 * 动机（真实缺口）：仓库里 `app/schemas/…AppDatabase/` **只有 39.json 与 40.json，缺 38.json**，
 * 也就是说 38→39 那次迁移已经无法用 `MigrationTestHelper` 回放验证。这类"升了版本号却没提交
 * 导出 schema / schema 与声明脱节"的问题本来靠人盯，本测试把它变成构建期红。
 *
 * 三条断言：
 * 1. `@Database(version=)` 声明的版本必须有对应 schema 文件（否则迁移校验无从谈起）；
 * 2. 不得存在比声明版本更新的 schema（防止改回旧版本号而 schema 残留）；
 * 3. 从**现存最早版本**起必须连续（38.json 的缺失属历史既成事实，故只从现存最早版本往后查）。
 *
 * "声明版本"的取法：`androidx.room.Database` 注解保留级别非 RUNTIME、运行期反射不到
 * （09-14 实证），改为在 Robolectric 下建**内存库**读 `openHelper.writableDatabase.version`
 * ——新建库时 Room 按 `@Database` 声明写入 user_version，等价于读注解。
 */
@RunWith(RobolectricTestRunner::class)
// 同 AppDatabaseDaoTest：绕开清单里 Hilt App 的初始化链（Application 未就绪时抛 NPE）
@Config(application = android.app.Application::class)
class RoomSchemaExportGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val databaseClass = AppDatabase::class.java
    private val schemaDirName = databaseClass.name

    private fun schemaDir(): File {
        val root = requireNotNull(System.getProperty("tieba.schemas.dir")) {
            "缺少 tieba.schemas.dir 系统属性——见 app/build.gradle.kts 的 testOptions"
        }
        return File(root, schemaDirName)
    }

    private fun exportedVersions(): List<Int> {
        val dir = schemaDir()
        assertTrue("schema 目录不存在:$dir", dir.isDirectory)
        return dir.listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .sorted()
    }

    private fun declaredVersion(): Int {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            return db.openHelper.writableDatabase.version
        } finally {
            db.close()
        }
    }

    @Test
    fun declaredVersionHasExportedSchema() {
        val declared = declaredVersion()
        val exported = exportedVersions()

        assertTrue(
            "AppDatabase 声明 version=$declared，但 $schemaDirName/$declared.json 不存在" +
                    "（已导出:$exported）。升级数据库版本时必须同时提交 schema 导出，" +
                    "否则迁移测试与 Room 的 schema 校验都失去依据。",
            exported.contains(declared),
        )
    }

    @Test
    fun noExportedSchemaNewerThanDeclared() {
        val declared = declaredVersion()
        val newer = exportedVersions().filter { it > declared }

        assertFalse(
            "存在比声明版本($declared)更新的 schema 文件:$newer —— " +
                    "通常是把 @Database 的 version 改回旧值却没清理 schema",
            newer.isNotEmpty(),
        )
    }

    @Test
    fun exportedVersionsAreContiguousFromEarliest() {
        val exported = exportedVersions()
        assertFalse("没有任何导出的 schema 文件", exported.isEmpty())

        val expected = (exported.first()..exported.last()).toList()
        assertEquals(
            "从现存最早版本(${exported.first()})起 schema 必须连续——" +
                    "断档意味着某个版本的迁移无法被回放验证",
            expected,
            exported,
        )
    }
}
