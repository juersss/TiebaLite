package com.huanchengfly.tieba.post.session.impl

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.session.OAIDProvider
import javax.inject.Inject

class AppOAIDProvider @Inject constructor() : OAIDProvider {
    override val encodedOAID: String get() = App.Config.encodedOAID

    override val statusCode: Int get() = App.Config.statusCode

    override val isOAIDSupported: Boolean get() = App.Config.isOAIDSupported

    override val isTrackLimited: Boolean get() = App.Config.isTrackLimited
}
