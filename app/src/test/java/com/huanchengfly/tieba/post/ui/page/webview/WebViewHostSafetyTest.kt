package com.huanchengfly.tieba.post.ui.page.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：§3.2 WebView 凭据注入域名判定（阻断级安全缺陷）。
 *
 * `isTiebaHost` / `isInternalHost` 是纯函数（仅 String 操作，无 Android 依赖），
 * 是 `injectCookies` / `onPageStarted` 中 `isTrustedUrl` 守卫的核心判定。
 *
 * 旧实现用 `host.contains("tieba.baidu.com")`，导致 `tieba.baidu.com.attacker.tld`
 * 被误判为内部域 → 任意外部站可拿到 BDUSS（账号接管）。修复改为精确匹配 + 后缀匹配。
 *
 * 证伪验证：临时把 `isTiebaHost` 改回 `contains` 实现后，
 * `bypassViaAttackerTldIsRejected` 与 `prefixLookalikeSubdomainIsRejected` 两例必 FAILED，
 * 其余仍通过；随后还原并复验全绿（见报告 §4.1.5）。
 */
class WebViewHostSafetyTest {

    // ---- isTiebaHost：内部贴吧域 ----

    @Test fun tiebaExactHostIsInternal() {
        assertTrue(isTiebaHost("tieba.baidu.com"))
    }

    @Test fun wappExactHostIsInternal() {
        assertTrue(isTiebaHost("wapp.baidu.com"))
    }

    @Test fun tiebacExactHostIsInternal() {
        assertTrue(isTiebaHost("tiebac.baidu.com"))
    }

    @Test fun subdomainOfTiebaIsInternal() {
        assertTrue(isTiebaHost("a.tieba.baidu.com"))
    }

    @Test fun nestedSubdomainOfTiebaIsInternal() {
        assertTrue(isTiebaHost("a.b.tieba.baidu.com"))
    }

    // ---- §3.2 关键回归：绕过攻击必须被拒绝 ----

    @Test fun bypassViaAttackerTldIsRejected() {
        // 旧 contains 实现会误判为内部域 → 账号接管；精确/后缀匹配下必须为 false
        assertFalse(isTiebaHost("tieba.baidu.com.attacker.tld"))
    }

    @Test fun prefixLookalikeSubdomainIsRejected() {
        // evil-tieba.baidu.com 含子串 tieba.baidu.com，但后缀匹配要求前导点，必须拒绝
        assertFalse(isTiebaHost("evil-tieba.baidu.com"))
    }

    @Test fun unrelatedBaiduHostIsRejected() {
        assertFalse(isTiebaHost("www.baidu.com"))
        assertFalse(isTiebaHost("baidu.com"))
    }

    @Test fun unrelatedExternalHostIsRejected() {
        assertFalse(isTiebaHost("example.com"))
    }

    @Test fun emptyHostIsRejected() {
        assertFalse(isTiebaHost(""))
    }

    // ---- isInternalHost：在 isTiebaHost 基础上扩展其它内部域 ----

    @Test fun wappassExactHostIsInternal() {
        assertTrue(isInternalHost("wappass.baidu.com"))
    }

    @Test fun wappassSubdomainIsInternal() {
        assertTrue(isInternalHost("login.wappass.baidu.com"))
    }

    @Test fun wappassBypassViaAttackerTldIsRejected() {
        assertFalse(isInternalHost("wappass.baidu.com.attacker.tld"))
    }

    @Test fun ufosdkHostIsInternal() {
        assertTrue(isInternalHost("ufosdk.baidu.com"))
    }

    @Test fun helpHostIsInternal() {
        assertTrue(isInternalHost("m.help.baidu.com"))
    }

    @Test fun helpSubdomainIsInternal() {
        assertTrue(isInternalHost("x.m.help.baidu.com"))
    }

    @Test fun fullyExternalHostIsRejected() {
        assertFalse(isInternalHost("evil.com"))
    }
}
