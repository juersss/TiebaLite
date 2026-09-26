package com.huanchengfly.tieba.post.selfcheck

import android.content.Context
import com.huanchengfly.tieba.post.api.session.SessionProviders

/**
 * 自检用例框架。
 *
 * 与单测的分工：
 * - 单测（246 例）跑在 JVM 上、进 CI 门禁，管**纯逻辑**；
 * - 自检面板跑在真机/模拟器上，管**单测够不着的东西**：数据落盘名、Room 可读、
 *   真实网络链路、图片链路、UI 导航。两者互补，不重复造。
 *
 * 执行协议：[check] 返回 [SelfCheckOutcome]；抛 AssertionError 视为失败（原因取异常消息），
 * 抛其它异常视为"崩溃"失败。
 */
interface SelfCheckCase {
    /** 分组名，面板按组展示 */
    val group: String

    /** 用例名 */
    val name: String

    /**
     * 事先声明需要登录态。面板在未登录时**连请求都不发**，直接标跳过。
     *
     * 网络/图片组当前一律 false：这些用例自己会在拿不到数据时调 [skipOrFail] 判定
     * （有登录态算失败、无登录态算跳过），比"事先一刀切"多一层证据。
     */
    val requiresLogin: Boolean get() = false

    suspend fun check(context: Context): SelfCheckOutcome
}

/**
 * 自检结果。
 *
 * 为什么不是 `String?`（null=通过）：网络/图片/UI 三组会撞上"这次没法判定"的情形
 * ——未登录、服务端明确回业务错误、响应里本就没有图片。这类结果既不是通过也不是失败，
 * 必须能如实标成 ⏭️ 并带上原因，否则只能在"假绿"和"假红"之间二选一。
 */
sealed interface SelfCheckOutcome {
    data object Passed : SelfCheckOutcome

    data class Failed(val reason: String) : SelfCheckOutcome

    data class Skipped(val reason: String) : SelfCheckOutcome
}

internal fun pass(): SelfCheckOutcome = SelfCheckOutcome.Passed

internal fun fail(reason: String): SelfCheckOutcome = SelfCheckOutcome.Failed(reason)

internal fun skip(reason: String): SelfCheckOutcome = SelfCheckOutcome.Skipped(reason)

/**
 * "请求/取数失败"的统一裁决：已登录 → 真失败（链路确实坏了）；
 * 未登录 → 跳过（拿不到数据是预期的，不该记红）。
 */
internal fun skipOrFail(reason: String): SelfCheckOutcome =
    if (isLoggedIn()) fail(reason) else skip("未登录：$reason")

/** 登录态判定：SessionProviders 未初始化等异常一律按"未登录"处理，不把自检自身搞崩 */
internal fun isLoggedIn(): Boolean = runCatching {
    SessionProviders.credential.isLoggedIn()
}.getOrDefault(false)

/** 断言失败即用例失败（由框架转成失败原因） */
internal fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

internal fun <T> expectEquals(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what 期望 <$expected> 实际 <$actual>")
}

/** 异常信息的单行化（多用于请求失败原因；KDoc 里不引用 Android 类型） */
internal fun Throwable.shortMessage(): String =
    "${this::class.java.simpleName}: ${message ?: "无消息"}"
