package com.huanchengfly.tieba.post.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级 fire-and-forget 协程作用域(外部审查-协程治理,替代裸 GlobalScope)。
 *
 * 裸 GlobalScope 无结构化语义:协程异常直通进程级 handler,历史上各调用点被迫
 * 各自包 runCatching 兜底。统一收口到这里:
 * - SupervisorJob:单任务失败不牵连同 scope 的其他任务;
 * - Default 调度器:需要 IO 的调用方显式 launch(Dispatchers.IO)。
 * 生命周期与进程一致(本就无宿主生命周期可挂靠),不做取消。
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)
