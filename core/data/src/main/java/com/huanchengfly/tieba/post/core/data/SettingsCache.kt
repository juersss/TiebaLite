package com.huanchengfly.tieba.post.core.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/**
 * 设置**同步读缓存**的一致性协议（2026-10-20，审查 §二-3/4）。
 *
 * 背景：`Settings.value` 是进程内同步读（旧 `AppPreferencesUtils` 的 ReadWriteProperty 语义），
 * 数据来源有三路且此前互不相识：
 * 1. **收集器**——`dataStore.data.collect` 每次落盘/外部写入后整份刷新缓存；
 * 2. **乐观 set**——`set()` 先改缓存再排队落盘（"写后立即可读"）；
 * 3. **外部直写**——`DataStorePreference`（androidx.preference 桥）、ClientUtils 等绕过本仓库直接
 *    `dataStore.edit`，只体现为收集器的一次发射。
 *
 * 三路各自整份覆盖缓存会造成两类真实故障（均为本类存在的原因）：
 * - **丢键**：缓存未预热时 `set()` 曾在 `emptyPreferences()` 上叠加 → 缓存只剩一个键，
 *   其余 61 项的同步读全部退回默认值（字号/主题/动态取色被"重置"）；
 * - **回退**：`set()` 的乐观值在落盘前被收集器的一次无关发射整份覆盖（用户看到刚打开的开关
 *   自己弹回去）；反之落盘失败时乐观值永远留在缓存里，与磁盘分叉（"显示已改、重启回退"）。
 *
 * 协议（全部在本类内同步完成，调用方无需额外加锁）：
 * - [onDiskSnapshot] **按键差量合并**：只把"和上一份磁盘快照不同"的键并进缓存，绝不整份覆盖；
 * - 有未结算 `set` 意图的键，收集器一律不覆盖（以用户最后一次操作为准）；
 * - [settleSet] 结算：成功 → 丢掉意图（此后磁盘值即权威）；失败 → 该键回滚到磁盘值，
 *   不会留下"磁盘没有、缓存却有"的幽灵值；
 * - [applySaved] 让 `save()`（读-改-写）落盘成功后的结果也能立刻被同步读看到，
 *   与 `set()` 的可见性口径对齐（`save` 没有乐观值，不可能回滚，故只在成功后写）。
 *
 * 纯内存、无 I/O、无协程：因此可脱离 DataStore/Robolectric 直接单测（见 `SettingsCacheTest`）。
 *
 * **取舍（故意的）**：合并判据是"与上一份磁盘快照不同"。落盘期间收集器可能交错拿到写之前的
 * 旧快照，按差量它会成为 no-op——因此不会出现"开关闪一下"的可见回退；代价是别的写者若把某键
 * 改回**与上一份快照完全相同**的值，缓存要等该键下次变化才跟上。后者无 UI 影响（UI 订阅的是
 * 磁盘流），而前者是用户可见的抖动，两害相权取此。
 */
internal class SettingsCache {

    /** 最近一次收到的磁盘快照；null = 还没收到过（收集器未发射）。 */
    private var disk: Preferences? = null

    /** 对外可见的有效值（磁盘 + 未结算的 set 意图）；null = 未预热。 */
    private var effective: Preferences? = null

    /**
     * 已排队、尚未落盘的 `set` 意图：键 → 意图。
     *
     * 用 [PendingSet] 对象身份而非值比较：连续两次 `set` 同一个键（如快速开关）时，
     * 第一次结算不能把第二次的意图一起清掉，否则第二次会被收集器回退。
     */
    private val pending = HashMap<Preferences.Key<*>, PendingSet>()

    private var seq = 0L

    /** 一次 `set` 的结算句柄。 */
    class PendingSet internal constructor(internal val seq: Long, internal val value: Any?)

    /** 缓存现值；null = 未预热（调用方需先 [seed]）。 */
    @Synchronized
    fun peek(): Preferences? = effective

    /** 冷启动/兜底：以一份磁盘快照预热缓存；已预热时只更新磁盘基线。 */
    @Synchronized
    fun seed(snapshot: Preferences): Preferences {
        disk = snapshot
        val current = effective
        if (current == null) {
            effective = snapshot
            return snapshot
        }
        return current
    }

