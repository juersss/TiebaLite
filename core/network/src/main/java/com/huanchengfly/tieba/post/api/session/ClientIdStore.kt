package com.huanchengfly.tieba.post.api.session

/**
 * 客户端标识的读写接口——api 包此前直接读 app 的 DataStore（`context.dataStore`）。
 *
 * 为什么不把 DataStore 搬进 core:network：core 之间只允许依赖 core:common（进度交接 §8.1），
 * 而 DataStore/SettingsRepository 属 core:data（Phase 5）。所以这里只留接口，
 * 实现（[com.huanchengfly.tieba.post.session.impl.AppClientIdStore]）留在 app，
 * **落盘仍是同一份 `app_preferences`**（数据名不变式）。
 *
 * 这些值随请求头发送：`_client_id` / `sample_id` / `BAIDUID` / `active_timestamp`。
 */
interface ClientIdStore {
    suspend fun readClientId(): String?

    suspend fun readSampleId(): String?

    suspend fun readBaiduId(): String?

    suspend fun readActiveTimestamp(): Long?

    suspend fun writeClientId(clientId: String, sampleId: String?)

    suspend fun writeBaiduId(id: String)

    suspend fun writeActiveTimestamp(timestamp: Long)
}
