package com.huanchengfly.tieba.post.core.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.huanchengfly.tieba.post.core.common.AppScope
import com.huanchengfly.tieba.post.core.common.JobQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一段**类型化设置**：同时是 [Flow]（订阅最新值）、可 `snapshot()` 取现值、
 * 可 `value` **同步读**（进程内预热缓存）、可 `set` 整体替换、可 `save` 基于旧值变换后
 * 写入（配合 JobQueue 串行化保证"读-改-写"原子链）。
 *
 * `value` 是 Phase 6.5 为"迁移旧同步读点"引入的：旧 `AppPreferencesUtils` 的属性是
 * `ReadWriteProperty`——首次 get 阻塞读 DataStore 预热、之后读内存缓存；`set` 先改缓存
 * 再异步落盘（读后写立即可见）。`value` 与 [DataStoreSettingsRepository] 的缓存 +
 * 乐观写**逐字对齐该语义**，使 171 处旧调用点可 1:1 直译。
 */
@Immutable
abstract class Settings<T>(
    protected val flow: Flow<T>,
    private val syncValue: () -> T,
) : Flow<T> by flow {

    /**
     * 同步读（缓存值）。启动期 [SettingsRepository.warmUp] 之后是**纯内存读**；缓存未预热时
     * 会阻塞预热一次——这一路**必须**保留（挂账 §三-1：`App.getResources()` 早于 `onCreate`
     * 就被框架调用来取 `fontScale`，冷启时读默认值会把用户设过的字号重置，见
     * `SettingsRepositoryTest.syncValueSeesPersistedValueWhenCacheIsCold`）。
     */
    val value: T
        get() = syncValue()

    /**
     * 订阅式读出口（挂账 §三-1"组合期 runBlocking 读 DataStore（theme 链）"的收口）：
     * 热 [StateFlow]，**绝不阻塞、绝不触发 I/O**。初值取进程缓存现值（启动期预热后即落盘值，
     * 故组合期首帧不会退化成默认值），之后随 DataStore 变化下发。组合期读设置一律
     * `collectAsState()` 订阅本流，不再走 [value] 的同步路径。
     */
    abstract val state: StateFlow<T>

    suspend fun snapshot(): T = this.first()

    abstract fun set(new: T)

    abstract fun save(transform: (old: T) -> T)
}

/**
 * 设置仓库接口（Phase 5 建，Phase 6.5 成为**唯一设置实现**）。
 *
 * **口径说明**：采用**扁平逐键**形态（62 个属性一一对应迁移前 `AppPreferencesUtils`
 * 代理层暴露的 62 个设置项），键名全部取自 [SettingsKeys]——键名红线机器可查、无法漂移。
 * Phase 6.5 删除了旧 `AppPreferencesUtils` 单例（其自带的第二套 DataStore 代理逻辑随之
 * 消失），原调用点统一走本接口：读 [Settings.value]（同步缓存）、写 [Settings.set]。
 * 0ranko0P 的"按域拆分模型（ThemeSettings/UISettings/HabitSettings…）"不在本轮引入。
 */
interface SettingsRepository {

    /**
     * 预热进程内同步读缓存（幂等；**唯一允许阻塞的位置**）。
     *
     * `App.onCreate` 调用一次后，进程内所有 [Settings.value] / [Settings.state] 读都是纯内存；
     * 挂账 §三-1（组合期 `runBlocking` 读 DataStore）由此收口——组合期不再是进程内第一个读者。
     * 框架早于 `onCreate` 的读（`App.getResources()` 取 `fontScale`）仍会走 [Settings.value]
     * 的冷启兜底阻塞读，语义不变。
     */
    fun warmUp()

