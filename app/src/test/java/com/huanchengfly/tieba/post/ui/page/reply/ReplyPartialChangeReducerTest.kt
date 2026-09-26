package com.huanchengfly.tieba.post.ui.page.reply

import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ReplyPartialChange 纯函数 JVM 单测:锁定 ClearContent(重开回复框清残留)语义。
 *
 * ReplyDialog 的 ReplyViewModel 挂在所在导航页上、跨多次打开存活(见
 * [ReplyUiIntent.ClearContent] 注释):Send.Success 不清图片/成功标志,上一轮的
 * 选中图片若不清空会被带进下一轮发送,replySuccess 恒 true 还会让草稿停写。
 * 对话框内容每次打开全新组合时发起 ClearContent 整状态复位——本用例锁 reducer
 * 行为,防止"只清图片、忘复位 replySuccess(草稿停写)"之类的半吊子回归。
 */
class ReplyPartialChangeReducerTest {

    @Test
    fun clearContentResetsEphemeralStateToFresh() {
        val dirty = ReplyUiState(
            isSending = false,
            replySuccess = true,
            replyPanelType = ReplyPanelType.IMAGE,
            replyType = ReplyType.TOPIC_THREAD,
            isUploading = true,
            isOriginImage = true,
            selectedImageList = persistentListOf("content://a", "content://b"),
            uploadImageResultList = persistentListOf(
                UploadPictureResultBean(errorCode = "0", errorMsg = "", chunkNo = "1")
            ),
        )

        // 整状态复位:图片/上传结果/成功标志/面板与类型全部回到初始值
        assertEquals(ReplyUiState(), ReplyPartialChange.ClearContent.reduce(dirty))
    }

    @Test
    fun clearContentOnFreshStateIsIdentity() {
        assertEquals(ReplyUiState(), ReplyPartialChange.ClearContent.reduce(ReplyUiState()))
    }
}
