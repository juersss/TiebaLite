package com.huanchengfly.tieba.post.session.impl

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.huanchengfly.tieba.post.api.session.ClientIdStore
import com.huanchengfly.tieba.post.core.data.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [ClientIdStore] 的 app 侧实现：读写 `app_preferences` DataStore。
 *
 * 键名与原先 `ClientUtils` 内联的那份**一字不差**（`client_id`/`sample_id`/`baidu_id`/
 * `active_timestamp`），DataStore 文件名也不变——3b-prep-3（2026-09-17）只换调用路径，不动数据。
 * Phase 6：Context 改 Hilt `@ApplicationContext` 注入，不再经静态入口点取值。
 */
class AppClientIdStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClientIdStore {

    private val clientIdKey by lazy { stringPreferencesKey("client_id") }
    private val sampleIdKey by lazy { stringPreferencesKey("sample_id") }
    private val baiduIdKey by lazy { stringPreferencesKey("baidu_id") }
    private val activeTimestampKey by lazy { longPreferencesKey("active_timestamp") }

    override suspend fun readClientId(): String? =
        context.dataStore.data.map { it[clientIdKey] }.firstOrNull()

    override suspend fun readSampleId(): String? =
        context.dataStore.data.map { it[sampleIdKey] }.firstOrNull()

    override suspend fun readBaiduId(): String? =
        context.dataStore.data.map { it[baiduIdKey] }.firstOrNull()

    override suspend fun readActiveTimestamp(): Long? =
        context.dataStore.data.map { it[activeTimestampKey] }.firstOrNull()

    override suspend fun writeClientId(clientId: String, sampleId: String?) {
        context.dataStore.edit {
            it[clientIdKey] = clientId
            if (sampleId != null) {
                it[sampleIdKey] = sampleId
            }
        }
    }

    override suspend fun writeBaiduId(id: String) {
        context.dataStore.edit { it[baiduIdKey] = id }
    }

    override suspend fun writeActiveTimestamp(timestamp: Long) {
        context.dataStore.edit { it[activeTimestampKey] = timestamp }
    }
}