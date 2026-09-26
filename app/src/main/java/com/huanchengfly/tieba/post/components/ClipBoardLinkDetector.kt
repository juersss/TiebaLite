package com.huanchengfly.tieba.post.components

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.MainActivityV2
import com.huanchengfly.tieba.post.activities.BaseActivity
import com.huanchengfly.tieba.post.arch.collectIn
import com.huanchengfly.tieba.post.utils.QuickPreviewUtil
import com.huanchengfly.tieba.post.utils.getClipBoardText
import com.huanchengfly.tieba.post.utils.getClipBoardTimestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.intellij.lang.annotations.RegExp
import java.util.regex.Pattern

open class ClipBoardLink(
    val url: String,
)

class ClipBoardForumLink(
    url: String,
    val forumName: String,
) : ClipBoardLink(url)

class ClipBoardThreadLink(
    url: String,
    val threadId: String,
) : ClipBoardLink(url)

object ClipBoardLinkDetector : Application.ActivityLifecycleCallbacks {
    private val mutablePreviewInfoStateFlow = MutableStateFlow<QuickPreviewUtil.PreviewInfo?>(null)
    val previewInfoStateFlow
        get() = mutablePreviewInfoStateFlow.asStateFlow()

    private var clipBoardHash: String? = null
    private var lastTimestamp: Long = 0L
    private fun updateClipBoardHashCode() {
        clipBoardHash = getClipBoardHash()
    }

    private fun getClipBoardHash(): String {
        return "$clipBoardTimestamp"
    }

    private val clipBoard: String?
        get() {
            val timestamp = System.currentTimeMillis()
            return if (timestamp - lastTimestamp >= 10 * 1000L) {
                lastTimestamp = timestamp
                App.INSTANCE.getClipBoardText()
            } else {
                null
            }
        }

    private val clipBoardTimestamp: Long
        get() = App.INSTANCE.getClipBoardTimestamp()

    private fun isTiebaDomain(host: String?): Boolean {
        return host != null && (host.equals("wapp.baidu.com", ignoreCase = true) ||
                host.equals("tieba.baidu.com", ignoreCase = true) ||
                host.equals("tiebac.baidu.com", ignoreCase = true))
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        activity.window.decorView.post { checkClipBoard(activity) }
    }

    private fun parseLink(url: String): ClipBoardLink? {
        val uri = Uri.parse(url)
        if (!isTiebaDomain(uri.host)) {
            return null
        }
        val path = uri.path
        return when {
            path.isNullOrEmpty() -> null
            // tid 只接受纯数字:预览流按 Long 请求,非数字串会在建流期抛
            // NumberFormatException(在 catch 覆盖之外)落主线程
            path.startsWith("/p/") -> path.substring(3)
                .takeIf { it.toLongOrNull() != null }
                ?.let { ClipBoardThreadLink(url, it) }
            path.equals("/f", ignoreCase = true) || path.equals("/mo/q/m", ignoreCase = true) -> {
                val kw = uri.getQueryParameter("kw")
                val word = uri.getQueryParameter("word")
                val kz = uri.getQueryParameter("kz")

                when {
                    !kw.isNullOrEmpty() -> ClipBoardForumLink(url, kw)
                    !word.isNullOrEmpty() -> ClipBoardForumLink(url, word)
                    !kz.isNullOrEmpty() && kz.toLongOrNull() != null -> ClipBoardThreadLink(url, kz)
                    else -> null
                }
            }

            else -> ClipBoardLink(url)
        }
    }

    override fun onActivityResumed(activity: Activity) {}

    private fun checkClipBoard(activity: Activity) {
        if (activity !is BaseActivity<*>) return
        if (clipBoardHash == getClipBoardHash()) {
            mutablePreviewInfoStateFlow.value = null
            return
        }
        val clipBoardText = clipBoard
        if (clipBoardText != null) {
            // hash 必须在读到非空剪贴板之后再提交:提交在读取之前时,10 秒节流窗内
            // 读到 null 会把本轮消费成"置空",且 hash 已相等导致这条新链接此后
            // 永不再被解析(节流命中时不提交、不清预览,留给下次生命周期回调重试)
            updateClipBoardHashCode()
            @RegExp val regex =
                "((http|https)://)(([a-zA-Z0-9._-]+\\.[a-zA-Z]{2,6})|([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}))(:[0-9]{1,4})*(/[a-zA-Z0-9&%_./-~-]*)?"
            val pattern = Pattern.compile(regex)
            val matcher = pattern.matcher(clipBoardText)
            if (matcher.find()) {
                val url = matcher.group()
                val link = parseLink(url)
                if (link != null) {
                    if (activity is MainActivityV2) {
                        activity.launch {
                            QuickPreviewUtil.getPreviewInfoFlow(
                                activity,
                                link,
                                activity.lifecycle
                            ).collectIn(activity) {
                                mutablePreviewInfoStateFlow.value = it
                            }
                        }
                    }
                }
            } else {
                mutablePreviewInfoStateFlow.value = null
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}