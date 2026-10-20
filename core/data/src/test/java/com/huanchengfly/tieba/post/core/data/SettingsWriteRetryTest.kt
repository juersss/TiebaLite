package com.huanchengfly.tieba.post.core.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 设置写入的**失败重试 / 回滚**契约（2026-10-20，审查 §二-4）。
 *
 * 背景：DataStore 在 Windows 上用 `Files.move(REPLACE_EXISTING)` 覆盖文件，目标被瞬间占用
 * 会抛 `IOException`。修复前该异常被 [com.huanchengfly.tieba.post.core.common.JobQueue] 吞掉，
 * 写入永久丢失且乐观缓存不回滚（实测 `allSettingsPersistKeyByKey` 约 10% 概率只落 61 项）。
 * 现在：IO 失败重试，重试耗尽才回滚——两类行为都在这里用"可注入失败的 DataStore"钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [Build.VERSION_CODES.O])
class SettingsWriteRetryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val radiusKey = intPreferencesKey(SettingsKeys.KEY_RADIUS)

    /** 前 [failuresLeft] 次 `updateData` 直接抛 [IOException]（模拟文件被别的句柄占用）。 */
    private class FlakyDataStore(
        private val delegate: DataStore<Preferences>,
        var failuresLeft: Int,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences>
            get() = delegate.data

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences {
            if (failuresLeft > 0) {
                failuresLeft--
                throw IOException("模拟落盘冲突：文件被占用")
            }
            return delegate.updateData(transform)
        }
    }

    /**
     * 清空并等"空"稳定观测到两次。
     *
     * 与 `SettingsRepositoryTest.clearStoreAndAwait` 同口径：只清一次的话，别的用例
     * 还在队列里的写会在清空之后落地 → 库又非空且本方法只等不重清，直接超时。
     */
    private suspend fun clearStore() {
        withTimeout(10_000) {
            var stableEmpty = 0
            while (stableEmpty < 2) {
                val empty = context.dataStore.data.first().asMap().isEmpty()
                if (empty) {
                    stableEmpty++
                } else {
                    stableEmpty = 0
                    context.dataStore.edit { it.clear() }
                }
                delay(150)
            }
        }
    }

    @Test
    fun transientIoFailureIsRetriedAndValueLands() = runBlocking {
        clearStore()
        val flaky = FlakyDataStore(context.dataStore, failuresLeft = 1)
        val repo = DataStoreSettingsRepository(context, flaky)

        repo.radius.set(33)

        withTimeout(10_000) {
            while (context.dataStore.data.first()[radiusKey] != 33) delay(20)
        }
        assertEquals("重试成功后同步读仍是新值", 33, repo.radius.value)
        clearStore()
    }

    /** 重试耗尽必须回滚：缓存不能留着磁盘上不存在的值（否则"显示已改、重启回退"）。 */
    @Test
    fun exhaustedRetriesRollCacheBackToDiskValue() = runBlocking {
        clearStore()
        context.dataStore.edit { it[radiusKey] = 8 }
        val flaky = FlakyDataStore(context.dataStore, failuresLeft = Int.MAX_VALUE)
        val repo = DataStoreSettingsRepository(context, flaky)

        repo.radius.set(33)
        assertEquals("乐观写仍然立即可见", 33, repo.radius.value)

        withTimeout(10_000) {
            while (repo.radius.value != 8) delay(20)
        }
        assertEquals("重试耗尽后回滚到磁盘值", 8, repo.radius.value)
        assertEquals("磁盘从未被写入", 8, context.dataStore.data.first()[radiusKey])
        clearStore()
    }

    /** `save` 的重试**不能重复执行 transform**（`it + 1` 加两次就成了 +2）。 */
    @Test
    fun saveRetryDoesNotApplyTransformTwice() = runBlocking {
        clearStore()
        val flaky = FlakyDataStore(context.dataStore, failuresLeft = 1)
        val repo = DataStoreSettingsRepository(context, flaky)

        repo.radius.save { it + 1 }

        withTimeout(10_000) {
            while (repo.radius.value != 9) delay(20)
        }
        assertEquals("读-改-写只生效一次", 9, context.dataStore.data.first()[radiusKey])
        clearStore()
    }
}
