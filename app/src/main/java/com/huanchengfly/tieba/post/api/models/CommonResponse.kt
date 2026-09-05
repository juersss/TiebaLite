package com.huanchengfly.tieba.post.api.models

import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.huanchengfly.tieba.post.api.adapters.ErrorMsgAdapter
import com.huanchengfly.tieba.post.models.BaseBean

data class CommonResponse(
    @SerializedName("error_code", alternate = ["errno", "no"])
    val errorCode: Int = 0,
    @JsonAdapter(ErrorMsgAdapter::class)
    @SerializedName("error_msg", alternate = ["errmsg", "error"])
    val errorMsg: String = ""
) : BaseBean() {
    companion object {
        /**
         * error_code 字段无法解析为 Int 时的兜底值("解析失败"的占位)。
         * 注意与 AgreeParams.RATE_LIMIT_ERROR_CODE(-1001) 只差一位数字,两者毫无关系:
         * 后者是客户端限流的哨兵值,会参与 UI 的事件分派判断,不能混用。
         */
        const val ERROR_CODE_UNKNOWN = -1
    }
}
