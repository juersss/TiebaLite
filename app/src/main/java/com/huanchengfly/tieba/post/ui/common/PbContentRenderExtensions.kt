package com.huanchengfly.tieba.post.ui.common

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.core.network.model.protos.Abstract
import com.huanchengfly.tieba.post.core.network.model.protos.PbContent
import com.huanchengfly.tieba.post.core.network.model.protos.Post
import com.huanchengfly.tieba.post.core.network.model.protos.PostInfoList
import com.huanchengfly.tieba.post.core.network.model.protos.SubPostList
import com.huanchengfly.tieba.post.core.network.model.protos.ThreadInfo
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.ui.common.TextContentRender.Companion.appendText
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

/**
 * protobuf 模型的**渲染侧**扩展：把 pb 内容变成 [PbContentRender] 列表（UI 可直接 Render）。
 *
 * 为什么单独成文件（结构大改 Phase 3b-prep-1，2026-09-17）：
 * 这些扩展依赖 UI（PbContentRender 一族）、主题（ThemeUtils）、图片（ImageUtil/getPhotoViewData）、
 * 吧表情（EmoticonManager）以及 app 的 `R`，**core:network 不可能依赖它们**。原先它们与纯数据扩展
 * 挤在 `api/models/protos/Extensions.kt` 一个文件里，导致整个 api 包无法成模块——现在按归属拆开：
 * 本文件留在 app，纯数据扩展（`hasAgree`/`hasAbstract`/`updateCollectStatus`/`bawuType`/
 * `withForumFallback`/`abstractText(List<Abstract>)`）留在 api 包，api 包因此对 app 零依赖。
 *
 * 命名沿用 [PbContentRender]：渲染层的入口就在这里。
 */

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
                    val sizeParts = it.bsize.split(",")
                    // bsize 是服务端可控字符串:缺段/非数字按 0 降级
                    // (渲染层 aspectRatio 对 0 已有守卫,直接 toInt 会 NFE/越界崩)
                    val width = sizeParts.getOrNull(0)?.toIntOrNull() ?: 0
                    val height = sizeParts.getOrNull(1)?.toIntOrNull() ?: 0
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
                        val sizeParts = it.bsize.split(",")
                        // 与类型 3/20 同口径:bsize 服务端可控,缺段/非数字按 0 降级
                        val width = sizeParts.getOrNull(0)?.toIntOrNull() ?: 0
                        val height = sizeParts.getOrNull(1)?.toIntOrNull() ?: 0
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
                    val sizeParts = it.bsize.split(",")
                    // bsize 是服务端可控字符串:缺段/非数字按 0 降级
                    // (渲染层 aspectRatio 对 0 已有守卫,直接 toInt 会 NFE/越界崩)
                    val width = sizeParts.getOrNull(0)?.toIntOrNull() ?: 0
                    val height = sizeParts.getOrNull(1)?.toIntOrNull() ?: 0
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
            // threadAuthorId 为 null(本楼不带 origin_thread_info)时,楼中楼作者缺失
            // (author 为 null)会凑成 null==null 误挂楼主角标——必须两侧都非空才判同主
            if (threadAuthorId != null && author?.id == threadAuthorId) {
                appendInlineContent("Lz")
            }
            append(": ")
        }
    }

    val contentStrings = content.renders.map { it.toAnnotationString() }

    return userNameString + contentStrings.reduce { acc, annotatedString -> acc + annotatedString }
}
