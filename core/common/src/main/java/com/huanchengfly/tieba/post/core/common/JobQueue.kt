package com.huanchengfly.tieba.post.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 串行任务队列（0ranko0P 同款）：`submit` 的任务按提交顺序**逐个执行**，前一个完成才开始下一个。
 *
 * 用途：DataStoreSettingsRepository 用它串行化全部写入——并发 `edit` 会互相覆盖，串行后
 * 每次写都是"读-改-写"原子链（配合 save 的 transform 语义）。调度模型：
 * 任务以 LAZY 协程入队，worker 循环逐个 join；`trySend` 非挂起，提交线程不被阻塞。
 *
 * 与 0ranko0P 原版的差异（2026-09-17 修）：worker 的 `job.join()` 会在**单个任务抛异常时
 * 把异常抛给 worker，worker 直接死亡、后续任务全部静默丢失**——DataStore 落盘偶发失败
 * （如 Windows 上 rename 覆盖）会一次性瘫痪整个写入管线。这里捕获单任务异常（仅
 * [CancellationException] 外抛），单点失败不拖垮队列。
 */
class JobQueue {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val queue = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.Default) {
            for (job in queue) {
                try {
                    job.join()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // 单个任务失败：吞掉，继续消费后续任务（失败已由调用方不可见地丢弃，串行性不受影响）
                }
            }
        }
    }

    fun submit(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        synchronized(this) {
            val job = scope.launch(context, CoroutineStart.LAZY, block)
            queue.trySend(job)
        }
    }

    fun cancel() {
        queue.cancel()
        scope.cancel()
    }
}
