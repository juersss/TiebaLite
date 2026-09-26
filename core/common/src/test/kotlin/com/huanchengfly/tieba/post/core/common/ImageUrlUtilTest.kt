package com.huanchengfly.tieba.post.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 大图页图片 URL 解析的纯函数单测。
 *
 * 回归背景(2026-09-14 实机定位):图页接口 `/c/f/pb/picpage` 的 `original_src` 是
 * `http://tiebapic.baidu.com/...`,被"禁明文"的网络策略当场拒绝
 * (`UnknownServiceException: CLEARTEXT communication ... not permitted`),
 * 大图页整屏全黑(无进度、无请求)。修复口径是升 https,而非放宽明文白名单。
 *
 * 误判代价不对称:该升不升 = 图打不开(用户可见故障);不该升乱升会把
 * 只能明文、证书不覆盖自身域名的静态资源域(tb.himg / static.tieba)弄坏
 * ——因此白名单域名必须原样保留,单独锁用例。
 */
class ImageUrlUtilTest {

    /** 实机日志里的真实地址(bba1cd11… 为 picpage 返回的原图) */
    private val realPicpageUrl =
        "http://tiebapic.baidu.com/forum/pic/item/bba1cd11728b47109fd793c985cec3fdfc03233e.jpg" +
                "?tbpicau=2026-09-25-05_678d5bb97ea6978c9240e7b092750866"

    @Test
    fun upgradesCleartextImageUrlToHttps() {
        assertEquals(
            "https://tiebapic.baidu.com/forum/pic/item/bba1cd11728b47109fd793c985cec3fdfc03233e.jpg" +
                    "?tbpicau=2026-09-25-05_678d5bb97ea6978c9240e7b092750866",
            upgradeImageUrlToHttps(realPicpageUrl)
        )
    }

    /**
     * 视频播放地址走**同一条**升级（2026-09-30 实机定位）：接口把播放地址下发成
     * `http://tb-video.bdstatic.com/....mp4`，禁明文下点播放直接失败（release 包连日志都被
     * proguard 裁掉，表现为静默黑屏）。修复复用本函数，故这里把该主机钉住。
     */
    @Test
    fun upgradesCleartextVideoUrlToHttps() {
        assertEquals(
            "https://tb-video.bdstatic.com/2_1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e_1.mp4?a=1&b=2",
            upgradeImageUrlToHttps("http://tb-video.bdstatic.com/2_1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e_1.mp4?a=1&b=2")
        )
    }

    @Test
    fun keepsHttpsUrlUntouched() {
        val url = "https://tiebapic.baidu.com/forum/pic/item/a.jpg?tbpicau=x"
        assertEquals(url, upgradeImageUrlToHttps(url))
    }

    @Test
    fun keepsCleartextOnlyHostsUntouched() {
        // 证书不覆盖自身域名,HTTPS 不可用——升级即把头像/表情弄坏
        val avatar = "http://tb.himg.baidu.com/sys/portrait/item/abc"
        val emoticon = "http://static.tieba.baidu.com/tb/editor/images/client/a.png"
        assertEquals(avatar, upgradeImageUrlToHttps(avatar))
        assertEquals(emoticon, upgradeImageUrlToHttps(emoticon))
    }

    @Test
    fun upgradesUppercaseSchemeAndIgnoresHostCaseWhenMatchingWhitelist() {
        // 只替换协议头,主机部分原样保留(改动面最小,不额外改写服务端下发的地址)
        assertEquals(
            "https://Tiebapic.Baidu.Com/a.jpg",
            upgradeImageUrlToHttps("HTTP://Tiebapic.Baidu.Com/a.jpg")
        )
        // 白名单匹配必须大小写不敏感(畸形大小写同样不得升级)
        assertEquals(
            "http://TB.Himg.Baidu.Com/sys/portrait/item/abc",
            upgradeImageUrlToHttps("http://TB.Himg.Baidu.Com/sys/portrait/item/abc")
        )
    }

