package com.huanchengfly.tieba.post.api.models.protos

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.ui.common.PbContentRender
import com.huanchengfly.tieba.post.ui.common.PicContentRender
import com.huanchengfly.tieba.post.ui.common.TextContentRender.Companion.appendText
import com.huanchengfly.tieba.post.ui.common.VideoContentRender
import com.huanchengfly.tieba.post.ui.common.VoiceContentRender
import com.huanchengfly.tieba.post.ui.common.theme.utils.ThemeUtils
import com.huanchengfly.tieba.post.ui.page.thread.SubPostItemData
import com.huanchengfly.tieba.post.ui.utils.getPhotoViewData
import com.huanchengfly.tieba.post.ui.utils.getSubPostPhotoViewData
import com.huanchengfly.tieba.post.utils.EmoticonManager
import com.huanchengfly.tieba.post.utils.EmoticonUtil.emoticonString
import com.huanchengfly.tieba.post.utils.ImageUtil
import com.huanchengfly.tieba.post.utils.StringUtil
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

val List<Abstract>.abstractText: String
    get() = joinToString(separator = "") {
        when (it.type) {
            0 -> it.text.replace(Regex(" {2,}"), " ")
            4 -> it.text

            else -> ""
        }
    }

@OptIn(ExperimentalTextApi::class)
val ThreadInfo.abstractText: String
    get() = richAbstract.joinToString(separator = "") {
        when (it.type) {
            0,40 -> it.text.replace(Regex(" {2,}"), " ")
            2 -> {
                EmoticonManager.registerEmoticon(it.text, it.c)
                "#(${it.c})"
            }
            else -> ""
        }
    }

val PostInfoList.abstractText: String
    get() = rich_abstract.joinToString(separator = "") {
        when (it.type) {
            0 -> it.text.replace(Regex(" {2,}"), " ")
            2 -> {
                EmoticonManager.registerEmoticon(it.text, it.c)
                "#(${it.c})"
            }

            else -> ""
        }
    }

val ThreadInfo.hasAgree: Int
    get() = agree?.hasAgree ?: 0
val ThreadInfo.hasAgreed: Boolean
    get() = hasAgree == 1
val ThreadInfo.hasAbstract: Boolean
    get() = richAbstract.any { (it.type == 0 && it.text.isNotBlank()) || it.type == 2 }

// 赞踩状态机改造:旧的 updateAgreeStatus 系列"直接改写 proto 计数字段"的扩展
// 已全部删除——计数永不被修改、±1 由差分公式自然得出,是消灭计数漂移整类 bug 的前提。
// 记录更新唯一入口:OpRecordStore(setPending/confirm/revertPending/rebase)。

fun ThreadInfo.updateCollectStatus(
    newStatus: Int,
    markPostId: Long
) = if (collectStatus != newStatus) {
    this.copy(
        collectStatus = newStatus,
        collectMarkPid = markPostId.toString()
    )
} else {
    this
}

private val PbContent.picUrl: String
    get() =
        ImageUtil.getUrl(
            App.INSTANCE,
            true,
            originSrc,
            bigCdnSrc,
            bigSrc,
            dynamic_,
            cdnSrc,
            cdnSrcActive,
            src
        )

val List<PbContent>.plainText: String
    get() = renders.joinToString("\n") { it.toString() }

@OptIn(ExperimentalTextApi::class)
val List<PbContent>.renders: ImmutableList<PbContentRender>
    get() {
        val renders = mutableListOf<PbContentRender>()

        forEach {
            when (it.type) {
                0, 9, 27, 35, 40 -> {
                    renders.appendText(it.text)
                }

                1 -> {
                    val text = buildAnnotatedString {
                        appendInlineContent("link_icon", alternateText = "🔗")
                        withAnnotation(tag = "url", annotation = it.link) {
                            withStyle(
                                SpanStyle(
                                    color = Color(
                                        ThemeUtils.getColorByAttr(
                                            App.INSTANCE,
                                            R.attr.colorNewPrimary
                                        )
                                    )
                                )
                            ) {
                                append(it.text)
                            }
                        }
                    }
                    renders.appendText(text)
                }

                2 -> {
                    EmoticonManager.registerEmoticon(
                        it.text,
                        it.c
                    )
                    val emoticonText = "#(${it.c})".emoticonString
                    renders.appendText(emoticonText)
                }

                3 -> {
                    val width = it.bsize.split(",")[0].toInt()
                    val height = it.bsize.split(",")[1].toInt()
                    renders.add(
                        PicContentRender(
                            picUrl = it.picUrl,
                            originUrl = it.originSrc,
                            showOriginBtn = it.showOriginalBtn == 1,
                            originSize = it.originSize,
                            picId = ImageUtil.getPicId(it.originSrc),
                            width = width,
                            height = height
                        )
                    )
                }

                4 -> {
                    val text = buildAnnotatedString {
                        withAnnotation(tag = "user", annotation = "${it.uid}") {
                            withStyle(
                                SpanStyle(
                                    color = Color(
                                        ThemeUtils.getColorByAttr(
                                            App.INSTANCE,
                                            R.attr.colorNewPrimary
                                        )
                                    )
                                )
                            ) {
                                append(it.text)
                            }
                        }
                    }
                    renders.appendText(text)
                }

                5 -> {
                    if (it.src.isNotBlank()) {
                        val width = it.bsize.split(",")[0].toInt()
                        val height = it.bsize.split(",")[1].toInt()
                        renders.add(
                            VideoContentRender(
                                videoUrl = it.link,
                                picUrl = it.src,
                                webUrl = it.text,
                                width = width,
                                height = height
                            )
                        )
                    } else {
                        val text = buildAnnotatedString {
                            appendInlineContent("video_icon", alternateText = "🎥")
                            withAnnotation(tag = "url", annotation = it.text) {
                                withStyle(
                                    SpanStyle(
                                        color = Color(
                                            ThemeUtils.getColorByAttr(
                                                App.INSTANCE,
                                                R.attr.colorNewPrimary
                                            )
                                        )
                                    )
                                ) {
                                    append(App.INSTANCE.getString(R.string.tag_video))
                                    append(it.text)
                                }
                            }
                        }
                        renders.appendText(text)
                    }
                }

                10 -> {
                    renders.add(VoiceContentRender(it.voiceMD5, it.duringTime))
                }

                20 -> {
                    val width = it.bsize.split(",")[0].toInt()
                    val height = it.bsize.split(",")[1].toInt()
                    renders.add(
                        PicContentRender(
                            picUrl = it.src,
                            originUrl = it.src,
                            showOriginBtn = it.showOriginalBtn == 1,
                            originSize = it.originSize,
                            picId = ImageUtil.getPicId(it.src),
                            width = width,
                            height = height
                        )
                    )
                }
            }
        }

        return renders.toImmutableList()
    }

