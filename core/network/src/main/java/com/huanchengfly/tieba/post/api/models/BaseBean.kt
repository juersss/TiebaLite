package com.huanchengfly.tieba.post.api.models

import com.huanchengfly.tieba.post.core.common.toJson

open class BaseBean {
    override fun toString(): String {
        return toJson()
    }
}