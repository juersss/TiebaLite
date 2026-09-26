package com.huanchengfly.tieba.post.utils

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * 进程身份判定（2026-09-26 新增，服务"**非主进程不得写共享持久层**"口径）。
 *
 * 背景：`App.onCreate` 在**每个**进程都会跑，而 `AndroidManifest.xml` 里
 * `OKSignService` 声明了 `android:process=":oksign"`（签到独立进程，避免长任务拖累主进程）。
 * 但 `tblite.db` 与 `app_preferences`(DataStore) **都不是多进程安全的**：
 *
 * - `tblite.db`：进程内 Mutex 不跨进程 → 退出删号与 `:oksign` 在途刷新竞争可让账号复活
 *   （挂账 §三-16；账号侧的根治见 `AccountDao.refreshByUid` 的 update-only 口径）。
 * - `app_preferences`：DataStore 官方明确不支持多进程；两个进程同时写会互相覆盖/丢更新。
 *
 * 因此凡是"启动期会写共享持久层"的初始化，都必须先过 [isMainProcess]。
 *
 * 结果**进程内缓存**：进程名在进程生命周期内不会变，且本方法可能在启动热路径被反复调用。
 */
object ProcessUtil {

    @Volatile
    private var cachedProcessName: String? = null

    @Volatile
    private var cachedIsMain: Boolean? = null

    /**
     * 当前进程名；取不到时返回 null（调用方须自行兜底）。
     *
     * 取法按版本分流：API 28+ 用 `Application.getProcessName()`（唯一无副作用的官方 API）；
     * 更低版本退回 `ActivityManager.runningAppProcesses` 按 pid 匹配；两者都拿不到时
     * 读 `/proc/self/cmdline`（部分 OEM 的 `runningAppProcesses` 会返回空列表）。
     */
    fun currentProcessName(context: Context): String? {
        cachedProcessName?.let { return it }
        val name = runCatching { resolveProcessName(context.applicationContext) }.getOrNull()
        if (name != null) cachedProcessName = name
        return name
    }

    private fun resolveProcessName(appContext: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()?.let { return it }
        }
        runCatching {
            val manager =
                appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pid = Process.myPid()
            manager.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
                ?.let { return it }
        }
        return runCatching {
            java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        }.getOrNull()
    }

    /**
     * 本进程是否为主进程（进程名 == applicationId）。
     *
     * **兜底口径：进程名解析失败时返回 true**。宁可让主进程照常工作（最坏是回到
     * "多进程各写各的"的旧行为），也不能因判定失败让主进程静默丢掉 client_id /
     * 设置的落盘——后者会让全部请求缺参、设置不生效，是更严重的故障。
     */
    fun isMainProcess(context: Context): Boolean {
        cachedIsMain?.let { return it }
        val appContext = context.applicationContext
        val name = currentProcessName(appContext)
        val isMain = name == null || name == appContext.packageName
        cachedIsMain = isMain
        return isMain
    }
}
