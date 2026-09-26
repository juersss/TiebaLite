package com.huanchengfly.tieba.post.core.data

import android.content.Context

private val repositoryLock = Any()

@Volatile
private var repositoryInstance: SettingsRepository? = null

/**
 * 进程级唯一的 [SettingsRepository]（Phase 6.5，2026-09-17）。
 *
 * 为什么要单一实例：
 * - `Context.dataStore` 本身已是进程级单例（见 `DataStore.kt` 的 `by lazy(SYNCHRONIZED)`），
 *   故这里保证**仓储对象/同步读缓存/写队列**也只存在一份——否则 Hilt 注入的实例与
 *   `Context.appPreferences` 拿到的实例各有一份缓存，`value` 的"写后立即可见"会失效。
 * - Hilt 侧经 `DataModule.provideSettingsRepository` 走同一个函数，两条路径**同源同实例**。
 */
fun settingsRepository(context: Context): SettingsRepository =
    repositoryInstance ?: synchronized(repositoryLock) {
        repositoryInstance ?: DataStoreSettingsRepository(context.applicationContext)
            .also { repositoryInstance = it }
    }

/**
 * 设置入口（Phase 6.5 起**返回 [SettingsRepository]**）。
 *
 * 旧 `AppPreferencesUtils` 门面已删除：本扩展**保留原属性名 `appPreferences`**，只把类型换成
 * 唯一事实源——这样 171 处调用点无需改 accessor，只需机械补 `.value`（读）/ 改 `.set(...)`（写）。
 * 键名红线仍由 [SettingsKeys] 单一事实源保证（未变）。
 */
val Context.appPreferences: SettingsRepository
    get() = settingsRepository(this)
