package com.huanchengfly.tieba.post.api.models

import com.huanchengfly.tieba.post.api.session.SessionProviders
import com.google.gson.annotations.SerializedName

data class OAID(
    @SerializedName("v")
    val encodedOAID: String = SessionProviders.oaid.encodedOAID,
    @SerializedName("sc")
    val statusCode: Int = SessionProviders.oaid.statusCode,
    @SerializedName("sup")
    val support: Int = if (SessionProviders.oaid.isOAIDSupported) 1 else 0,
    val isTrackLimited: Int = if (SessionProviders.oaid.isTrackLimited) 1 else 0
)
