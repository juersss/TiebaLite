package com.huanchengfly.tieba.post.api.models

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CheckReportBean(
    @SerialName("errno")
    @SerializedName("errno")
    var errorCode: Int?,
    @SerialName("errmsg")
    @SerializedName("errmsg")
    var errorMsg: String?,
    // 可空:gson 对无无参构造的 data class 走 Unsafe 分配,响应缺 data 键时留 null
    // (kotlinx 默认值对 gson 不生效),声明非空会在消费端 NPE
    val data: CheckReportDataBean? = null,
) {
    @Serializable
    data class CheckReportDataBean(
        val url: String = "",
    )
}