val Post.contentRenders: ImmutableList<PbContentRender>
    get() {
        val renders = content.renders
        val pics = renders.filterIsInstance<PicContentRender>()
        if (pics.isEmpty() || from_forum == null) return renders

        // 多图时以整楼图片列表构建 PhotoViewData,大图浏览可左右翻页(与楼中楼行为一致)
        return renders.map { render ->
            if (render is PicContentRender) {
                render.copy(
                    photoViewData = getPhotoViewData(
                        this,
                        pics,
                        pics.indexOf(render)
                    )
                )
            } else render
        }.toImmutableList()
    }

val User.bawuType: String?
    get() = if (is_bawu == 1) {
        if (bawu_type == "manager") "吧主" else "小吧主"
    } else null

val Post.subPostContents: ImmutableList<AnnotatedString>
    get() = sub_post_list?.sub_post_list?.map { it.getContentText(origin_thread_info?.author?.id) }
        ?.toImmutableList()
        ?: persistentListOf()

val Post.subPosts: ImmutableList<SubPostItemData>
    get() = sub_post_list?.sub_post_list?.map {
        SubPostItemData(
            it.wrapImmutable(),
            it.getContentText(origin_thread_info?.author?.id),
            it.bindSubPostPicPhotoViewData(this)
        )
    }?.toImmutableList() ?: persistentListOf()

/**
 * pb 响应的 post 不下发 from_forum(实测 pb/page 与 pb/floor 均缺失),
 * 而图片的 PhotoViewData 绑定需要 from_forum/tid 作为大图上下文,
 * 这里用同一响应内的吧信息补齐,缺省时原样返回
 */
fun Post.withForumFallback(forum: SimpleForum?): Post =
    if (from_forum == null && forum != null) copy(from_forum = forum) else this

/**
 * 为楼中楼内容中的图片绑定大图浏览数据：
 * 楼中楼预览此前把图片丢弃为空文本，这里保留 PicContentRender
 * 并以父楼层（[post]）上下文构建 PhotoViewData，支持多图翻页
 */
private fun SubPostList.bindSubPostPicPhotoViewData(
    post: Post
): ImmutableList<PbContentRender> {
    val renders = content.renders
    val pics = renders.filterIsInstance<PicContentRender>()
    if (pics.isEmpty()) return renders
    return renders.map { render ->
        if (render is PicContentRender) {
            render.copy(
                // 楼中楼图片直接用自身 URL 浏览,不走 pb 图页(见 getSubPostPhotoViewData 注释)
                photoViewData = getSubPostPhotoViewData(pics, pics.indexOf(render))
            )
        } else render
    }.toImmutableList()
}

@OptIn(ExperimentalTextApi::class)
fun SubPostList.getContentText(threadAuthorId: Long? = null): AnnotatedString {
    val context = App.INSTANCE
    val accentColor = Color(ThemeUtils.getColorByAttr(context, R.attr.colorNewPrimary))

    val userNameString = buildAnnotatedString {
        withAnnotation("user", "${author?.id}") {
            withStyle(
                SpanStyle(
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(
                    StringUtil.getUsernameAnnotatedString(
                        context,
                        author?.name ?: "",
                        author?.nameShow
                    )
                )
            }
            if (author?.id == threadAuthorId) {
                appendInlineContent("Lz")
            }
            append(": ")
        }
    }

    val contentStrings = content.renders.map { it.toAnnotationString() }

    return userNameString + contentStrings.reduce { acc, annotatedString -> acc + annotatedString }
}