    @Test
    fun hostParsingHandlesPortQueryAndMissingPath() {
        assertEquals("https://tiebapic.baidu.com:8080/a.jpg", upgradeImageUrlToHttps("http://tiebapic.baidu.com:8080/a.jpg"))
        assertEquals("https://tiebapic.baidu.com?a=1", upgradeImageUrlToHttps("http://tiebapic.baidu.com?a=1"))
        assertEquals("https://tiebapic.baidu.com#f", upgradeImageUrlToHttps("http://tiebapic.baidu.com#f"))
        assertEquals("https://tiebapic.baidu.com", upgradeImageUrlToHttps("http://tiebapic.baidu.com"))
        // 主机为空:原样返回,不制造 "https:///path"
        assertEquals("http:///path", upgradeImageUrlToHttps("http:///path"))
    }

    @Test
    fun keepsNonHttpSchemesAndRelativePathsUntouched() {
        assertEquals("content://media/external/images/1", upgradeImageUrlToHttps("content://media/external/images/1"))
        assertEquals("file:///data/a.jpg", upgradeImageUrlToHttps("file:///data/a.jpg"))
        assertEquals("//tiebapic.baidu.com/a.jpg", upgradeImageUrlToHttps("//tiebapic.baidu.com/a.jpg"))
        assertEquals("", upgradeImageUrlToHttps(""))
    }

    @Test
    fun displayUrlPrefersInlineUrlThenOriginThenPageUrl() {
        // 楼中楼:内联 URL 刻意优先(与缩略图同源、命中缓存)
        assertEquals(
            "https://inline/a.jpg",
            resolvePhotoViewDisplayUrl("https://inline/a.jpg", "https://origin/a.jpg", "https://page/a.jpg")
        )
        // 普通帖子:无内联 URL,用原图
        assertEquals(
            "https://origin/a.jpg",
            resolvePhotoViewDisplayUrl(null, "https://origin/a.jpg", "https://page/a.jpg")
        )
        // 原图为空(服务端常见):回落到图页大图
        assertEquals(
            "https://page/a.jpg",
            resolvePhotoViewDisplayUrl(null, "", "https://page/a.jpg")
        )
        // 空白内联 URL 视为不存在,不得吞掉后续候选
        assertEquals(
            "https://origin/a.jpg",
            resolvePhotoViewDisplayUrl("   ", "https://origin/a.jpg", null)
        )
    }

    @Test
    fun displayUrlUpgradesSchemeOfWhicheverCandidateWins() {
        assertEquals(
            "https://tiebapic.baidu.com/forum/pic/item/bba1cd11.jpg",
            resolvePhotoViewDisplayUrl(null, "http://tiebapic.baidu.com/forum/pic/item/bba1cd11.jpg", null)
        )
    }

    @Test
    fun displayUrlFallsBackToBlankWhenNothingUsable() {
        // 全空时不抛异常,返回原 originUrl(交由 Sketch 走 uriEmpty 分支,不崩)
        assertEquals("", resolvePhotoViewDisplayUrl(null, "", null))
        assertEquals("", resolvePhotoViewDisplayUrl(null, "", ""))
    }

    @Test
    fun downloadUrlPrefersOriginThenFallsBack() {
        // 有原图:下载/分享用原图保质量,且同样升 https
        assertEquals(
            "https://tiebapic.baidu.com/forum/pic/item/a.jpg",
            resolvePhotoViewDownloadUrl("https://inline/a.jpg", "http://tiebapic.baidu.com/forum/pic/item/a.jpg", null)
        )
        // 原图缺失:回落内联 URL,避免下载按钮静默失效
        assertEquals(
            "https://inline/a.jpg",
            resolvePhotoViewDownloadUrl("https://inline/a.jpg", "", "https://page/a.jpg")
        )
        // 原图与内联都缺:回落图页大图
        assertEquals(
            "https://page/a.jpg",
            resolvePhotoViewDownloadUrl(null, "  ", "https://page/a.jpg")
        )
    }

    @Test
    fun firstNotBlankImageUrlSkipsBlankAndTrims() {
        assertEquals(null, firstNotBlankImageUrl(null, "", "   "))
        assertEquals("https://a/x.jpg", firstNotBlankImageUrl("", " https://a/x.jpg ", null))
    }
}
