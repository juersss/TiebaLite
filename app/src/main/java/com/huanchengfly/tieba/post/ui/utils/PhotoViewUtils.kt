package com.huanchengfly.tieba.post.ui.utils

import com.huanchengfly.tieba.post.api.models.protos.Media
import com.huanchengfly.tieba.post.api.models.protos.Post
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.models.LoadPicPageData
import com.huanchengfly.tieba.post.models.PhotoViewData
import com.huanchengfly.tieba.post.models.PicItem
import com.huanchengfly.tieba.post.ui.common.PicContentRender
import com.huanchengfly.tieba.post.utils.ImageUtil
import kotlinx.collections.immutable.toImmutableList

/**
 * 楼中楼图片专用:不携带 [LoadPicPageData],大图浏览器只用本楼中楼的图片直接浏览。
 * pb 图页接口按"楼层"取图,楼中楼图片的 picId 在父楼层的图片列表中不存在,
 * 会被回退到列表第一张——表现为点楼中楼小图打开的是主楼/父楼层的图片。
 */
fun getSubPostPhotoViewData(
    picRenders: List<PicContentRender>,
    index: Int
): PhotoViewData {
    return PhotoViewData(
        data = null,
        picItems = picRenders.mapIndexed { picIndex, pic ->
            PicItem(
                picId = pic.picId,
                picIndex = picIndex + 1,
                url = pic.picUrl,
                originUrl = pic.originUrl,
                showOriginBtn = pic.showOriginBtn,
                originSize = pic.originSize,
                postId = null
            )
        },
        index = index
    )
}

/**
 * 基于 [PicContentRender] 列表构建大图浏览数据（多图时可左右翻页）
 *
 * @param index 当前点击的图片在 [picRenders] 中的下标（从 0 开始）
 */
fun getPhotoViewData(
    post: Post,
    picRenders: List<PicContentRender>,
    index: Int,
    seeLz: Boolean = false
): PhotoViewData? {
    if (post.from_forum == null) return null
    val current = picRenders.getOrNull(index) ?: return null
    return PhotoViewData(
        data = LoadPicPageData(
            forumId = post.from_forum.id,
            forumName = post.from_forum.name,
            threadId = post.tid,
            postId = post.id,
            objType = "pb",
            picId = current.picId,
            picIndex = index + 1,
            seeLz = seeLz,
            originUrl = current.originUrl,
        ),
        picItems = picRenders.mapIndexed { picIndex, pic ->
            PicItem(
                picId = pic.picId,
                picIndex = picIndex + 1,
                url = pic.picUrl,
                originUrl = pic.originUrl,
                showOriginBtn = pic.showOriginBtn,
                originSize = pic.originSize,
                postId = post.id
            )
        }.toImmutableList(),
        index = index
    )
}

fun getPhotoViewData(
    threadInfo: ThreadInfo,
    index: Int
): PhotoViewData {
    return getPhotoViewData(
        medias = threadInfo.media,
        forumId = threadInfo.forumId,
        forumName = threadInfo.forumName,
        threadId = threadInfo.threadId,
        index = index
    )
}

fun getPhotoViewData(
    medias: List<Media>,
    forumId: Long,
    forumName: String,
    threadId: Long,
    index: Int
): PhotoViewData {
    val media = medias[index]
    return PhotoViewData(
        data = LoadPicPageData(
            forumId = forumId,
            forumName = forumName,
            threadId = threadId,
            postId = media.postId,
            seeLz = false,
            objType = "index",
            picId = ImageUtil.getPicId(media.originPic),
            picIndex = index + 1,
            originUrl = media.originPic
        ),
        picItems = medias.mapIndexed { mediaIndex, mediaItem ->
            PicItem(
                picId = ImageUtil.getPicId(mediaItem.originPic),
                picIndex = mediaIndex + 1,
                url = mediaItem.bigPic,
                originUrl = mediaItem.originPic,
                showOriginBtn = mediaItem.showOriginalBtn == 1,
                originSize = mediaItem.originSize,
                postId = mediaItem.postId
            )
        }.toImmutableList(),
        index = index
    )
}