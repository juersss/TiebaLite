package com.huanchengfly.tieba.post.api.internal

/**
 * 头像 URL 归一化。3b-prep-2（2026-09-17）从 `StringUtil.getAvatarUrl` 逐字搬出
 * （app 侧原方法保留为同名转发）——api 的 [com.huanchengfly.tieba.post.api.adapters.PortraitAdapter]
 * 需要它，而 StringUtil 依赖 android.text/Compose，搬不动。
 */
object AvatarUrl {

    /** 非空 portrait 若不是完整 URL，则补 `tb.himg.baidu.com` 前缀（明文白名单域名） */
    @JvmStatic
    fun of(portrait: String?): String {
        if (portrait.isNullOrEmpty()) return ""
        return if (portrait.startsWith("http://") || portrait.startsWith("https://")) {
            portrait
        } else "http://tb.himg.baidu.com/sys/portrait/item/$portrait"
    }
}
