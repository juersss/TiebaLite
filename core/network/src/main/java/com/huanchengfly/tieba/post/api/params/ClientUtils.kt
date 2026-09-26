package com.huanchengfly.tieba.post.api.params

import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.session.SessionProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 客户端标识的内存快照 + 首次同步。
 *
 * 结构大改 3b-prep-3（2026-09-17）：原先它直接持有 `Context`（弱引用 + `App.INSTANCE` 兜底）、
 * 读 `context.dataStore`，还依赖 `TiebaApi`——DataStore 属 core:data，会撞上"core 之间只依赖
 * core:common"的规则。现在落盘走 [com.huanchengfly.tieba.post.api.session.ClientIdStore] 接口，
 * Context 走 [SessionProviders]。
 *
 * 行为不变：内存字段仍是进程级快照；`sync()` 只在 init 时跑一次；`sampleId` 缺键时保留原值
 * （**不得让 null 覆盖**——否则本次会话所有请求丢 `sample_id`，与持久值分叉）。
 */
object ClientUtils {
    var clientId: String? = null
    var sampleId: String? = null
    var baiduId: String? = null
    var activeTimestamp: Long = System.currentTimeMillis()

    /**
     * 启动期调用一次（`App.onCreate`，且必须在 `SessionProviders.install` 之后）。
     *
     * @param syncEnabled 是否允许"联网同步 + 落盘"。**只有主进程可以传 true**：
     *   [sync] 会把服务端下发的 client_id/sample_id 写回 DataStore，而 DataStore
     *   不支持多进程并发写——`:oksign` 是 manifest 声明的独立进程，若也写就会与主进程
     *   互相覆盖/丢更新（2026-09-26 收口；进程判定在 app 侧的 `utils/ProcessUtil`）。
     *   非主进程传 false：内存快照照常读出来（请求参数要用），只是不联网、不落盘。
     */
    fun init(syncEnabled: Boolean = true) {
        CoroutineScope(Dispatchers.IO).launch {
            val store = SessionProviders.clientIds
            clientId = store.readClientId()
            sampleId = store.readSampleId()
            baiduId = store.readBaiduId()
            activeTimestamp = store.readActiveTimestamp() ?: System.currentTimeMillis()
            if (syncEnabled) sync()
        }
    }

    suspend fun saveBaiduId(id: String) {
        baiduId = id
        SessionProviders.clientIds.writeBaiduId(id)
    }

    suspend fun setActiveTimestamp() {
        activeTimestamp = System.currentTimeMillis()
        SessionProviders.clientIds.writeActiveTimestamp(activeTimestamp)
    }

    private suspend fun sync() {
        TiebaApi.getInstance()
            .syncFlow(clientId)
            .catch { it.printStackTrace() }
            .collect { sync ->
                // catch 在 collect 上游,拦不到 collect 内的异常:缺键字段(null)在这里
                // 判空跳过,否则裸协程 NPE 直达未捕获处理器崩进程(客户端 id 缺失即放弃同步)
                val newClientId = sync.client?.clientId ?: return@collect
                // sampleId 内存/落盘同口径:缺键时保留原值(DataStore 与内存),不得让
                // null 覆盖内存造成本次会话所有请求丢 sample_id、与持久值分叉
                sync.wlConfig?.sampleId?.let { sampleId = it }
                SessionProviders.clientIds.writeClientId(newClientId, sampleId)
            }
    }
}
