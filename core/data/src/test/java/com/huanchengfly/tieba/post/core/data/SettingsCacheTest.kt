package com.huanchengfly.tieba.post.core.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SettingsCache] 一致性协议单测（2026-10-20，审查 §二-3/4）。
 *
 * 全部是纯内存断言：不碰 DataStore 文件、不需要 Robolectric。三条纪律各有对应用例：
 * ① 收集器**差量合并**——无关的落盘发射不能把刚改的值弹回去，也不能丢掉别的键；
 * ② 乐观 set 的**结算**——成功即让位给磁盘，失败必须回滚；
 * ③ `save` 成功后的**回写**——同步读与 `set` 同样是"写后可见"。
 */
class SettingsCacheTest {

    private val radius = intPreferencesKey("radius")
    private val littleTail = stringPreferencesKey("little_tail")
    private val hideReply = booleanPreferencesKey("hide_reply")

    private fun prefs(block: MutablePreferences.() -> Unit = {}): Preferences =
        emptyPreferences().toMutablePreferences().apply(block).toPreferences()

    @Suppress("UNCHECKED_CAST")
    private fun <T> Preferences.read(key: Preferences.Key<*>): T? = get(key as Preferences.Key<T>)

    @Test
    fun firstSnapshotPreheatsCache() {
        val cache = SettingsCache()
        assertNull(cache.peek())
        cache.seed(prefs { set(radius, 8) })
        assertEquals(8, cache.peek()?.read<Int>(radius))
    }

    @Test
    fun diskSnapshotMergesOnlyChangedKeys() {
        val cache = SettingsCache()
        cache.seed(prefs { set(radius, 8); set(littleTail, "旧") })

        // 外部写者只改了 little_tail
        cache.onDiskSnapshot(prefs { set(radius, 8); set(littleTail, "外部写的") })

        assertEquals(8, cache.peek()?.read<Int>(radius))
        assertEquals("外部写的", cache.peek()?.read<String>(littleTail))
    }

    /** 回归点：`set()` 的乐观值在落盘前被一次**无关**的磁盘发射整份覆盖 → 开关自己弹回去。 */
    @Test
    fun unrelatedDiskSnapshotDoesNotRevertPendingSet() {
        val cache = SettingsCache()
        cache.seed(prefs { set(hideReply, false); set(radius, 8) })
        cache.beginSet(hideReply, true)

        // 落盘顺序不保证：hide_reply 还没写下去，别的键先落盘并发射
        cache.onDiskSnapshot(prefs { set(hideReply, false); set(radius, 30) })

        assertEquals(true, cache.peek()?.read<Boolean>(hideReply))
        assertEquals(30, cache.peek()?.read<Int>(radius))
    }

    /**
     * 结算成功后，**写之前的旧快照**（落盘期间被收集器交错拿到的）不能把刚写的值弹回去——
     * 否则每次开设置页都会看到开关"闪一下"。代价是：别的写者把同一个键改回"与上一份磁盘
     * 快照完全相同"的值时，缓存要等到该键下次变化才跟上（无 UI 影响，见类注释"取舍"）。
     */
    @Test
    fun settledSetIsNotRevertedByStaleSnapshot() {
        val cache = SettingsCache()
        cache.seed(prefs { set(hideReply, false) })
        val handle = cache.beginSet(hideReply, true)
        cache.settleSet(hideReply, handle, success = true)

        cache.onDiskSnapshot(prefs { set(hideReply, false) })

        assertEquals(true, cache.peek()?.read<Boolean>(hideReply))
    }

    /** 回归点：落盘失败时缓存留着幽灵值 → "显示已改、重启回退"。 */
    @Test
    fun settleFailureRollsBackToDiskValue() {
        val cache = SettingsCache()
        cache.seed(prefs { set(hideReply, false) })
        val handle = cache.beginSet(hideReply, true)
        assertEquals(true, cache.peek()?.read<Boolean>(hideReply))

        cache.settleSet(hideReply, handle, success = false)

        assertEquals(false, cache.peek()?.read<Boolean>(hideReply))
    }

    @Test
    fun settleFailureOfRemovalRestoresDiskValue() {
        val cache = SettingsCache()
        cache.seed(prefs { set(littleTail, "原来") })
        val handle = cache.beginSet(littleTail, null)
        assertNull(cache.peek()?.read<String>(littleTail))

        cache.settleSet(littleTail, handle, success = false)

        assertEquals("原来", cache.peek()?.read<String>(littleTail))
    }

    /** 同键连续两次 `set`：第一次失败结算不能把第二次的意图一起清掉（否则第二次被回退）。 */
    @Test
    fun staleSettlementDoesNotClearNewerIntent() {
        val cache = SettingsCache()
        cache.seed(prefs { set(radius, 8) })
        val first = cache.beginSet(radius, 20)
        val second = cache.beginSet(radius, 30)

        cache.settleSet(radius, first, success = false)
        assertEquals(30, cache.peek()?.read<Int>(radius))

        cache.settleSet(radius, second, success = true)
        assertEquals(30, cache.peek()?.read<Int>(radius))
    }

    @Test
    fun applySavedIsVisibleImmediately() {
        val cache = SettingsCache()
        cache.seed(prefs { set(radius, 8) })
        cache.applySaved(radius, 9)
        assertEquals(9, cache.peek()?.read<Int>(radius))
    }

    @Test
    fun applySavedYieldsToNewerPendingSet() {
        val cache = SettingsCache()
        cache.seed(prefs { set(radius, 8) })
        cache.beginSet(radius, 20)

        // save 的 transform 基于磁盘算出 9，但用户刚又 set 了 20：以意图为准
        cache.applySaved(radius, 9)
        assertEquals(20, cache.peek()?.read<Int>(radius))
    }

    @Test
    fun diskSnapshotRemovedKeyIsRemovedFromCache() {
        val cache = SettingsCache()
        cache.seed(prefs { set(littleTail, "在的") })
        cache.onDiskSnapshot(emptyPreferences())
        assertNull(cache.peek()?.read<String>(littleTail))
    }
}
