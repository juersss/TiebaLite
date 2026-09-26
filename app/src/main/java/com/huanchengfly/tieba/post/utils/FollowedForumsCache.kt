package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.models.ForumGuideBean.LikeForum
import com.huanchengfly.tieba.post.core.common.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 关注吧的内存缓存 + **按账号写穿透到 Room**（2026-09-26，§8.7 候选 5）。
 *
 * 为什么要落盘：本对象此前是纯内存单例，冷启动/切号后要等首页"慢路径"全量同步
 * （54 个串行请求）完成才有数据。这段窗口里 `isFollowed()` 按空表判——"只看关注吧"
 * 会被过滤成空、关注态显示不对。落到 Room 后 [preload] 能直接预载，不必等网络。
 *
 * 三条口径：
 * 1. **账号维度**：所有落库都带 uid，主键 (`uid`,`forum_id`)，切号按新 uid 预载，不串号；
 * 2. **写穿透绝不阻塞调用方**：全部走 [AppScope] + `Dispatchers.IO` 的 fire-and-forget，
 *    失败静默（缓存写失败不该影响 UI；下次同步会自愈）；
 * 3. **不写"不属于当前账号"的数据**：见 [persistWithUid] 的 ownerUid 守卫。
 */
object FollowedForumsCache {
    /**
     * 复合操作(read-modify-write)的私有锁。
     *
     * 此前的 `synchronized(this)` 把**公开单例对象**本身当锁:任何外部代码只要对该
     * 对象加锁,就能阻塞本缓存的全量替换,反之亦然——锁的作用域不可控。改用私有锁
     * 对象后,锁的可见范围收敛到本文件。
     */
    private val lock = Any()

    @Volatile
    private var forumMap: Map<Long, LikeForum> = emptyMap()

    /**
     * 内存缓存当前归属的 uid（空串 = 未登录/无主）。
     *
     * 落库一律以它为准，**绝不现取 `AccountUtil.getUid()` 当写入目标**——那会在
     * "切号之后才到达的旧响应"上取到**新** uid，把上一个账号的关注吧写进新账号名下。
     */
    @Volatile
    private var ownerUid: String = ""

    /**
     * 全量替换缓存（首页"慢路径"：后台全量同步完成，结果权威）
     *
     * 必须与 [mergeAll] 等共用同一把锁，否则快/慢路径并发时 read-modify-write 会互相覆盖。
     * 落库用 `replaceAll`（先删后插、同一事务）——与内存的"整体替换"语义一致。
     */
    fun updateAll(forums: List<LikeForum>?) {
        synchronized(lock) {
            forumMap = forums?.associateBy { it.forumId } ?: emptyMap()
        }
        persistWithUid { uid ->
            val rows = forums.orEmpty().map { it.toFollowedForum(uid) }
            AppScope.launch(Dispatchers.IO) {
                runCatching { DatabaseUtil.replaceFollowedForums(uid, rows) }
            }
        }
    }

    /**
     * 增量合并，不覆盖缓存中已有的其他吧（首页"快速路径"：先并入前几页）
     *
     * 落库用 `mergeAll`（只覆盖传入的 forum_id）——**绝不能**用 `replaceAll`：
     * 快路径可能提前截断，整体替换会把后面几页的关注吧静默删掉。
     */
    fun mergeAll(forums: List<LikeForum>?) {
        if (forums.isNullOrEmpty()) return
        synchronized(lock) {
            val newMap = forumMap.toMutableMap()
            forums.forEach { newMap[it.forumId] = it }
            forumMap = newMap
        }
        persistWithUid { uid ->
            val rows = forums.map { it.toFollowedForum(uid) }
            AppScope.launch(Dispatchers.IO) {
                runCatching { DatabaseUtil.mergeFollowedForums(uid, rows) }
            }
        }
    }

    fun isFollowed(id: Long?): Boolean {
        if (id == null || id == 0L) return false
        return forumMap.containsKey(id)
    }

