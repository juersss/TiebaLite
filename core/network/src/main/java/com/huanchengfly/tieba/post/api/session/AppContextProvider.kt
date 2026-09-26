package com.huanchengfly.tieba.post.api.session

import android.content.Context

/**
 * 应用 Context 的取值接口——api 包此前直接取 `App.INSTANCE`。
 *
 * 只暴露 Context（框架类型），不暴露 `App` 这个 app 类，
 * 否则 api（将来的 `core:network`）会被钉死在 app 模块上。
 */
interface AppContextProvider {
    val appContext: Context
}
