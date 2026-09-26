package com.huanchengfly.tieba.post.api.session

import androidx.annotation.StringRes

/**
 * 文案取值接口——api 包此前直接 `App.INSTANCE.getString(...)`。
 *
 * 注意：@StringRes 的 id 属于 app 的 R 类；`core:network` 若要独立编译，
 * 需把这里的 int 换成字符串常量或把文案移到 core 模块。Phase 3b 前先定这个口径。
 */
interface ResourceProvider {
    fun getString(@StringRes resId: Int): String
}