    fun updateOrAddFollowedForum(forum: LikeForum?) {
        if (forum == null || forum.forumId == 0L) return
        synchronized(lock) {
            val newMap = forumMap.toMutableMap()
            newMap[forum.forumId] = forum
            forumMap = newMap
        }
        persistWithUid { uid ->
            AppScope.launch(Dispatchers.IO) {
                runCatching { DatabaseUtil.mergeFollowedForums(uid, listOf(forum.toFollowedForum(uid))) }
            }
        }
    }

    fun removeFollowedForum(id: Long?) {
        if (id == null || id == 0L) return
        synchronized(lock) {
            // 判空必须在锁内,避免在锁外 check-then-act 与并发写入产生竞争
            if (!forumMap.containsKey(id)) return
            val newMap = forumMap.toMutableMap()
            newMap.remove(id)
            forumMap = newMap
        }
        persistWithUid { uid ->
            AppScope.launch(Dispatchers.IO) {
                runCatching { DatabaseUtil.deleteFollowedForum(uid, id) }
            }
        }
    }

    fun getFollowedForum(id: Long?): LikeForum? {
        if (id == null || id == 0L) return null
        return forumMap[id]
    }

    fun getAllFollowedForums(): List<LikeForum> = forumMap.values.toList()

    /**
     * 账号切换/退出登录后重载缓存。
     *
     * 关注吧列表是账号私有数据：不清则新账号的"只看关注吧"过滤
     * （`PersonalizedViewModel.isFollowed`）与关注状态判定全部按上一个账号的吧单来。
     * 清空后立刻 [preload] 新账号的**落盘副本**——这样切号后不必等一次网络全量同步
     * 就有正确的关注态（这正是本对象落盘的目的）。
     */
    fun onAccountSwitched(uid: String?) {
        synchronized(lock) { forumMap = emptyMap() }
        preload(uid)
    }

    /**
     * 冷启动/切号时从 Room 预载某账号的关注吧。
     *
     * **只填空、不覆盖**：预载是异步的，期间网络的快/慢路径可能已经写进更新的数据
     * （`updateAll` 的结果是权威的）——此时必须让网络数据赢，不能被落盘副本盖回去。
     * 同理，`ownerUid` 变了（又切号了）就丢弃这次预载结果。
     */
    fun preload(uid: String?) {
        val target = uid?.trim().orEmpty()
        ownerUid = target
        if (target.isEmpty()) return
        AppScope.launch(Dispatchers.IO) {
            val rows = runCatching { DatabaseUtil.getFollowedForums(target) }
                .getOrNull()
                .orEmpty()
            if (rows.isEmpty()) return@launch
            synchronized(lock) {
                if (ownerUid == target && forumMap.isEmpty()) {
                    forumMap = rows.associate { it.forumId to it.toLikeForum() }
                }
            }
        }
    }

    /**
     * 落库前的账号守卫 + 异步派发。
     *
     * 两道判据：
     * 1. `ownerUid` 为空 → 还没登录/还没预载，没有归属账号，不写；
     * 2. `ownerUid` 与**实时** uid 不一致 → 响应到达前已经切号，这份数据属于上一个账号，
     *    写下去就是跨账号污染（内存缓存侧的历史行为是"被 onAccountSwitched 清掉"，
     *    但落盘的数据会活到下次启动，所以必须在这里拦住）。
     */
    private fun persistWithUid(action: (uid: String) -> Unit) {
        val uid = ownerUid
        if (uid.isEmpty()) return
        if (AccountUtil.getUid() != uid) return
        action(uid)
    }

    /**
     * 仅供单测:清空缓存。本对象是进程级单例,用例之间若不复位会互相污染
     * (与 [OpRecordStore.resetForTest] 同一口径)。
     */
    internal fun resetForTest() {
        synchronized(lock) {
            forumMap = emptyMap()
            ownerUid = ""
        }
    }
}
