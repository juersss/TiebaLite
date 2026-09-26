package com.huanchengfly.tieba.post.session.impl

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.session.DeviceInfoProvider
import com.huanchengfly.tieba.post.utils.SharedPreferencesUtil
import com.huanchengfly.tieba.post.api.params.MobileInfoUtil
import javax.inject.Inject

/** [DeviceInfoProvider] 的 app 侧实现：读 [App.ScreenInfo]（由 BaseActivity 写入）。 */
class AppDeviceInfoProvider @Inject constructor() : DeviceInfoProvider {
    override val density: Float get() = App.ScreenInfo.DENSITY

    override val exactScreenWidth: Int get() = App.ScreenInfo.EXACT_SCREEN_WIDTH

    override val exactScreenHeight: Int get() = App.ScreenInfo.EXACT_SCREEN_HEIGHT

    override val imei: String get() = MobileInfoUtil.getIMEI(App.INSTANCE)

    /** 与 3b-prep 前 `UIDUtil.uUID` 的实现逐字等价：读 `app_data` 的 `uuid`，缺则生成后落盘 */
    @Suppress("ApplySharedPref")
    override fun getOrCreateUuid(): String {
        val prefs = SharedPreferencesUtil.get(App.INSTANCE, SharedPreferencesUtil.SP_APP_DATA)
        prefs.getString("uuid", null)?.let { return it }
        val uuid = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("uuid", uuid).apply()
        return uuid
    }
}
