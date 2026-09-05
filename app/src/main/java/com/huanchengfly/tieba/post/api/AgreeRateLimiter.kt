package com.huanchengfly.tieba.post.api

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 赞/踩接口限流器
 *
 * 贴吧对 /c/c/agree/opAgree 有风控，高频调用会牵连账号的发帖能力（严重时发帖秒删）。
 * 点踩功能上线后调用量增加，客户端侧先做两层约束：
 * - 同一对象两次操作最小间隔 [MIN_INTERVAL_MS] 毫秒
 * - 全局每分钟最多 [MAX_PER_MINUTE] 次
 *
 * 已知问题 1（配额口径）：赞踩互斥时切换一次手势要发两个请求（撤销旧态 + 置新态），
 * 两者都计入全局窗口，因此 10 次/分 实际只允许约 5 次手势/分。此处刻意不调整
 * [MAX_PER_MINUTE]，代价由用户承担以换取风控安全；若要放开，应在"手势"层计数
 * 而不是在请求层计数。
 *
 * 已解决问题（key 冲突）：调用侧曾按页面自造前缀（`"thread_$id"`/`"post_$id"`），同一服务端
 * 对象（首楼 postId==threadId 时底栏与首楼）持有两个 key，3 秒同对象间隔可交替绕过；
 * 楼中楼与楼层又共用 `"post_"` 前缀，两类 id 空间独立却可能互相受限。现统一由 [keyFor]
 * 按 `objType_id` 生成（与 [OpRecordStore.key] 同构），同类对象共享窗口、异类对象互不影响。
 */
object AgreeRateLimiter {
    const val MIN_INTERVAL_MS = 3_000L
    const val MAX_PER_MINUTE = 10

    /**
     * 赞踩限流的唯一 key 工厂：`"$objType_$id"`。
     *
     * 键式与 [OpRecordStore.key] 刻意保持一致——同一个服务端对象在限流窗口与差分记录
     * 两套机制里必须指到同一个条目；格式若调整需两处同步。
     * 调用侧禁止再手拼 `"post_$id"` 之类的前缀 key。
     */
    fun keyFor(objType: Int, id: Long): String = "${objType}_$id"

    /** 对象时间戳超过该条数才触发一次清理，避免每次调用都全表扫描 */
    private const val MAX_TRACKED_KEYS = 256

    private const val WINDOW_MS = 60_000L

    /** 保证 check-then-act 原子性：并发调用下"读时间戳→判断→写入"会互相穿透 */
    private val lock = Any()

    /** key -> 该对象上次操作时刻，取值来自 [SystemClock.elapsedRealtime]（毫秒，含深睡眠） */
    private val lastOpAt = ConcurrentHashMap<String, Long>()

    /** 全局滑动窗口内的操作时刻，取值同样来自 [SystemClock.elapsedRealtime]（毫秒） */
    private val recentOps = ConcurrentLinkedQueue<Long>()

    /**
     * 时间源。默认取 [SystemClock.elapsedRealtime]（含深睡眠的单调时钟）。
     *
     * 抽成可替换字段是为了让限流逻辑能在 JVM 单测里被验证——限流窗口与最小间隔
     * 都是时间相关行为，若直连 SystemClock 就只能靠 instrumentation 测试或真机手点。
     * 单测通过 [resetForTest] 注入可控时钟。
     */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    fun tryAcquire(key: String, checkPerObject: Boolean = true): Boolean {
        // 用 elapsedRealtime 而非 currentTimeMillis:墙钟会被 NTP 校时/用户改时间影响,
        // 时间跳变会让间隔判断误放行或长时间误限流
        val now = clock()
        synchronized(lock) {
            // 赞踩互斥的成对撤销请求属同一次用户手势,可跳过同对象间隔(仍计全局每分钟上限)
            // 用 null 判断"从未操作"而非默认 0L:elapsedRealtime 开机即接近 0,
            // 0L 作默认值既会在开机瞬间误判,也避免 now - Long.MIN_VALUE 溢出
            val last = lastOpAt[key]
            if (checkPerObject && last != null && now - last < MIN_INTERVAL_MS) return false

            // 滑出 1 分钟窗口的全局记录不再计入配额
            while (true) {
                val head = recentOps.peek() ?: break
                if (now - head > WINDOW_MS) recentOps.poll() else break
            }
            if (recentOps.size >= MAX_PER_MINUTE) return false

            lastOpAt[key] = now
            recentOps.add(now)

            // 顺带清理:条目数超阈值时剔除已过最小间隔的对象时间戳,
            // 否则 lastOpAt 只增不减,长时间使用会一直膨胀
            if (lastOpAt.size > MAX_TRACKED_KEYS) {
                val expiredBefore = now - MIN_INTERVAL_MS
                val it = lastOpAt.entries.iterator()
                while (it.hasNext()) {
                    if (it.next().value <= expiredBefore) it.remove()
                }
            }
            return true
        }
    }

    /**
     * 仅供单测:清空全部限流状态并把时钟重置为默认值。
     * 限流器是进程级单例,用例之间若不复位会互相污染。
     */
    internal fun resetForTest() {
        synchronized(lock) {
            lastOpAt.clear()
            recentOps.clear()
            clock = { SystemClock.elapsedRealtime() }
        }
    }
}

/**
 * 触发限流时抛出，errorCode 使用 [AgreeParams.RATE_LIMIT_ERROR_CODE]，
 * 便于 UI 层识别并给出提示
 */
class TiebaRateLimitedException :
    Exception("操作过于频繁，请稍后再试")
