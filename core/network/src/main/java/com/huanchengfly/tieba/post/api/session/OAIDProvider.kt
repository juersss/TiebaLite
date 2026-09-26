package com.huanchengfly.tieba.post.api.session

/**
 * OAID（匿名设备标识符）取值接口——api 包此前直接读 `App.Config`。
 *
 * 取值都是启动期由 `App.Config.init` 填好的快照，这里只做只读透出。
 */
interface OAIDProvider {
    val encodedOAID: String

    val statusCode: Int

    val isOAIDSupported: Boolean

    val isTrackLimited: Boolean
}
