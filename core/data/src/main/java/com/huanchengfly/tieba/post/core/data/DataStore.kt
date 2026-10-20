package com.huanchengfly.tieba.post.core.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

object DataStoreConst {
    // 红线：DataStore 文件名一字不动（Phase 5 从 app 根包迁入，值不变）
    const val DATA_STORE_NAME = "app_preferences"
}

private val dataStoreInstance by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
    preferencesDataStore(
        name = DataStoreConst.DATA_STORE_NAME,
        // 2026-10-20:损坏兜底。默认 NoOpCorruptionHandler 会把 CorruptionException 直接抛出，
        // 而 App.getResources()（取 fontScale）早于 onCreate 就会读这份 DataStore、onCreate 里
        // 还有 warmUp()——文件一旦损坏就是"冷启动即崩且无法自愈"，用户只能清应用数据（连 Room 的
        // 账号/历史一起丢）。改成用空偏好替换：坏的是设置，不该赔上整个应用。
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceMigrations = { context ->
            listOf(
                SharedPreferencesMigration(context, "settings"),
                object : DataMigration<Preferences> {
                    override suspend fun cleanUp() {}

                    override suspend fun migrate(currentData: Preferences): Preferences {
                        return currentData.toMutablePreferences().apply {
                            set(stringPreferencesKey("dark_theme"), "grey_dark")
                        }.toPreferences()
                    }

                    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
                        return currentData[stringPreferencesKey("dark_theme")] == "dark"
                    }
                }
            )
        }
    )
}

val Context.dataStore: DataStore<Preferences> by dataStoreInstance
