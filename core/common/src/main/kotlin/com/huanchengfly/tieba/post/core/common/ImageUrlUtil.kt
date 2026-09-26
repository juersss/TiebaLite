package com.huanchengfly.tieba.post.core.common

/**
 * 大图浏览(PhotoViewActivity)用到的图片 URL 解析——纯函数,无 Android 依赖,便于 JVM 单测。
 *
 * ## 为什么需要 [upgradeImageUrlToHttps]
 *
 * 2026-09-14 实机定位:图页接口 `/c/f/pb/picpage` 给 `img.original.original_src` 下发的是
 * `http://tiebapic.baidu.com/...`,而本应用全局禁明文
 * (`res/xml/network_security_config.xml` 的 base-config 为 false,白名单只有
 * `tb.himg.baidu.com` / `static.tieba.baidu.com` 两个 TLS 证书不覆盖自身域名的静态资源域)。
 * Sketch 的请求随即被网络策略当场拒绝:
 *
 * ```
 * E Sketch: Request failed. CLEARTEXT communication to tiebapic.baidu.com
 *           not permitted by network security policy.
 * java.net.UnknownServiceException: CLEARTEXT communication ... not permitted ...
 * ```
 *
 * 表现是**大图页整屏全黑**:既没有进度圈也没有任何网络请求(请求在连之前就失败),
 * 下载/分享按钮同源同因,静默无响应——因为它们的 URL 取自同一个字段。
 *
 * 修复口径:**把 http 图片地址升级为 https**,而不是放宽明文白名单。
 * 依据:同一 CDN 域名 HTTPS 可用——对同一 URL 的 https 版本实测返回 200 且字节与
 * 服务端下发的 `origin_size` 完全一致。保持"凭据/内容全量 HTTPS、明文白名单只留给
 * 证书不覆盖自身域名的静态资源"这一既有安全口径不变。
 *
 * ★ 白名单内的域名 HTTPS 不可用(证书不覆盖自身域名),**不得升级**,否则会把
 * 本来能显示的图弄坏。改动白名单时务必同步 [CLEARTEXT_ALLOWED_IMAGE_HOSTS]。
 */

/** 与 `res/xml/network_security_config.xml` 的 domain-config 保持一致:只能走明文 */
private val CLEARTEXT_ALLOWED_IMAGE_HOSTS = setOf(
    "tb.himg.baidu.com",
    "static.tieba.baidu.com",
)

private const val HTTP_PREFIX = "http://"
private const val HTTPS_PREFIX = "https://"

/**
 * 从 URL 中取小写主机名;解析不出时返回空串。
 * 只做纯字符串处理(不依赖 [java.net.URL]):图片地址里可能带未转义的查询串/端口。
 */
private fun imageUrlHost(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return ""
    val rest = url.substring(schemeEnd + 3)
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == ':' }
    return (if (end >= 0) rest.substring(0, end) else rest).lowercase()
}

/**
 * 把 `http://` 图片地址升级为 `https://`。
 * 已是 https、非 http 协议、解析不出主机、或主机在明文白名单内时原样返回。
 */
fun upgradeImageUrlToHttps(url: String): String {
    if (!url.startsWith(HTTP_PREFIX, ignoreCase = true)) return url
    val host = imageUrlHost(url)
    if (host.isEmpty() || host in CLEARTEXT_ALLOWED_IMAGE_HOSTS) return url
    return HTTPS_PREFIX + url.substring(HTTP_PREFIX.length)
}

/** 取第一个非空白(升级为 https 后)的候选 URL;全为空返回 null */
fun firstNotBlankImageUrl(vararg urls: String?): String? =
    urls.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
        ?.let(::upgradeImageUrlToHttps)

/**
 * 大图页**展示**用 URL,取第一个非空者:
 * 1. `displayUrl` —— 楼中楼内联 URL(与缩略图同源,命中缓存,刻意优先);
 * 2. `originUrl` —— 原图;
 * 3. `url`        —— 图页大图(picpage 的 `big_cdn_src`)。
 *
 * 任一级命中后统一走 [upgradeImageUrlToHttps]。
 */
fun resolvePhotoViewDisplayUrl(displayUrl: String?, originUrl: String, url: String?): String =
    firstNotBlankImageUrl(displayUrl, originUrl, url) ?: originUrl

/**
 * 大图页**下载/分享**用 URL:优先原图 `originUrl`,为空时按展示顺序回落
 * (服务端并不保证下发原图字段,回落可避免下载按钮静默失效)。
 */
fun resolvePhotoViewDownloadUrl(displayUrl: String?, originUrl: String, url: String?): String =
    firstNotBlankImageUrl(originUrl, displayUrl, url) ?: originUrl
