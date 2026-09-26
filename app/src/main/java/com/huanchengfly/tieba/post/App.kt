package com.huanchengfly.tieba.post

import com.huanchengfly.tieba.post.api.params.ClientUtils
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import com.huanchengfly.tieba.post.api.session.SessionProviders
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.toArgb
import com.github.gzuliyujiang.oaid.DeviceID
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.SketchFactory
import com.github.panpf.sketch.datasource.AssetDataSource
import com.github.panpf.sketch.datasource.BasedFileDataSource
import com.github.panpf.sketch.datasource.ByteArrayDataSource
import com.github.panpf.sketch.datasource.ContentDataSource
import com.github.panpf.sketch.datasource.ResourceDataSource
import com.github.panpf.sketch.decode.DrawableDecodeResult
import com.github.panpf.sketch.decode.DrawableDecoder
import com.github.panpf.sketch.decode.GifAnimatedDrawableDecoder
import com.github.panpf.sketch.decode.GifMovieDrawableDecoder
import com.github.panpf.sketch.decode.HeifAnimatedDrawableDecoder
import com.github.panpf.sketch.decode.WebpAnimatedDrawableDecoder
import com.github.panpf.sketch.fetch.FetchResult
import com.github.panpf.sketch.http.OkHttpStack
import com.github.panpf.sketch.request.PauseLoadWhenScrollingDrawableDecodeInterceptor
import com.github.panpf.sketch.request.internal.RequestContext
import com.huanchengfly.tieba.post.components.ClipBoardLinkDetector
import com.huanchengfly.tieba.post.components.OAIDGetter

import com.huanchengfly.tieba.post.ui.common.theme.compose.dynamicTonalPalette
import com.huanchengfly.tieba.post.ui.common.theme.interfaces.ThemeSwitcher
import com.huanchengfly.tieba.post.ui.common.theme.utils.ThemeUtils
import com.huanchengfly.tieba.post.utils.OpRecordStore
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.AppIconUtil
import com.huanchengfly.tieba.post.utils.BlockManager
import com.huanchengfly.tieba.post.utils.EmoticonManager
import com.huanchengfly.tieba.post.utils.ProcessUtil
import com.huanchengfly.tieba.post.utils.SharedPreferencesUtil
import com.huanchengfly.tieba.post.utils.ThemeUtil
import com.huanchengfly.tieba.post.utils.Util
import com.huanchengfly.tieba.post.core.data.SettingsKeys
import com.huanchengfly.tieba.post.core.data.appPreferences
import com.huanchengfly.tieba.post.utils.applicationMetaData
import com.huanchengfly.tieba.post.utils.packageInfo
import com.huanchengfly.tieba.post.session.SessionManager
import dagger.hilt.android.HiltAndroidApp
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking


@HiltAndroidApp
class App : Application(), SketchFactory {
    private val mActivityList: MutableList<Activity> = mutableListOf()

    /** 会话层（Phase 6 起由 Hilt 注入；`App.onCreate` 里 [AccountUtil.attach] 后全局可用） */
    @Inject
    lateinit var sessionManager: SessionManager

