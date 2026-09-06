package com.huanchengfly.tieba.post.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TopicDetailBean(
    @SerialName("no")
    val errorCode: Int,
    @SerialName("error")
    val errorMsg: String,
    val data: TopicDetailDataBean,
)

@Serializable
data class TopicDetailDataBean(
    @SerialName("topic_info")
    val topicInfo: TopicInfoBean,
    val user: UserBean,
    val tbs: String,
    @SerialName("relate_forum")
    val relateForum: List<RelateForumBean>,
    /**
     * 置顶/特殊话题内容(外部审查-8)。真实报文形状未经验证(话题详情为
     * 未完成的预览功能),此前按 List<SpecialTopicBean> 强类型建模会因形状不符导致
     * 整个话题页解析失败,故被注释。改用 JsonElement 无损捕获:解析永不失败,
     * 展示层经 TopicDetailPinned 防御性提取,提取失败安全降级为空(与现状一致)。
     */
    @SerialName("special_topic")
    val specialTopic: JsonElement? = null,
    @SerialName("relate_thread")
    val relateThread: RelateThreadBean,
    @SerialName("has_more")
    val hasMore: Boolean,
    @SerialName("wreq")
    val wreq: Wreq,
)

@Serializable
data class RelateThreadBean(
    @SerialName("thread_list")
    val threadList: List<ThreadBean>,
)

@Serializable
data class Wreq(
    @SerialName("pn")
    val page: Int,
    @SerialName("rn")
    val pageSize: Int,
)

@Serializable
data class ThreadBean(
    @SerialName("feed_id")
    val feedId: Long,
    val source: Int,
    @SerialName("thread_info")
    val threadInfo: ThreadInfoBean,
    @SerialName("user_agree")
    val userAgree: Int,
)

@Serializable
data class TopicInfoBean(
    @SerialName("topic_id")
    val topicId: String,
    @SerialName("topic_name")
    val topicName: String,
    val candle: String,
    @SerialName("topic_desc")
    val topicDesc: String,
    @SerialName("discuss_num")
    val discussNum: Long,
    @SerialName("topic_image")
    val topicImage: String,
    @SerialName("share_title")
    val shareTitle: String,
    @SerialName("share_pic")
    val sharePic: String,
    @SerialName("is_video_topic")
    val isVideoTopic: Int,
    @SerialName("idx_num")
    val idxNum: Int,
)

@Serializable
data class UserBean(
    @SerialName("is_login")
    val isLogin: Boolean,
    val id: Long,
    val uid: Long,
    val name: String,
    @SerialName("name_show")
    val nameShow: String,
    @SerialName("portrait")
    val portraitUrl: String,
)

@Serializable
data class RelateForumBean(
    @SerialName("forum_id")
    val forumId: Long,
    @SerialName("forum_name")
    val forumName: String,
    val avatar: String,
    val desc: String,
    @SerialName("member_num")
    val memberNum: Long,
    @SerialName("thread_num")
    val threadNum: Long,
    @SerialName("post_num")
    val postNum: Long,
)

@Serializable
data class SpecialTopicBean(
    val title: String,
    @SerialName("thread_list")
    val threadList: List<ThreadInfoBean>,
)

@Serializable
data class ThreadInfoBean(
    val id: Long,
    @SerialName("feed_id")
    val feedId: Long,
    val avatar: String,
    val title: String? = "",
    @SerialName("tid")
    val threadId: Long,
    @SerialName("forum_id")
    val forumId: Long,
    @SerialName("forum_name")
    val forumName: String,
    @SerialName("create_time")
    val createTime: Long,
    @SerialName("last_time")
    val lastTime: String,
    @SerialName("last_time_int")
    val lastTimeInt: Long,
    @SerialName("abstract")
    val abstractText: String,
    val media: List<MediaBean>,
    @SerialName("media_num")
    val mediaNum: MediaNumBean,
    @SerialName("agree_num")
    val agreeNum: Int,
    @SerialName("reply_num")
    val replyNum: Int,
    @SerialName("share_num")
    val shareNum: Long,
    @SerialName("user_id")
    val userId: Long,
    @SerialName("first_post_id")
    val firstPostId: Long,
    @SerialName("user_agree")
    val userAgree: Int,
    @SerialName("author")
    val author: Author,
    val agree: Agree,
)



@Serializable
data class Agree(
    @SerialName("agree_num")
    val agreeNum: Int,

    @SerialName("agree_type")
    val agreeType: Int,

    @SerialName("has_agree")
    val hasAgree: Int,
)

@Serializable
data class Author(
    val name: String?,
    val id: Long,
    @SerialName("show_nickname")
    val showNickName: String,
    @SerialName("name_show")
    val nameShow: String,
    val portrait: String,
)

@Serializable
data class MediaNumBean(
    val pic: Int,
)

@Serializable
data class MediaBean(
    val type: String,
    val width: String,
    val height: String,
    @SerialName("small_pic")
    val smallPic: String,
    @SerialName("big_pic")
    val bigPic: String,
    @SerialName("water_pic")
    val waterPic: String,
    @SerialName("is_long_pic")
    val isLongPic: Int,
    @SerialName("bsize")
    val bSize: String,
)