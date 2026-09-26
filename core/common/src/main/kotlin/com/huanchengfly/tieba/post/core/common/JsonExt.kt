package com.huanchengfly.tieba.post.core.common

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 任意对象 → JSON 串（`BaseBean.toString()` 与若干请求体拼装都走它）。
 *
 * 结构大改 3b-prep-2（2026-09-17）从 app 根包 `Extensions.kt` 搬来，**实现逐字保留
 * （每次 new Gson()）**：不要顺手换成 [GsonUtil.getGson]，两者的适配器/命名策略配置不同，
 * 换了会改变序列化结果。放在 core:common 是因为 api 包（未来的 core:network）也要用它。
 */
fun Any.toJson(): String = Gson().toJson(this)

/**
 * JSON 串 → 对象（reified 版，配合 [GsonUtil] 的适配器配置）。
 *
 * 结构大改 Phase 4（2026-09-17）从 app 根包 `Extensions.kt` 搬来，**实现逐字保留**——
 * 判据：core:database 的实体 `Block.getKeywords()` 要用它，实体不能依赖 app。
 * app 侧原 `import …post.fromJson` 的两处（HistoryListPage/EmoticonManager）改指本包。
 */
inline fun <reified Data> String.fromJson(): Data {
    val type = object : TypeToken<Data>() {}.type
    return GsonUtil.getGson().fromJson(this, type)
}

inline fun <reified Data> java.io.File.fromJson(): Data {
    val type = object : TypeToken<Data>() {}.type
    return GsonUtil.getGson().fromJson(reader(), type)
}
