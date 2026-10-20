package com.huanchengfly.tieba.post.core.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.huanchengfly.tieba.post.core.common.AppScope
import com.huanchengfly.tieba.post.core.common.JobQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
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
 * 缓存与磁盘/其它写者的一致性协议见 [SettingsCache]（2026-10-20 收口三路覆盖）。
 *
 * **启动期预热 + 订阅式读（Phase 8，挂账 §三-1 收口）**：`App.onCreate` 显式调 [warmUp] 填满
 * 缓存，组合期读点改走 [Settings.state] 订阅——惰性阻塞读由此退化成"框架早于 `onCreate`"的兜底，
 * 组合期不再可能触发磁盘 I/O。
 */
class DataStoreSettingsRepository(
    context: Context,
    /** 测试注入点（生产恒为 `Context.dataStore`，进程级单例）：默认参数让 Dagger 无需改动。 */
    private val dataStore: DataStore<Preferences> = context.dataStore,
) : SettingsRepository {

    private val queue = JobQueue()

    /**
     * 同步读的共享缓存（协议见 [SettingsCache]，本类只负责"取数 / 排队落盘"）。
     *
     * 三路写入的一致性由它收口：收集器差量合并、乐观 set 带结算、`save` 成功后回写。
     */
    private val cache = SettingsCache()

    init {
        // 单份收集器保鲜缓存（与 DataStore 实例同生命周期：进程级）
        AppScope.launch {
            dataStore.data.collect { cache.onDiskSnapshot(it) }
        }
    }

    /**
     * 同步读入口：命中缓存直接返回，否则阻塞预热一次（对齐旧层的阻塞读语义）。
     *
     * 阻塞读放在缓存锁之外：预热 I/O 期间收集器仍可推进，不与本方法互等。
     */
    private fun current(): Preferences =
        cache.peek() ?: runBlocking { dataStore.data.first() }.let { cache.seed(it) }

    /** 见 [SettingsRepository.warmUp]：幂等；缓存已热时只是一次内存读。 */
    override fun warmUp() {
        current()
    }

    /**
     * 落盘一次，`IOException` 时**重试**（最多 [WRITE_ATTEMPTS] 次，间隔 [WRITE_RETRY_DELAY_MS]）。
     *
     * 为什么必须重试：DataStore 在 Windows 上用 `Files.move(REPLACE_EXISTING)` 覆盖文件，
     * 目标文件被杀软/索引/另一个句柄瞬时占用时会抛 `IOException`（"Unable to rename" 同源）。
     * 单测已实测到约 10% 概率随机丢一个键（`allSettingsPersistKeyByKey` 偶发只落 61 项）——
     * 修复前该异常被 JobQueue 吞掉，写入**永久丢失**且没有任何回滚或提示。
     *
     * 只重试 IO 类失败：`set` 的写入是幂等的；`save` 的重入由调用方保证不重复执行 transform。
     * 其余异常（类型错误、键冲突等）重试无意义，直接判失败。
     *
     * @return true = 已落盘；false = 重试后仍失败（调用方负责回滚/放弃）。
     */
    private suspend fun retryWritesOnIoFailure(block: suspend () -> Unit): Boolean {
        repeat(WRITE_ATTEMPTS) { attempt ->
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt == WRITE_ATTEMPTS - 1) return false
                delay(WRITE_RETRY_DELAY_MS)
            } catch (_: Throwable) {
                return false
            }
        }
        return false
    }

    private inner class SimpleSettings<T>(
        private val key: Preferences.Key<T>,
        private val default: T,
    ) : Settings<T>(
        flow = dataStore.data.map { it[key] ?: default }.distinctUntilChanged(),
        syncValue = { current()[key] ?: default },
    ) {

        /**
         * 初值取**进程缓存现值**（`cache.peek()`，不触发预热 I/O），因此启动期 [warmUp] 之后
         * 组合期第一帧拿到的就是落盘值，不会退化成 `default`。`by lazy`：只有真正被订阅的
         * 设置项才起收集器（当前只有 theme 链的 `toolbarPrimaryColor`）。
         */
        override val state: StateFlow<T> by lazy {
            flow.stateIn(AppScope, SharingStarted.Eagerly, cache.peek()?.get(key) ?: default)
        }

        /**
         * 写入（`set` 语义）：**立刻**改同步缓存，再排队落盘。
         *
         * 落盘失败时 [SettingsCache.settleSet] 会把该键回滚到磁盘值——否则缓存里会留下
         * 一个磁盘上不存在的值，直到下次冷启动才"莫名其妙变回去"。
         */
        override fun set(new: T) {
            // 先取一次 current()：缓存未预热时不能凭空造一份只有单个键的快照（会丢另外 61 项）
            current()
            val handle = cache.beginSet(key, new)
            queue.submit(Dispatchers.IO) {
                val success = retryWritesOnIoFailure {
                    dataStore.edit {
                        // 可空字符串设置：null = 删除键（与 AppPreferencesUtils 的 string 代理一致）
                        if (new == null) it.remove(key) else it[key] = new
                    }
                }
                cache.settleSet(key, handle, success)
            }
        }

        /**
         * 读-改-写（`save` 语义）：变换发生在队列里，基于**磁盘现值**而不是缓存值，
         * 因此不会把别的写者（外部 `dataStore.edit`）的改动吃掉。
         *
         * `save` 没有乐观值可回滚：失败就什么都不做（缓存保持磁盘值）；成功则把结果写回缓存，
         * 让 `value` 与 `set` 一样在写完后可见。
         *
         * 重试时**绝不重复执行 transform**（`it + 1` 会被加两次）：第一次就把结果记进
         * `produced`，后续尝试只重放"写入这个已知值"。
         */
        override fun save(transform: (T) -> T) = queue.submit(Dispatchers.IO) {
            var produced: Any? = null
            var known = false
            val success = retryWritesOnIoFailure {
                dataStore.edit {
                    if (!known) {
                        produced = transform(it[key] ?: default)
                        known = true
                    }
                    it.setOrRemove(key, produced)
                }
            }
            if (success) cache.applySaved(key, produced)
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

    private companion object {
        /**
         * 一次写入最多尝试几次（首次 + 重试 2 次）。
         *
         * 3 次足够覆盖"文件被瞬时占用"这类毫秒级抖动；再多就变成"用户已感知卡顿还写不进去"。
         */
        const val WRITE_ATTEMPTS = 3

        /** 重试间隔：给占用文件的句柄一点释放时间，又不至于让串行队列明显变慢。 */
        const val WRITE_RETRY_DELAY_MS = 40L
    }
}
