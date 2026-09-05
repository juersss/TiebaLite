package com.huanchengfly.tieba.post.components

import android.util.LruCache
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.ramcosta.composedestinations.navargs.DestinationsNavTypeSerializer
import com.ramcosta.composedestinations.navargs.NavTypeSerializer


object ThreadNavBridge {
    private const val MAX_CACHE_SIZE = 4

    private val cache = LruCache<Long, ThreadInfo>(MAX_CACHE_SIZE)

    fun put(data: ThreadInfo): String {
        // thread_id 缺省(0)时不同帖子会折叠到同一缓存键、互相污染首帧预渲染——
        // 兜底换一次性唯一键(缓存本为内存态,进程死亡后本就静默降级为空 ThreadInfo)
        val id = data.threadId.takeIf { it != 0L } ?: System.nanoTime()
        cache.put(id, data)
        return id.toString()
    }

    fun get(key: String): ThreadInfo? {
        val id = key.toLongOrNull() ?: return null
        return cache.get(id)
    }
}

@NavTypeSerializer
class ThreadInfoSerializer : DestinationsNavTypeSerializer<ThreadInfo> {
    override fun toRouteString(value: ThreadInfo): String {
        return ThreadNavBridge.put(value)
    }

    override fun fromRouteString(routeStr: String): ThreadInfo {
        return ThreadNavBridge.get(routeStr) ?: ThreadInfo()
    }
}