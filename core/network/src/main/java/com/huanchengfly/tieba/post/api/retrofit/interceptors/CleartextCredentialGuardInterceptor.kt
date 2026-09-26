package com.huanchengfly.tieba.post.api.retrofit.interceptors

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 明文凭据传输守卫(外部审查-1):任何走明文 HTTP 的请求一旦携带账号凭据
 * (BDUSS/bdusstoken/stoken)直接抛错拦截,不再发出——即便服务端随后重定向,
 * 首个明文请求里的凭据也已被链路观察者看到,事后无法撤回。
 *
 * 三个 c.tieba 客户端已于外部审查-1 全部迁移 HTTPS,本拦截器在正常路径下
 * 永不触发;它是防御性断言,防的是未来"新增/回退一个 http baseUrl"这类回归。
 * 放在拦截器链最末尾(网络之前),才能看到 CommonParamInterceptor/CommonHeaderInterceptor
 * 注入 BDUSS/Cookie 之后的最终请求形态。
 */
class CleartextCredentialGuardInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.scheme.equals("http", ignoreCase = true) && request.carriesCredentials()) {
            throw IOException(
                "已拦截明文传输账号凭据的请求:${request.url.redactCredential()}。" +
                    "凭据请求必须走 HTTPS;若为接口迁移回归请修正 baseUrl。"
            )
        }
        return chain.proceed(request)
    }

    private fun Request.carriesCredentials(): Boolean {
        val sensitive = setOf("bduss", "bdusstoken", "stoken")
        if (url.queryParameterNames.any { it.lowercase() in sensitive }) return true
        if (headers["Cookie"]?.contains("BDUSS", ignoreCase = true) == true) return true
        return body.containsCredentialField(sensitive)
    }

    private fun RequestBody?.containsCredentialField(sensitive: Set<String>): Boolean {
        return when (this) {
            is FormBody -> (0 until size).any { name(it).lowercase() in sensitive }
            is MultipartBody -> parts.any { part ->
                // 表单字段名位于 Content-Disposition 的 name="..." 中,不是独立 header 名
                part.headers?.values("Content-Disposition")?.any { disposition ->
                    val value = disposition.lowercase()
                    sensitive.any { value.contains("name=\"$it\"") || value.contains("name=$it;") }
                } == true
            }

            else -> false
        }
    }

    /** 异常信息里不输出 query(可能携带凭据原值),仅保留 scheme/host/path */
    private fun HttpUrl.redactCredential(): String =
        "$scheme://$host$encodedPath (query 已脱敏)"
}
