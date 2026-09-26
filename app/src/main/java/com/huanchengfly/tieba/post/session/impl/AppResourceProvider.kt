package com.huanchengfly.tieba.post.session.impl

import androidx.annotation.StringRes
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.session.ResourceProvider
import javax.inject.Inject

class AppResourceProvider @Inject constructor() : ResourceProvider {
    override fun getString(@StringRes resId: Int): String = App.INSTANCE.getString(resId)
}