    @RequiresApi(api = 28)
    private fun setWebViewPath(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = getProcessName(context)
            if (applicationContext.packageName != processName) { //判断不等于默认进程名称
                WebView.setDataDirectorySuffix(processName!!)
            }
        }
    }

    private fun getProcessName(context: Context): String? {
        val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (processInfo in manager.runningAppProcesses) {
            if (processInfo.pid == Process.myPid()) {
                return processInfo.processName
            }
        }
        return null
    }

    override fun onCreate() {
        INSTANCE = this
        // 结构大改 Phase 3a：api 层静态上下文（Retrofit 默认参数等）经此入口点取 Provider。
        // 必须早于任何会发请求的初始化——ClientUtils.init 会立刻在后台起 sync 请求
        // （实测 2026-09-17：装晚了直接 IllegalStateException 崩溃在 getScreenWidth）。
        // 这里只存 Context，真正的 Hilt 入口点解析是懒进行的，此时机安全。
        SessionProviders.install(this)
        super.onCreate()
        // 挂账 §三-1 收口（Phase 8）：把"组合期惰性 runBlocking 读 DataStore（theme 链）"
        // 收束为**启动期一次显式预热**。此前进程内第一个读者是谁不确定——若恰好是组合期的
        // 主题链，主线程就要在首帧里同步读盘；现在预热固定发生在这里（Activity/Compose 之前），
        // 之后所有 Settings.value / Settings.state 读都是纯内存。
        // 框架早于 onCreate 的读（getResources 取 fontScale）仍走同一处的冷启兜底阻塞读，语义不变。
        appPreferences.warmUp()
        // 多进程口径（2026-09-26 收口，挂账 §三-16 / §8.7 候选 4）：
        // App.onCreate 在每个进程都跑，而 :oksign 是 manifest 声明的独立进程。
        // client_id/sample_id/baidu_id 的**联网同步与落盘只在主进程做**——DataStore
        // 不支持多进程并发写，第二写者会让主进程的设置丢更新。非主进程仍读内存快照
        // （请求参数要用），只是不 sync、不写盘。判定见 utils/ProcessUtil。
        ClientUtils.init(syncEnabled = ProcessUtil.isMainProcess(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setWebViewPath(this)
        }
        // Phase 6：会话层切 Hilt 注入（AccountUtil 降为转发门面）
        AccountUtil.attach(sessionManager)
        AccountUtil.init(this)
        OpRecordStore.init(this)
        Config.init(this)
        val isSelfBuild = applicationMetaData.getBoolean("is_self_build")
        AppIconUtil.setIcon()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        ThemeUtils.init(ThemeDelegate)
        registerActivityLifecycleCallbacks(ClipBoardLinkDetector)
        registerActivityLifecycleCallbacks(OAIDGetter)
        thread {
            // 与 OpRecordStore.loadAndMerge(R5-F1)同口径:裸线程抛错=启动期崩进程
            runCatching { runBlocking { BlockManager.init() } }
            runCatching { EmoticonManager.init(this@App) }
        }
    }

    //解决魅族 Flyme 系统夜间模式强制反色
    @Keep
    fun mzNightModeUseOf(): Int = 2

    //禁止app字体大小跟随系统字体大小调节
    override fun getResources(): Resources {
        //INSTANCE = this
        val fontScale = appPreferences.fontScale.value
        val resources = super.getResources()
        if (resources.configuration.fontScale != fontScale) {
            val configuration = resources.configuration
            configuration.fontScale = fontScale
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        return resources
    }

    /**
     * 添加Activity
     */
    fun addActivity(activity: Activity) {
        // 判断当前集合中不存在该Activity
        if (!mActivityList.contains(activity)) {
            mActivityList.add(activity) //把当前Activity添加到集合中
        }
    }

    /**
     * 销毁单个Activity
     */
    @JvmOverloads
    fun removeActivity(activity: Activity, finish: Boolean = false) {
        //判断当前集合中存在该Activity
        if (mActivityList.contains(activity)) {
            mActivityList.remove(activity) //从集合中移除
            if (finish) activity.finish() //销毁当前Activity
        }
    }

    /**
     * 销毁所有的Activity
     */
    fun removeAllActivity() {
        //通过循环，把集合中的所有Activity销毁
        for (activity in mActivityList) {
            activity.finish()
        }
    }

    object Config {
        var inited: Boolean = false

        var isOAIDSupported: Boolean = false
        var statusCode: Int = -200
        var oaid: String = ""
        var encodedOAID: String = ""
        var isTrackLimited: Boolean = false
        var userAgent: String? = null
        var appFirstInstallTime: Long = 0L
        var appLastUpdateTime: Long = 0L

        fun init(context: Context) {
            if (!inited) {
                isOAIDSupported = DeviceID.supportedOAID(context)
                if (isOAIDSupported) {
                    DeviceID.getOAID(context, OAIDGetter)
                } else {
                    statusCode = -200
                    isTrackLimited = false
                }
                userAgent = WebSettings.getDefaultUserAgent(context)
                appFirstInstallTime = context.packageInfo.firstInstallTime
                appLastUpdateTime = context.packageInfo.lastUpdateTime
                inited = true
            }
        }
    }

    object ScreenInfo {
        @JvmField
        var EXACT_SCREEN_HEIGHT = 0

        @JvmField
        var EXACT_SCREEN_WIDTH = 0

        @JvmField
        var SCREEN_HEIGHT = 0

        @JvmField
        var SCREEN_WIDTH = 0

        @JvmField
        var DENSITY = 0f
    }

    companion object {
        const val TAG = "App"

        @JvmStatic
        var translucentBackground: Drawable? = null

        private val packageName: String
            get() = INSTANCE.packageName

        @JvmStatic
        lateinit var INSTANCE: App
            private set

        val isInitialized: Boolean
            get() = this::INSTANCE.isInitialized

        val isSystemNight: Boolean
            get() = nightMode == Configuration.UI_MODE_NIGHT_YES

        val isFirstRun: Boolean
            get() = SharedPreferencesUtil.get(SharedPreferencesUtil.SP_APP_DATA)
                .getBoolean("first", true)

        private val nightMode: Int
            get() = INSTANCE.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    object ThemeDelegate : ThemeSwitcher {
        private fun getDefaultColorResId(attrId: Int): Int {
            return when (attrId) {
                R.attr.colorPrimary -> R.color.default_color_primary
                R.attr.colorNewPrimary -> R.color.default_color_primary
                R.attr.colorAccent -> R.color.default_color_accent
                R.attr.colorOnAccent -> R.color.default_color_on_accent
                R.attr.colorToolbar -> R.color.default_color_toolbar
                R.attr.colorToolbarItem -> R.color.default_color_toolbar_item
                R.attr.colorToolbarItemSecondary -> R.color.default_color_toolbar_item_secondary
                R.attr.colorToolbarItemActive -> R.color.default_color_toolbar_item_active
                R.attr.colorToolbarSurface -> R.color.default_color_toolbar_bar
                R.attr.colorOnToolbarSurface -> R.color.default_color_on_toolbar_bar
                R.attr.colorText -> R.color.default_color_text
                R.attr.colorTextSecondary -> R.color.default_color_text_secondary
                R.attr.colorTextOnPrimary -> R.color.default_color_text_on_primary
                R.attr.color_text_disabled -> R.color.default_color_text_disabled
                R.attr.colorBackground -> R.color.default_color_background
                R.attr.colorWindowBackground -> R.color.default_color_window_background
                R.attr.colorChip -> R.color.default_color_chip
                R.attr.colorOnChip -> R.color.default_color_on_chip
                R.attr.colorUnselected -> R.color.default_color_unselected
                R.attr.colorNavBar -> R.color.default_color_nav
                R.attr.colorNavBarSurface -> R.color.default_color_nav_bar_surface
                R.attr.colorOnNavBarSurface -> R.color.default_color_on_nav_bar_surface
                R.attr.colorCard -> R.color.default_color_card
                R.attr.colorFloorCard -> R.color.default_color_floor_card
                R.attr.colorDivider -> R.color.default_color_divider
                R.attr.shadow_color -> R.color.default_color_shadow
                R.attr.colorIndicator -> R.color.default_color_swipe_refresh_view_background
                R.attr.colorPlaceholder -> R.color.default_color_placeholder
                else -> R.color.transparent
            }
        }

        @SuppressLint("DiscouragedApi")
        fun getColorByAttr(context: Context, attrId: Int, theme: String): Int {
            if (!isInitialized) return context.getColorCompat(getDefaultColorResId(attrId))
            val resources = context.resources
            when (attrId) {
                R.attr.colorPrimary -> {
                    if (ThemeUtil.isDynamicTheme(theme) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val dynamicTonalPalette = dynamicTonalPalette(context)
                        return if (ThemeUtil.isNightMode(theme)) {
                            dynamicTonalPalette.primary80.toArgb()
                        } else {
                            dynamicTonalPalette.primary40.toArgb()
                        }
                    } else if (ThemeUtil.THEME_CUSTOM == theme) {
                        val customPrimaryColorStr = context.appPreferences.customPrimaryColor.value
                        return if (customPrimaryColorStr != null) {
                            Color.parseColor(customPrimaryColorStr)
                        } else getColorByAttr(context, attrId, SettingsKeys.THEME_DEFAULT)
                    } else if (ThemeUtil.isTranslucentTheme(theme)) {
                        val primaryColorStr = context.appPreferences.translucentPrimaryColor.value
                        return if (primaryColorStr != null) {
                            Color.parseColor(primaryColorStr)
                        } else getColorByAttr(context, attrId, SettingsKeys.THEME_DEFAULT)
                    }
                    return context.getColorCompat(
                        resources.getIdentifier(
                            "theme_color_primary_$theme",
                            "color",
                            packageName
                        )
                    )
                }

                R.attr.colorNewPrimary -> {
                    return if (ThemeUtil.isDynamicTheme(theme) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val dynamicTonalPalette = dynamicTonalPalette(context)
                        if (ThemeUtil.isNightMode(theme)) {
                            dynamicTonalPalette.primary80.toArgb()
                        } else {
                            dynamicTonalPalette.primary40.toArgb()
                        }
                    } else if (ThemeUtil.isNightMode(theme)) {
                        context.getColorCompat(R.color.theme_color_new_primary_night)
                    } else if (theme == SettingsKeys.THEME_DEFAULT) {
                        context.getColorCompat(
                            R.color.theme_color_new_primary_light
                        )
                    } else {
                        getColorByAttr(context, R.attr.colorPrimary, theme)
                    }
                }

                R.attr.colorAccent -> {
                    return if (ThemeUtil.isDynamicTheme(theme) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val dynamicTonalPalette = dynamicTonalPalette(context)
                        if (ThemeUtil.isNightMode(theme)) {
                            dynamicTonalPalette.secondary80.toArgb()
                        } else {
                            dynamicTonalPalette.secondary40.toArgb()
                        }
                    } else if (ThemeUtil.THEME_CUSTOM == theme || ThemeUtil.isTranslucentTheme(theme)) {
                        getColorByAttr(context, R.attr.colorPrimary, theme)
                    } else {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_accent_$theme",
                                "color",
                                packageName
                            )
                        )
                    }
                }

                R.attr.colorOnAccent -> {
                    return if (ThemeUtil.isNightMode(theme) || ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_on_accent_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(R.color.theme_color_on_accent_light)
                }

                R.attr.colorToolbar -> {
                    return if (ThemeUtil.isNightMode(theme) || ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_toolbar_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else {
                        val isPrimaryColor = context.appPreferences.toolbarPrimaryColor.value
                        if (isPrimaryColor) {
                            getColorByAttr(context, R.attr.colorPrimary, theme)
                        } else {
                            context.getColorCompat(R.color.white)
                        }
                    }
                }

                R.attr.colorText -> {
                    return if (ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "color_text_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(if (ThemeUtil.isNightMode(theme)) R.color.color_text_night else R.color.color_text)
                }

                R.attr.color_text_disabled -> {
                    return if (ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "color_text_disabled_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(if (ThemeUtil.isNightMode(theme)) R.color.color_text_disabled_night else R.color.color_text_disabled)
                }

                R.attr.colorTextSecondary -> {
                    return if (ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "color_text_secondary_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(if (ThemeUtil.isNightMode(theme)) R.color.color_text_secondary_night else R.color.color_text_secondary)
                }

                R.attr.colorTextOnPrimary -> {
                    return if (ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(R.color.white)
                    } else getColorByAttr(context, R.attr.colorBackground, theme)
                }

                R.attr.colorBackground -> {
                    if (ThemeUtil.isTranslucentTheme(theme)) {
                        return context.getColorCompat(R.color.transparent)
                    }
                    return if (ThemeUtil.isNightMode(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_background_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(R.color.theme_color_background_light)
                }

                R.attr.colorWindowBackground -> {
                    return if (ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_window_background_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else if (ThemeUtil.isNightMode()) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_background_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else {
                        context.getColorCompat(R.color.theme_color_background_light)
                    }
                }

                R.attr.colorChip -> {
                    return if (ThemeUtil.isNightMode(theme) || ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_chip_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(R.color.theme_color_chip_light)
                }

                R.attr.colorOnChip -> {
                    return if (ThemeUtil.isNightMode(theme) || ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_on_chip_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(R.color.theme_color_on_chip_light)
                }

                R.attr.colorUnselected -> {
                    return context.getColorCompat(
                        if (ThemeUtil.isNightMode(theme)) resources.getIdentifier(
                            "theme_color_unselected_$theme",
                            "color",
                            packageName
                        ) else R.color.theme_color_unselected_day
                    )
                }

                R.attr.colorNavBar -> {
                    if (ThemeUtil.isTranslucentTheme(theme)) {
                        return context.getColorCompat(R.color.transparent)
                    }
                    return if (ThemeUtil.isNightMode(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_nav_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else {
                        context.getColorCompat(R.color.theme_color_nav_light)
                    }
                }

                R.attr.colorFloorCard -> {
                    return if (ThemeUtil.isNightMode(theme) || ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_floor_card_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(R.color.theme_color_floor_card_light)
                }

                R.attr.colorCard -> {
                    return if (ThemeUtil.isNightMode(theme) || ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_card_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(R.color.theme_color_card_light)
                }

                R.attr.colorDivider -> {
                    return if (ThemeUtil.isNightMode(theme) || ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_divider_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(R.color.theme_color_divider_light)
                }

                R.attr.shadow_color -> {
                    return if (ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(R.color.transparent)
                    } else context.getColorCompat(if (ThemeUtil.isNightMode(theme)) R.color.theme_color_shadow_night else R.color.theme_color_shadow_day)
                }

                R.attr.colorToolbarItem -> {
                    if (ThemeUtil.isTranslucentTheme(theme)) {
                        return context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_toolbar_item_$theme",
                                "color",
                                packageName
                            )
                        )
                    }
                    return if (ThemeUtil.isNightMode(theme)) {
                        context.getColorCompat(R.color.theme_color_toolbar_item_night)
                    } else context.getColorCompat(if (ThemeUtil.isStatusBarFontDark()) R.color.theme_color_toolbar_item_light else R.color.theme_color_toolbar_item_dark)
                }

                R.attr.colorToolbarItemActive -> {
                    if (ThemeUtil.isTranslucentTheme(theme)) {
                        return context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_toolbar_item_active_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else if (ThemeUtil.isNightMode(theme)) {
                        return getColorByAttr(context, R.attr.colorAccent, theme)
                    }
                    return context.getColorCompat(if (ThemeUtil.isStatusBarFontDark()) R.color.theme_color_toolbar_item_light else R.color.theme_color_toolbar_item_dark)
                }

                R.attr.colorToolbarItemSecondary -> {
                    return if (
                        ThemeUtil.isNightMode(theme) ||
                        ThemeUtil.isTranslucentTheme(theme)
                    ) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_toolbar_item_secondary_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(if (ThemeUtil.isStatusBarFontDark()) R.color.theme_color_toolbar_item_secondary_white else R.color.theme_color_toolbar_item_secondary_light)
                }

                R.attr.colorIndicator -> {
                    return if (ThemeUtil.isNightMode(theme) || ThemeUtil.isTranslucentTheme(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_indicator_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else context.getColorCompat(R.color.theme_color_indicator_light)
                }

                R.attr.colorToolbarSurface -> {
                    return if (ThemeUtil.isTranslucentTheme(theme) || ThemeUtil.isNightMode(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_toolbar_surface_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else {
                        context.getColorCompat(R.color.theme_color_toolbar_surface_light)
                    }
                }

                R.attr.colorOnToolbarSurface -> {
                    return if (ThemeUtil.isTranslucentTheme(theme) || ThemeUtil.isNightMode(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_on_toolbar_surface_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else {
                        context.getColorCompat(R.color.theme_color_on_toolbar_surface_light)
                    }
                }

                R.attr.colorNavBarSurface -> {
                    return if (ThemeUtil.isNightMode(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_nav_bar_surface_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else {
                        context.getColorCompat(R.color.theme_color_nav_bar_surface_light)
                    }
                }

                R.attr.colorOnNavBarSurface -> {
                    return if (ThemeUtil.isNightMode(theme)) {
                        context.getColorCompat(R.color.theme_color_on_nav_bar_surface_dark)
                    } else {
                        context.getColorCompat(R.color.theme_color_on_nav_bar_surface_light)
                    }
                }

                R.attr.colorPlaceholder -> {
                    return if (ThemeUtil.isTranslucentTheme(theme) || ThemeUtil.isNightMode(theme)) {
                        context.getColorCompat(
                            resources.getIdentifier(
                                "theme_color_placeholder_$theme",
                                "color",
                                packageName
                            )
                        )
                    } else {
                        context.getColorCompat(R.color.theme_color_placeholder_light)
                    }
                }
            }
            return Util.getColorByAttr(context, attrId, R.color.transparent)
        }

        override fun getColorByAttr(context: Context, attrId: Int): Int {
            return when (attrId) {
                R.attr.colorPrimary, R.attr.colorNewPrimary, R.attr.colorAccent -> {
                    getColorByAttr(context, attrId, ThemeUtil.getCurrentTheme(checkDynamic = true))
                }

                else -> getColorByAttr(context, attrId, ThemeUtil.getCurrentTheme())
            }
        }

        override fun getColorById(context: Context, colorId: Int): Int {
//            if (!isInitialized) {
//                return context.getColorCompat(colorId)
//            }
            when (colorId) {
                R.color.default_color_primary -> return getColorByAttr(context, R.attr.colorPrimary)
                R.color.default_color_accent -> return getColorByAttr(context, R.attr.colorAccent)
                R.color.default_color_on_accent -> return getColorByAttr(
                    context,
                    R.attr.colorOnAccent
                )

                R.color.default_color_chip -> return getColorByAttr(
                    context,
                    R.attr.colorChip
                )

                R.color.default_color_background -> return getColorByAttr(
                    context,
                    R.attr.colorBackground
                )

                R.color.default_color_window_background -> return getColorByAttr(
                    context,
                    R.attr.colorWindowBackground
                )

                R.color.default_color_toolbar -> return getColorByAttr(context, R.attr.colorToolbar)
                R.color.default_color_toolbar_item -> return getColorByAttr(
                    context,
                    R.attr.colorToolbarItem
                )

                R.color.default_color_toolbar_item_active -> return getColorByAttr(
                    context,
                    R.attr.colorToolbarItemActive
                )

                R.color.default_color_toolbar_item_secondary -> return getColorByAttr(
                    context,
                    R.attr.colorToolbarItemSecondary
                )

                R.color.default_color_toolbar_bar -> return getColorByAttr(
                    context,
                    R.attr.colorToolbarSurface
                )

                R.color.default_color_on_toolbar_bar -> return getColorByAttr(
                    context,
                    R.attr.colorOnToolbarSurface
                )

                R.color.default_color_nav_bar_surface -> return getColorByAttr(
                    context,
                    R.attr.colorNavBarSurface
                )

                R.color.default_color_on_nav_bar_surface -> return getColorByAttr(
                    context,
                    R.attr.colorOnNavBarSurface
                )

                R.color.default_color_card -> return getColorByAttr(context, R.attr.colorCard)
                R.color.default_color_floor_card -> return getColorByAttr(
                    context,
                    R.attr.colorFloorCard
                )

                R.color.default_color_nav -> return getColorByAttr(context, R.attr.colorNavBar)
                R.color.default_color_shadow -> return getColorByAttr(context, R.attr.shadow_color)
                R.color.default_color_unselected -> return getColorByAttr(
                    context,
                    R.attr.colorUnselected
                )

                R.color.default_color_text -> return getColorByAttr(context, R.attr.colorText)
                R.color.default_color_text_on_primary -> return getColorByAttr(
                    context,
                    R.attr.colorTextOnPrimary
                )

                R.color.default_color_text_secondary -> return getColorByAttr(
                    context,
                    R.attr.colorTextSecondary
                )

                R.color.default_color_text_disabled -> return getColorByAttr(
                    context,
                    R.attr.color_text_disabled
                )

                R.color.default_color_divider -> return getColorByAttr(context, R.attr.colorDivider)
                R.color.default_color_swipe_refresh_view_background -> return getColorByAttr(
                    context,
                    R.attr.colorIndicator
                )
            }
            return context.getColorCompat(colorId)
        }
    }

    @SuppressLint("NewApi")
    override fun createSketch(): Sketch = Sketch.Builder(this).apply {
        httpStack(OkHttpStack.Builder().apply {
            userAgent(System.getProperty("http.agent"))
        }.build())
        components {
            addDrawableDecodeInterceptor(PauseLoadWhenScrollingDrawableDecodeInterceptor())

            val gifAnimatedFactory = GifAnimatedDrawableDecoder.Factory()
            val webpAnimatedFactory = WebpAnimatedDrawableDecoder.Factory()
            val heifAnimatedFactory = HeifAnimatedDrawableDecoder.Factory()
            //兼容单帧动图
            fun wrapDecoder(factory: DrawableDecoder.Factory) = object : DrawableDecoder.Factory {
                override fun create(
                    sketch: Sketch,
                    requestContext: RequestContext,
                    fetchResult: FetchResult
                ): DrawableDecoder? {
                    val animatedMimeTypes = setOf("image/gif", "image/webp", "image/heif")
                    if (fetchResult.mimeType !in animatedMimeTypes) return factory.create(
                        sketch,
                        requestContext,
                        fetchResult
                    )

                    val dataSource = fetchResult.dataSource

                    val isActuallyAnimated = try {
                        val source = when (dataSource) {
                            is AssetDataSource -> ImageDecoder.createSource(
                                sketch.context.assets,
                                dataSource.assetFileName
                            )

                            is ResourceDataSource -> ImageDecoder.createSource(
                                dataSource.resources,
                                dataSource.resId
                            )

                            is ContentDataSource -> ImageDecoder.createSource(
                                sketch.context.contentResolver,
                                dataSource.contentUri
                            )

                            is ByteArrayDataSource -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    ImageDecoder.createSource(dataSource.data)
                                } else {
                                    ImageDecoder.createSource(ByteBuffer.wrap(dataSource.data))
                                }
                            }

                            is BasedFileDataSource -> ImageDecoder.createSource(dataSource.getFile())

                            else -> null
                        }

                        if (source != null) {
                            var animated = false
                            ImageDecoder.decodeDrawable(source) { _, info, _ ->
                                animated = info.isAnimated
                            }
                            animated
                        } else {
                            false
                        }
                    } catch (e: Throwable) {
                        false
                    }

                    if (!isActuallyAnimated) {
                        return null
                    }

                    return factory.create(sketch, requestContext, fetchResult)
                }
            }

            addDrawableDecoder(wrapDecoder(gifAnimatedFactory))
            addDrawableDecoder(wrapDecoder(webpAnimatedFactory))
            addDrawableDecoder(wrapDecoder(heifAnimatedFactory))

            addDrawableDecoder(GifMovieDrawableDecoder.Factory())
        }
    }.build()
}