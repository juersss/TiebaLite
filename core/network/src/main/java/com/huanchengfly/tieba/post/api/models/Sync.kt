package com.huanchengfly.tieba.post.api.models

import com.google.gson.annotations.SerializedName

// 字段全部可空+默认值:gson 对无无参构造的 data class 走 Unsafe 分配,
// 响应缺键时字段留 null(非空声明只拦编译期)——消费端此前 `it.wlConfig.sampleId`
// 在启动链裸协程里直接 NPE 崩进程
data class Sync(
    val client: Client? = null,
    @SerializedName("wl_config")
    val wlConfig: WlConfig? = null
) {
    data class Client(
        @SerializedName("client_id")
        val clientId: String? = null
    )

    data class WlConfig(
        @SerializedName("sample_id")
        val sampleId: String? = null
    )
}