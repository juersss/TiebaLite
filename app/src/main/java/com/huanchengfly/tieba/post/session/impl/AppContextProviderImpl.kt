package com.huanchengfly.tieba.post.session.impl

import android.content.Context
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.session.AppContextProvider
import javax.inject.Inject

class AppContextProviderImpl @Inject constructor() : AppContextProvider {
    override val appContext: Context get() = App.INSTANCE
}
