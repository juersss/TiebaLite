package com.huanchengfly.tieba.post.components

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.BuildConfig
import com.huanchengfly.tieba.post.api.ClientVersion
import com.huanchengfly.tieba.post.api.session.ClientConfigProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 客户端配置托管（结构大改 Phase 6，2026-09-17）。
 *
 * 只做**常量托管**，不引入网络同步行为：
 * - [clientVersion]：V22 伪装版本（红线 22.10.1.0，楼中楼图片必要条件），
 *   单一事实源在 core:network 的 [ClientVersion] 枚举（`api/Enums.kt`），
 *   此处只引用不写死；机读锁定交给 `core/network/.../ClientVersionTest`。
 * - 实现 [ClientConfigProvider]：api 侧运行期配置的 app 侧取值
 *   （此前是 `session/impl/AppClientConfigProvider`，Phase 6 收编为本类并删除旧类）。
 */
@Singleton
class ClientConfigManager @Inject constructor() : ClientConfigProvider {

    /** V22 伪装版本（红线值不在此落字面量——见 [ClientVersion.TIEBA_V22] 与测试锁定）。 */
    val clientVersion: String get() = ClientVersion.TIEBA_V22.version

    override val userAgent: String? get() = App.Config.userAgent

    override val appFirstInstallTime: Long get() = App.Config.appFirstInstallTime

    override val appLastUpdateTime: Long get() = App.Config.appLastUpdateTime

    /** api 侧诊断日志的开关（原先 api 直接读 app 的 `BuildConfig.DEBUG`） */
    override val isDebug: Boolean get() = BuildConfig.DEBUG
}