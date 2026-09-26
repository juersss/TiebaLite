package com.huanchengfly.tieba.post.api.session
import com.huanchengfly.tieba.post.api.session.SessionProviders

/**
 * 账号凭据的取值接口——结构大改 Phase 3a 的"缝"。
 *
 * api 包（将来整体搬进 `core:network`）此前直接调用 `AccountUtil` 这个 app 层单例，
 * 导致 api 无法脱离 app 独立成模块。这里把 api 真正需要的那几个值抽成接口，
 * 由 app 侧实现并经 Hilt 注入（见 `di/SessionModule`）。
 *
 * 设计约束：
 * - **不暴露 `Account`/`LoginBean` 等数据层类型**：只给 api 需要的原始值，
 *   否则 `core:network` 会被迫依赖 `core:database`。
 * - 只读为主；唯一的写操作 [setTbs] 用于 tbs 失效自愈（原逻辑就是在内存对象上改单字段）。
 */
interface CredentialProvider {
    /** 当前账号 uid；未登录返回 null */
    fun getUid(): String?

    fun getBduss(): String?

    fun getSToken(): String?

    /** 内存缓存的 tbs；未登录返回 null */
    fun getTbs(): String?

    /** tbs 失效自愈后写回内存缓存（对应原 `SessionProviders.credential.setTbs(it)`） */
    fun setTbs(tbs: String)

    /** 走一次 fetchAccountFlow 拿最新 tbs（单飞互斥由实现侧内建）；失败返回 null */
    suspend fun fetchAccountTbs(): String?

    /** z_id，未取到返回空串（与原 `getAccountInfo { zid }.orEmpty()` 一致） */
    fun getZId(): String

    fun getCookie(): String?

    fun isLoggedIn(): Boolean

    fun getNameShow(): String?
}