    /**
     * 收集器收到新的磁盘快照。
     *
     * 只合并**变化了的键**（对比上一份磁盘快照），且跳过有未结算意图的键：
     * 于是"别处写了一个键"能进来，"我刚改但还没落盘的键"不会被旧快照打回去。
     */
    @Synchronized
    fun onDiskSnapshot(snapshot: Preferences) {
        val previous = disk
        disk = snapshot
        val base = effective
        if (base == null) {
            // 首次发射即预热，省掉一次阻塞读
            effective = snapshot
            return
        }
        if (previous === snapshot) return
        val keys = LinkedHashSet<Preferences.Key<*>>()
        previous?.asMap()?.keys?.let { keys.addAll(it) }
        keys.addAll(snapshot.asMap().keys)
        var merged: MutablePreferences? = null
        for (key in keys) {
            if (pending.containsKey(key)) continue
            if (previous != null && previous.rawGet(key) == snapshot.rawGet(key)) continue
            if (merged == null) merged = base.toMutablePreferences()
            merged.setOrRemove(key, snapshot.rawGet(key))
        }
        if (merged != null) effective = merged.toPreferences()
    }

    /**
     * 记录一次乐观写入（`set` 语义）并立刻反映到缓存。
     *
     * 前置条件：缓存已就绪（[peek] 非 null）。未就绪时调用属于编程错误——旧实现在这时用
     * 空偏好叠加，正是"整份缓存丢键"的根因。
     */
    @Synchronized
    fun beginSet(key: Preferences.Key<*>, value: Any?): PendingSet {
        val base = checkNotNull(effective) { "SettingsCache 未预热：beginSet 之前必须 seed()" }
        val handle = PendingSet(++seq, value)
        pending[key] = handle
        effective = base.toMutablePreferences().apply { setOrRemove(key, value) }.toPreferences()
        return handle
    }

    /**
     * 结算一次 [beginSet]：[success] 为 false（落盘失败）时把该键回滚到磁盘值。
     *
     * 句柄已经过期（同键又来了新的 `set`）则什么都不做——交给最新意图决定。
     */
    @Synchronized
    fun settleSet(key: Preferences.Key<*>, handle: PendingSet, success: Boolean) {
        if (pending[key] !== handle) return
        pending.remove(key)
        if (success) return
        val base = effective ?: return
        val restored = disk?.rawGet(key)
        effective = base.toMutablePreferences().apply { setOrRemove(key, restored) }.toPreferences()
    }

    /** `save()` 落盘成功后的缓存同步（同键若有更新的 `set` 意图，以意图为准）。 */
    @Synchronized
    fun applySaved(key: Preferences.Key<*>, value: Any?) {
        if (pending.containsKey(key)) return
        val base = effective ?: return
        effective = base.toMutablePreferences().apply { setOrRemove(key, value) }.toPreferences()
    }

    /**
     * 仅供单测：丢弃全部状态。
     *
     * `DataStoreSettingsRepository` 是进程级单例，但用例之间共享同一个 DataStore 文件；
     * 与本仓库其它单例的 `resetForTest` 同一口径。
     */
    @Synchronized
    internal fun resetForTest() {
        disk = null
        effective = null
        pending.clear()
        seq = 0
    }
}

/** `Preferences.get(Key<*>)` 需要具体类型参数，这里收口一次擦除转换。 */
@Suppress("UNCHECKED_CAST")
private fun Preferences.rawGet(key: Preferences.Key<*>): Any? = get(key as Preferences.Key<Any?>)

/**
 * 值语义：null 即"删除该键"（与 `set()`/`save()` 中可空设置的约定一致）。
 *
 * `internal` 而非 `private`：`SettingsRepository.save` 里 transform 的结果是擦除后的 `Any?`，
 * 写入侧要复用同一套语义（两者同模块）。
 */
@Suppress("UNCHECKED_CAST")
internal fun MutablePreferences.setOrRemove(key: Preferences.Key<*>, value: Any?) {
    if (value == null) remove(key) else set(key as Preferences.Key<Any>, value)
}