    val userLikeLastRequestUnix: Settings<Long>
    val ignoreBatteryOptimizationsDialog: Settings<Boolean>
    val appIcon: Settings<String?>
    val useThemedIcon: Settings<Boolean>
    val debugMode: Settings<Boolean>
    val autoSign: Settings<Boolean>
    val autoSignTime: Settings<String?>
    val blockVideo: Settings<Boolean>
    val showFollowedOnly: Settings<Boolean>
    val checkCIUpdate: Settings<Boolean>
    val collectThreadSeeLz: Settings<Boolean>
    val collectThreadDescSort: Settings<Boolean>
    val customPrimaryColor: Settings<String?>
    val customStatusBarFontDark: Settings<Boolean>
    val toolbarPrimaryColor: Settings<Boolean>
    val defaultSortType: Settings<String?>
    val darkTheme: Settings<String?>
    val doNotUsePhotoPicker: Settings<Boolean>
    val useDynamicColorTheme: Settings<Boolean>
    val followSystemNight: Settings<Boolean>
    val fontScale: Settings<Float>
    val forumFabFunction: Settings<String?>
    val hideBlockedContent: Settings<Boolean>
    val hideExplore: Settings<Boolean>
    val defaultStart: Settings<Int>
    val hideForumIntroAndStat: Settings<Boolean>
    val incognitoMode: Settings<Boolean>
    val hideMedia: Settings<Boolean>
    val hideReply: Settings<Boolean>
    val homePageScroll: Settings<Boolean>
    val homePageShowHistoryForum: Settings<Boolean>
    val imageDarkenWhenNightMode: Settings<Boolean>
    val imageLoadType: Settings<String?>
    val imeHeight: Settings<Int>
    val liftUpBottomBar: Settings<Boolean>
    val listItemsBackgroundIntermixed: Settings<Boolean>
    val listSingle: Settings<Boolean>
    val littleTail: Settings<String?>
    val loadPictureWhenScroll: Settings<Boolean>
    val oldTheme: Settings<String?>
    val oksignSlowMode: Settings<Boolean>
    val oksignUseOfficialOksign: Settings<Boolean>
    val oksignFailAutoStop: Settings<Boolean>
    val picWatermarkType: Settings<String?>
    val postOrReplyWarning: Settings<Boolean>
    val radius: Settings<Int>
    val signDay: Settings<Int>
    val showBlockTip: Settings<Boolean>
    val showBothUsernameAndNickname: Settings<Boolean>
    val showDisagreeButton: Settings<Boolean>
    val showExperimentalFeatures: Settings<Boolean>
    val showShortcutInThread: Settings<Boolean>
    val showTopForumInNormalList: Settings<Boolean>
    val statusBarDarker: Settings<Boolean>
    val theme: Settings<String?>
    val translucentBackgroundAlpha: Settings<Int>
    val translucentBackgroundBlur: Settings<Int>
    val translucentBackgroundTheme: Settings<Int>
    val translucentThemeBackgroundPath: Settings<String?>
    val translucentPrimaryColor: Settings<String?>
    val useCustomTabs: Settings<Boolean>
    val useWebView: Settings<Boolean>
}

/**
 * DataStore 实现（0ranko0P 的 DataStoreSettingsRepository 同构）：
 * 所有写入经 [JobQueue] 串行化（Dispatchers.IO），流订阅去重后下发最新值。
 *
 * **同步读缓存（Phase 6.5）**：进程内维护单份 `Preferences` 缓存（一个后台收集器保鲜），
 * 未预热时按需阻塞读一次——与旧 `AppPreferencesUtils` 的"首次 get 阻塞 + 之后读内存"
 * 语义一致；`set` 先乐观改缓存（读后写立即可见）再排队落盘。
 *
 * **启动期预热 + 订阅式读（Phase 8，挂账 §三-1 收口）**：`App.onCreate` 显式调 [warmUp] 填满
 * 缓存，组合期读点改走 [Settings.state] 订阅——惰性阻塞读由此退化成"框架早于 `onCreate`"的兜底，
 * 组合期不再可能触发磁盘 I/O。
 */
