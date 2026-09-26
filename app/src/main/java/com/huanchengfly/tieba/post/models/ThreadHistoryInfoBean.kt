package com.huanchengfly.tieba.post.models

import com.huanchengfly.tieba.post.api.models.BaseBean
import kotlinx.serialization.Serializable

@Serializable
data class ThreadHistoryInfoBean(
    val isSeeLz: Boolean = false,
    val pid: String? = null,
    val forumName: String? = null,
    val floor: String? = null,
) : BaseBean()