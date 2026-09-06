package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.models.MessageListBean
import com.huanchengfly.tieba.post.api.models.protos.Post
import com.huanchengfly.tieba.post.api.models.protos.SubPostList
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.abstractText
import com.huanchengfly.tieba.post.api.models.protos.plainText
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

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
            author?.name?.ifEmpty { author.nameShow })

    fun Post.shouldBlock(): Boolean =
        shouldBlock(content.plainText) || shouldBlock(
            author_id.takeIf { it != 0L } ?: (author?.id ?: -1),
            author?.name?.ifEmpty { author.nameShow })

    fun SubPostList.shouldBlock(): Boolean =
        shouldBlock(content.plainText) || shouldBlock(
            author_id.takeIf { it != 0L } ?: (author?.id ?: -1),
            author?.name?.ifEmpty { author.nameShow })

    fun MessageListBean.MessageInfoBean.shouldBlock(): Boolean =
        shouldBlock(content.orEmpty()) || shouldBlock(
            this.replyer?.id?.toLongOrNull() ?: -1,
            this.replyer?.name?.ifEmpty { this.replyer.nameShow }
        )
}
