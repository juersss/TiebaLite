package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.models.MessageListBean
import com.huanchengfly.tieba.post.core.network.model.protos.Post
import com.huanchengfly.tieba.post.core.network.model.protos.SubPostList
import com.huanchengfly.tieba.post.core.network.model.protos.ThreadInfo
import com.huanchengfly.tieba.post.ui.common.abstractText
import com.huanchengfly.tieba.post.ui.common.plainText
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
import com.huanchengfly.tieba.post.core.common.AppScope

object BlockManager {
    // CopyOnWriteArrayList(R9-F2):写侧在 Dispatchers.IO(加/删黑名单),读侧 shouldBlock
    // 跑在信息流过滤的 IO 上下文——普通 ArrayList 跨线程读写会 CME/撕裂;读多写少故 COW
    private val blockList: MutableList<Block> = CopyOnWriteArrayList()

    val blackList: List<Block>
        get() = blockList.filter { it.category == Block.CATEGORY_BLACK_LIST }

    val whiteList: List<Block>
        get() = blockList.filter { it.category == Block.CATEGORY_WHITE_LIST }

    suspend fun addBlock(block: Block): Block {
        val id = DatabaseUtil.insertBlock(block)
        val savedBlock = block.copy(id = id)
        blockList.add(savedBlock)
        return savedBlock
    }

    fun addBlockAsync(
        block: Block,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        AppScope.launch(Dispatchers.IO) {
            // 兜底(R7-⑤,09-06 收口):DB 异常崩在裸协程上会带崩进程;
            // 失败时 callback 不触发,UI 不谎报"已加入"
            runCatching {
                val id = DatabaseUtil.insertBlock(block)
                val savedBlock = block.copy(id = id)
                blockList.add(savedBlock)
                callback?.invoke(true)
            }
        }
    }

    suspend fun removeBlock(id: Long) {
        DatabaseUtil.deleteBlockById(id)
        blockList.removeAll { it.id == id }
    }

    suspend fun init() {
        blockList.addAll(DatabaseUtil.getAllBlocks())
    }

    /**
     * 账号切换/退出后重载内存缓存（2026-09-26）。
     *
     * 黑/白名单自本次起**按账号隔离**：`DatabaseUtil.getAllBlocks()` 的查询已经带 uid，
     * 但 [blockList] 是**内存镜像**——不重载的话 `shouldBlock` 会继续按上一个账号的名单过滤内容，
     * 表现为"数据库分开了、界面还是旧账号的屏蔽规则"。
     *
     * **先同步清空、再异步重载**：清空 = 短暂"不过滤"（宽松降级），
     * 沿用旧名单 = 按**别人的**口味屏蔽（方向错误）。两者都要不得时选前者。
     */
    fun onAccountSwitched() {
        blockList.clear()
        AppScope.launch(Dispatchers.IO) {
            // 与 init 同口径兜底：裸协程抛错会带崩进程
            runCatching { blockList.addAll(DatabaseUtil.getAllBlocks()) }
        }
    }

    fun shouldBlock(content: String): Boolean {
        val isWhite = whiteList.any { block ->
            block.type == Block.TYPE_KEYWORD && block.getKeywords().any { keyword ->
                if (block.isRegex) {
                    try {
                        Pattern.compile(keyword).matcher(content).find()
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    content.contains(keyword)
                }
            }
        }
        if (isWhite)
            return false
        val isBlack = blackList.any { block ->
            block.type == Block.TYPE_KEYWORD && block.getKeywords().any { keyword ->
                if (block.isRegex) {
                    try {
                        Pattern.compile(keyword).matcher(content).find()
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    content.contains(keyword)
                }
            }
        }
        return isBlack
    }

    fun shouldBlock(userId: Long = 0L, userName: String? = null): Boolean {
        val isWhite = whiteList.any { block ->
            !block.isRegex &&
                    block.type == Block.TYPE_USER &&
                    (block.uid == userId.toString() || block.username == userName)
        }
        if (isWhite) return false

        val isBlack = blackList.any { block ->
            !block.isRegex &&
                    block.type == Block.TYPE_USER &&
                    (block.uid == userId.toString() || block.username == userName)
        }
        return isBlack
    }

    fun ThreadInfo.shouldBlock(): Boolean =
        shouldBlock(title) || shouldBlock(abstractText) || shouldBlock(
            authorId.takeIf { it != 0L } ?: (author?.id ?: -1),
            author?.name?.ifEmpty { author!!.nameShow })

    fun Post.shouldBlock(): Boolean =
        shouldBlock(content.plainText) || shouldBlock(
            author_id.takeIf { it != 0L } ?: (author?.id ?: -1),
            author?.name?.ifEmpty { author!!.nameShow })

    fun SubPostList.shouldBlock(): Boolean =
        shouldBlock(content.plainText) || shouldBlock(
            author_id.takeIf { it != 0L } ?: (author?.id ?: -1),
            author?.name?.ifEmpty { author!!.nameShow })

    fun MessageListBean.MessageInfoBean.shouldBlock(): Boolean =
        shouldBlock(content.orEmpty()) || shouldBlock(
            this.replyer?.id?.toLongOrNull() ?: -1,
            this.replyer?.name?.ifEmpty { this.replyer!!.nameShow }
        )
}
