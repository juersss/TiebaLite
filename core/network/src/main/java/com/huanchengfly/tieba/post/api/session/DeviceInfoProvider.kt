package com.huanchengfly.tieba.post.api.session

/**
 * 设备/屏幕信息的取值接口——api 包此前直接读 `App.ScreenInfo`。
 *
 * 只暴露协议里真正要用的三个量（贴吧协议字段 `scr_w`/`scr_h`/`scr_dip`）。
 * 这些值由 `BaseActivity` 在窗口建立后写入，取用时机都在 UI 之后。
 */
interface DeviceInfoProvider {
    /** 屏幕密度（协议字段 scr_dip） */
    val density: Float

    /** 物理像素宽（协议字段 scr_w 的原始值） */
    val exactScreenWidth: Int

    /** 物理像素高（协议字段 scr_h 的原始值） */
    val exactScreenHeight: Int

    /** 设备 IMEI（协议字段 iemi 的来源）；取不到时返回空串，与 MobileInfoUtil 一致 */
    val imei: String

    /**
     * 设备级 UUID（`UIDUtil.uUID` 的来源）。首次调用时生成并**落盘到 `app_data`/`uuid`**
     * （落盘名与键与 3b-prep 之前一致），之后每次返回同一个值。
     */
    fun getOrCreateUuid(): String
}
