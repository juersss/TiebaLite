package com.huanchengfly.tieba.post.api.models

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * `/c/s/uploadPicture` 的分块响应（每块一个）。
 *
 * 2026-10-20 修（审查 §二 P3「模型缺默认值 → `MissingFieldException` 覆盖真实错误」）：
 * 1. **键名双写**：Baidu 返回的是 snake_case（`chunk_no` / `pic_info` / `origin_pic` / `pic_url`），
 *    而旧模型只给 `error_code` / `error_msg` 标了 `@SerialName`，其余字段在 kotlinx.serialization
 *    的严格键名下**永远解不出来**（Gson 时代是静默留 null，所以以前看不出来）。现在用
 *    [JsonNames] 同时接受 snake_case 与 camelCase，两种形态都不会再抛错。
 * 2. **全部可缺省**：上传失败时服务端只回 `error_code` / `error_msg`，没有 `chunk_no`——
 *    旧模型把它声明成必填，于是"上传被拒"退化成 `MissingFieldException`，真实错误文案被覆盖。
 *    现在缺字段取默认值，由 [com.huanchengfly.tieba.post.components.ImageUploader] 检查
 *    `errorCode` 决定成功与否。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UploadPictureResultBean(
    @SerialName("error_code")
    @JsonNames("errorCode")
    @SerializedName("error_code")
    val errorCode: String = "",
    @SerialName("error_msg")
    @JsonNames("errorMsg")
    @SerializedName("error_msg")
    val errorMsg: String = "",
    @JsonNames("resource_id")
    val resourceId: String? = null,
    @JsonNames("chunk_no")
    val chunkNo: String = "",
    @JsonNames("pic_id")
    val picId: String? = null,
    @JsonNames("pic_info")
    val picInfo: PicInfo? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PicInfo(
    @JsonNames("origin_pic")
    val originPic: PicInfoItem? = null,
    @JsonNames("big_pic")
    val bigPic: PicInfoItem? = null,
    @JsonNames("small_pic")
    val smallPic: PicInfoItem? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PicInfoItem(
    val width: String? = null,
    val height: String? = null,
    val type: String? = null,
    @JsonNames("pic_url")
    val picUrl: String? = null,
)
