package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.models.ForumGuideBean.LikeForum

object FollowedForumsCache {
    @Volatile
    private var forumMap: Map<Long, LikeForum> = emptyMap()

    /**
     * 全量替换缓存
     *
     * 供首页“慢速路径”使用：后台全量同步完成后整体覆盖
     * 必须与 [mergeAll] 等共用同一把锁，否则快/慢路径并发时 read-modify-write 会互相覆盖
     */
    fun updateAll(forums: List<LikeForum>?) {
        synchronized(this) {
            forumMap = forums?.associateBy { it.forumId } ?: emptyMap()
        }
    }

    /**
     * 增量合并，不覆盖缓存中已有的其他吧
     *
     * 供首页“快速路径”使用：先并入前几页数据，后台全量同步完成后再整体替换
     */
    fun mergeAll(forums: List<LikeForum>?) {
        if (forums.isNullOrEmpty()) return
        synchronized(this) {
            val newMap = forumMap.toMutableMap()
            forums.forEach { newMap[it.forumId] = it }
            forumMap = newMap
        }
    }

    fun isFollowed(id: Long?): Boolean {
        if (id == null || id == 0L) return false
        return forumMap.containsKey(id)
    }

    fun updateOrAddFollowedForum(forum: LikeForum?) {
        if (forum == null || forum.forumId == 0L) return
        synchronized(this) {
            val newMap = forumMap.toMutableMap()
            newMap[forum.forumId] = forum
            forumMap = newMap
        }
    }

    fun removeFollowedForum(id: Long?) {
        if (id == null || id == 0L) return
        synchronized(this) {
            // 判空必须在锁内,避免在锁外 check-then-act 与并发写入产生竞争
            if (!forumMap.containsKey(id)) return
            val newMap = forumMap.toMutableMap()
            newMap.remove(id)
            forumMap = newMap
        }
    }

    fun getFollowedForum(id: Long?): LikeForum? {
        if (id == null || id == 0L) return null
        return forumMap[id]
    }

    fun getAllFollowedForums(): List<LikeForum> = forumMap.values.toList()
}