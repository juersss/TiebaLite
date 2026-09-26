package com.huanchengfly.tieba.post.api.session

/**
 * 客户端运行期配置——api 包此前直接读 `App.Config`（如 WebView 默认 UA）。
 */
interface ClientConfigProvider {
    /** WebView 默认 UA；拿不到时返回 null，由调用方回落到内置 UA */
    val userAgent: String?

    /** 首次安装时间（协议字段 first_install_time） */
    val appFirstInstallTime: Long

    /** 最后更新时间（协议字段 last_update_time） */
    val appLastUpdateTime: Long
    /** 是否 debug 构建（api 侧诊断日志的门控；实现侧读 app 的 `BuildConfig.DEBUG`） */
    val isDebug: Boolean
}
