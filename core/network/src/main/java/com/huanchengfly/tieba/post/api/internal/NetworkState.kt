package com.huanchengfly.tieba.post.api.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.huanchengfly.tieba.post.api.session.SessionProviders

/**
 * 联网状态判定（api 侧专用）。
 *
 * 3b-prep-2（2026-09-17）：app 的 `NetworkUtil.isNetworkConnected` 默认参数引用了 `App.INSTANCE`
 * （未来 core:network 拿不到），这里用 [SessionProviders] 取 Context，判定逻辑与 app 侧同源同口径。
 */
object ApiNetworkState {

    fun isConnected(): Boolean = isConnected(SessionProviders.appContext)

    fun isConnected(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            val activeNetwork = manager.activeNetworkInfo ?: return false
            activeNetwork.isConnected
        } else {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