class DataStoreSettingsRepository(
    context: Context,
) : SettingsRepository {

    private val queue = JobQueue()

    private val dataStore: DataStore<Preferences> = context.dataStore

    /** 同步读的共享缓存；null = 尚未预热 */
    private val cache = AtomicReference<Preferences?>(null)

    private val warmLock = Any()

    init {
        // 单份收集器保鲜缓存（与 DataStore 实例同生命周期：进程级）
        AppScope.launch {
            dataStore.data.collect { cache.set(it) }
        }
    }

    /** 同步读入口：命中缓存直接返回，否则阻塞预热一次（对齐旧层的阻塞读语义）。 */
    private fun current(): Preferences =
        cache.get() ?: synchronized(warmLock) {
            cache.get() ?: runBlocking { dataStore.data.first() }.also { cache.set(it) }
        }

    /** 乐观更新缓存：保证"写后立即读得到新值"（旧 ReadWriteProperty.set 的语义）。 */
    private fun updateCache(block: MutablePreferences.() -> Unit) {
        // 不用 AtomicReference.updateAndGet：它要求 API 24，而本项目 minSdk 23（lint NewApi 会红）。
        // 本方法只在设置写入时调用、频率极低，用与 current() 同一把锁做同步替换即可，语义等价。
        synchronized(warmLock) {
            cache.set(
                (cache.get() ?: emptyPreferences()).toMutablePreferences().apply(block).toPreferences()
            )
        }
    }

    /** 见 [SettingsRepository.warmUp]：幂等；缓存已热时只是一个 [AtomicReference] 读。 */
    override fun warmUp() {
        current()
    }

    private inner class SimpleSettings<T>(
        private val key: Preferences.Key<T>,
        private val default: T,
    ) : Settings<T>(
        flow = dataStore.data.map { it[key] ?: default }.distinctUntilChanged(),
        syncValue = { current()[key] ?: default },
    ) {

        /**
         * 初值取**进程缓存现值**（`cache.get()`，不触发预热 I/O），因此启动期 [warmUp] 之后
         * 组合期第一帧拿到的就是落盘值，不会退化成 `default`。`by lazy`：只有真正被订阅的
         * 设置项才起收集器（当前只有 theme 链的 `toolbarPrimaryColor`）。
         */
        override val state: StateFlow<T> by lazy {
            flow.stateIn(AppScope, SharingStarted.Eagerly, cache.get()?.get(key) ?: default)
        }

        override fun set(new: T) {
            updateCache { if (new == null) remove(key) else set(key, new) }
            queue.submit(Dispatchers.IO) {
                dataStore.edit {
                    // 可空字符串设置：null = 删除键（与 AppPreferencesUtils 的 string 代理一致）
                    if (new == null) it.remove(key) else it[key] = new
                }
            }
        }

        override fun save(transform: (T) -> T) = queue.submit(Dispatchers.IO) {
            dataStore.edit {
                val new = transform(it[key] ?: default)
                if (new == null) it.remove(key) else it[key] = new
            }
        }
    }

    override val userLikeLastRequestUnix: Settings<Long> = SimpleSettings(longPreferencesKey(SettingsKeys.KEY_USER_LIKE_LAST_REQUEST_UNIX), 0L)
    override val ignoreBatteryOptimizationsDialog: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_IGNORE_BATTERY_OPTIMIZATIONS_DIALOG), false)
    override val appIcon: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_APP_ICON) as Preferences.Key<String?>, SettingsKeys.DEFAULT_APP_ICON)
    override val useThemedIcon: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_USE_THEMED_ICON), false)
    override val debugMode: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_DEBUG_MODE), false)
    override val autoSign: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_AUTO_SIGN), false)
    override val autoSignTime: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_AUTO_SIGN_TIME) as Preferences.Key<String?>, "09:00")
    override val blockVideo: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_BLOCK_VIDEO), false)
    override val showFollowedOnly: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_SHOW_FOLLOWED_ONLY), false)
    override val checkCIUpdate: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_CHECK_CI_UPDATE), false)
    override val collectThreadSeeLz: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_COLLECT_THREAD_SEE_LZ), true)
    override val collectThreadDescSort: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_COLLECT_THREAD_DESC_SORT), false)
    override val customPrimaryColor: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_CUSTOM_PRIMARY_COLOR) as Preferences.Key<String?>, null)
    override val customStatusBarFontDark: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_CUSTOM_STATUS_BAR_FONT_DARK), false)
    override val toolbarPrimaryColor: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_TOOLBAR_PRIMARY_COLOR), false)
    override val defaultSortType: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_DEFAULT_SORT_TYPE) as Preferences.Key<String?>, "0")
    override val darkTheme: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_DARK_THEME) as Preferences.Key<String?>, "grey_dark")
    override val doNotUsePhotoPicker: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_DO_NOT_USE_PHOTO_PICKER), false)
    override val useDynamicColorTheme: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_USE_DYNAMIC_COLOR_THEME), false)
    override val followSystemNight: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_FOLLOW_SYSTEM_NIGHT), true)
    override val fontScale: Settings<Float> = SimpleSettings(floatPreferencesKey(SettingsKeys.KEY_FONT_SCALE), 1.0f)
    override val forumFabFunction: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_FORUM_FAB_FUNCTION) as Preferences.Key<String?>, "post")
    override val hideBlockedContent: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_HIDE_BLOCKED_CONTENT), false)
    override val hideExplore: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_HIDE_EXPLORE), false)
    override val defaultStart: Settings<Int> = SimpleSettings(intPreferencesKey(SettingsKeys.KEY_DEFAULT_START), 0)
    override val hideForumIntroAndStat: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_HIDE_FORUM_INTRO_AND_STAT), false)
    override val incognitoMode: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_INCOGNITO_MODE), false)
    override val hideMedia: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_HIDE_MEDIA), false)
    override val hideReply: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_HIDE_REPLY), false)
    override val homePageScroll: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_HOME_PAGE_SCROLL), false)
    override val homePageShowHistoryForum: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_HOME_PAGE_SHOW_HISTORY_FORUM), true)
    override val imageDarkenWhenNightMode: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_IMAGE_DARKEN_WHEN_NIGHT_MODE), true)
    override val imageLoadType: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_IMAGE_LOAD_TYPE) as Preferences.Key<String?>, "0")
    override val imeHeight: Settings<Int> = SimpleSettings(intPreferencesKey(SettingsKeys.KEY_IME_HEIGHT), 800)
    override val liftUpBottomBar: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_LIFT_UP_BOTTOM_BAR), true)
    override val listItemsBackgroundIntermixed: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_LIST_ITEMS_BACKGROUND_INTERMIXED), true)
    override val listSingle: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_LIST_SINGLE), false)
    override val littleTail: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_LITTLE_TAIL) as Preferences.Key<String?>, null)
    override val loadPictureWhenScroll: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_LOAD_PICTURE_WHEN_SCROLL), true)
    override val oldTheme: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_OLD_THEME) as Preferences.Key<String?>, null)
    override val oksignSlowMode: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_OKSIGN_SLOW_MODE), true)
    override val oksignUseOfficialOksign: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_OKSIGN_USE_OFFICIAL_OKSIGN), true)
    override val oksignFailAutoStop: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_OKSIGN_FAIL_AUTO_STOP), true)
    override val picWatermarkType: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_PIC_WATERMARK_TYPE) as Preferences.Key<String?>, "2")
    override val postOrReplyWarning: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_POST_OR_REPLY_WARNING), true)
    override val radius: Settings<Int> = SimpleSettings(intPreferencesKey(SettingsKeys.KEY_RADIUS), 8)
    override val signDay: Settings<Int> = SimpleSettings(intPreferencesKey(SettingsKeys.KEY_SIGN_DAY), -1)
    override val showBlockTip: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_SHOW_BLOCK_TIP), true)
    override val showBothUsernameAndNickname: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_SHOW_BOTH_USERNAME_AND_NICKNAME), false)
    override val showDisagreeButton: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_SHOW_DISAGREE_BUTTON), true)
    override val showExperimentalFeatures: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_SHOW_EXPERIMENTAL_FEATURES), false)
    override val showShortcutInThread: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_SHOW_SHORTCUT_IN_THREAD), true)
    override val showTopForumInNormalList: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_SHOW_TOP_FORUM_IN_NORMAL_LIST), true)
    override val statusBarDarker: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_STATUS_BAR_DARKER), true)
    override val theme: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_THEME) as Preferences.Key<String?>, SettingsKeys.THEME_DEFAULT)
    override val translucentBackgroundAlpha: Settings<Int> = SimpleSettings(intPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_BACKGROUND_ALPHA), 255)
    override val translucentBackgroundBlur: Settings<Int> = SimpleSettings(intPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_BACKGROUND_BLUR), 0)
    override val translucentBackgroundTheme: Settings<Int> = SimpleSettings(intPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_BACKGROUND_THEME), SettingsKeys.TRANSLUCENT_THEME_LIGHT)
    override val translucentThemeBackgroundPath: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_THEME_BACKGROUND_PATH) as Preferences.Key<String?>, null)
    override val translucentPrimaryColor: Settings<String?> = SimpleSettings(stringPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_PRIMARY_COLOR) as Preferences.Key<String?>, null)
    override val useCustomTabs: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_USE_CUSTOM_TABS), true)
    override val useWebView: Settings<Boolean> = SimpleSettings(booleanPreferencesKey(SettingsKeys.KEY_USE_WEB_VIEW), true)
}
