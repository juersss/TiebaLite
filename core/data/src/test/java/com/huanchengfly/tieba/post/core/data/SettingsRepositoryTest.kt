package com.huanchengfly.tieba.post.core.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SettingsRepository 契约测试（Phase 5 建，Phase 6.5 重写）。
 *
 * Phase 6.5 删除了旧 `AppPreferencesUtils` 代理层，本类随之换掉"与旧层逐项对齐"的锚点，
 * 改为 **以迁移前基线为准**（`tools/settings-baseline.json` = 从旧 APU 提取的记录，
 * 比依赖一个已删除的活层更稳）。覆盖：
 * ① 62 项默认值与基线逐项一致（键/类型/默认值三重锁）；
 * ② 62 项经 repo 写入后逐键落盘（JobQueue 异步写 + 原始 Preferences 断言）；
 * ③ **同步 `value` 语义**（Phase 6.5 新增）：落盘值冷读可见、`set` 后立即可见、
 *    外部写入可见——这三条正是旧同步读点不改行为的前提；
 * ④ `Context.appPreferences` 与 Hilt 注入的是**同一实例**（单一事实源）；
 * ⑤ `save` 读-改-写（JobQueue 串行化）。
 *
 * **`@Config(sdk = O)` 是 Windows JVM 的关键**：SDK_INT < 26 时 DataStore 走
 * `File.renameTo` 回退——Windows 上 renameTo 覆盖已存在文件必失败（间歇性"Unable to
 * rename"即此）；sdk 26+ 走 `Files.move(REPLACE_EXISTING)`，Windows 可用。实测
 * （2026-09-17）：renameTo 覆盖 = false，Files.move 覆盖 = 成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [Build.VERSION_CODES.O])
class SettingsRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val repo = DataStoreSettingsRepository(context)

    private val baselineFile: File = findBaseline()

    /**
     * 清空 DataStore 并**等它真的落盘**。
     *
     * 后续断言都建立在"空库"前提上；`edit {}` 是挂起函数、返回即已落盘，但**本类的
     * 每个用例是一个新实例、共享同一个进程级 DataStore 文件**，不等就会出现"读到上一个
     * 用例残留"的偶发红（2026-09-17 实测：save 用例把 radius 从残留值上累加）。
     */
    private suspend fun clearStoreAndAwait() {
        context.dataStore.edit { it.clear() }
        withTimeout(10_000) {
            while (context.dataStore.data.first().asMap().isNotEmpty()) delay(20)
        }
    }

    // ───────────────────────── ① 默认值与迁移前基线逐项一致 ─────────────────────────

    @Test
    fun defaultsMatchPreMigrationBaselineAcrossAll62Keys() = runBlocking {
        clearStoreAndAwait()
        val baseline = parseBaseline(baselineFile)
        assertEquals("基线条目数异常", 62, baseline.size)
        val expected = baseline.associate { it.name to expectedDefault(it) }
        // 用**清理后新建**的实例读：本类字段里的 repo 是清库之前构造的，其同步缓存可能
        // 仍握着清理前的快照（收集器是异步的）——那样这条用例会假红。
        val fresh = DataStoreSettingsRepository(context)
        assertEquals("62 项默认值与迁移前基线不一致", expected, actualDefaults(fresh))
    }

    /** 62 项当前值（**发现式**：新增/删除设置项会因数量不符而红）。 */
    private fun actualDefaults(repo: SettingsRepository): Map<String, Any?> = mapOf(
        "userLikeLastRequestUnix" to repo.userLikeLastRequestUnix.value,
        "ignoreBatteryOptimizationsDialog" to repo.ignoreBatteryOptimizationsDialog.value,
        "appIcon" to repo.appIcon.value,
        "useThemedIcon" to repo.useThemedIcon.value,
        "debugMode" to repo.debugMode.value,
        "autoSign" to repo.autoSign.value,
        "autoSignTime" to repo.autoSignTime.value,
        "blockVideo" to repo.blockVideo.value,
        "showFollowedOnly" to repo.showFollowedOnly.value,
        "checkCIUpdate" to repo.checkCIUpdate.value,
        "collectThreadSeeLz" to repo.collectThreadSeeLz.value,
        "collectThreadDescSort" to repo.collectThreadDescSort.value,
        "customPrimaryColor" to repo.customPrimaryColor.value,
        "customStatusBarFontDark" to repo.customStatusBarFontDark.value,
        "toolbarPrimaryColor" to repo.toolbarPrimaryColor.value,
        "defaultSortType" to repo.defaultSortType.value,
        "darkTheme" to repo.darkTheme.value,
        "doNotUsePhotoPicker" to repo.doNotUsePhotoPicker.value,
        "useDynamicColorTheme" to repo.useDynamicColorTheme.value,
        "followSystemNight" to repo.followSystemNight.value,
        "fontScale" to repo.fontScale.value,
        "forumFabFunction" to repo.forumFabFunction.value,
        "hideBlockedContent" to repo.hideBlockedContent.value,
        "hideExplore" to repo.hideExplore.value,
        "defaultStart" to repo.defaultStart.value,
        "hideForumIntroAndStat" to repo.hideForumIntroAndStat.value,
        "incognitoMode" to repo.incognitoMode.value,
        "hideMedia" to repo.hideMedia.value,
        "hideReply" to repo.hideReply.value,
        "homePageScroll" to repo.homePageScroll.value,
        "homePageShowHistoryForum" to repo.homePageShowHistoryForum.value,
        "imageDarkenWhenNightMode" to repo.imageDarkenWhenNightMode.value,
        "imageLoadType" to repo.imageLoadType.value,
        "imeHeight" to repo.imeHeight.value,
        "liftUpBottomBar" to repo.liftUpBottomBar.value,
        "listItemsBackgroundIntermixed" to repo.listItemsBackgroundIntermixed.value,
        "listSingle" to repo.listSingle.value,
        "littleTail" to repo.littleTail.value,
        "loadPictureWhenScroll" to repo.loadPictureWhenScroll.value,
        "oldTheme" to repo.oldTheme.value,
        "oksignSlowMode" to repo.oksignSlowMode.value,
        "oksignUseOfficialOksign" to repo.oksignUseOfficialOksign.value,
        "oksignFailAutoStop" to repo.oksignFailAutoStop.value,
        "picWatermarkType" to repo.picWatermarkType.value,
        "postOrReplyWarning" to repo.postOrReplyWarning.value,
        "radius" to repo.radius.value,
        "signDay" to repo.signDay.value,
        "showBlockTip" to repo.showBlockTip.value,
        "showBothUsernameAndNickname" to repo.showBothUsernameAndNickname.value,
        "showDisagreeButton" to repo.showDisagreeButton.value,
        "showExperimentalFeatures" to repo.showExperimentalFeatures.value,
        "showShortcutInThread" to repo.showShortcutInThread.value,
        "showTopForumInNormalList" to repo.showTopForumInNormalList.value,
        "statusBarDarker" to repo.statusBarDarker.value,
        "theme" to repo.theme.value,
        "translucentBackgroundAlpha" to repo.translucentBackgroundAlpha.value,
        "translucentBackgroundBlur" to repo.translucentBackgroundBlur.value,
        "translucentBackgroundTheme" to repo.translucentBackgroundTheme.value,
        "translucentThemeBackgroundPath" to repo.translucentThemeBackgroundPath.value,
        "translucentPrimaryColor" to repo.translucentPrimaryColor.value,
        "useCustomTabs" to repo.useCustomTabs.value,
        "useWebView" to repo.useWebView.value,
    )

    // ───────────────────────── ③ 同步 value 语义（Phase 6.5） ─────────────────────────

    /**
     * 冷读必须拿到**已落盘**的非默认值。
     *
     * 回归点：`App.getResources()` 在 `onCreate` 之前就会读 `fontScale`——若同步读在
     * 缓存未预热时返回默认值，用户设过的字号会在启动瞬间被"重置"（可见 bug）。
     */
    @Test
    fun syncValueSeesPersistedValueWhenCacheIsCold() {
        runBlocking {
            clearStoreAndAwait()
            repo.fontScale.set(1.35f)
            repo.hideReply.set(true)
            // 等**两个**键都落盘。JobQueue 是串行的，但每个 set 是独立任务——只等其中一个，
            // 断言就会读到还排在队列里的另一个（2026-09-17 实测偶发红：hideReply 期望 true 实得 false）。
            withTimeout(10_000) {
                while (true) {
                    val p = context.dataStore.data.first()
                    if (p[floatPreferencesKey(SettingsKeys.KEY_FONT_SCALE)] == 1.35f &&
                        p[booleanPreferencesKey(SettingsKeys.KEY_HIDE_REPLY)] == true
                    ) {
                        break
                    }
                    delay(20)
                }
            }

            // 全新实例 = 冷缓存（进程级缓存未热身）
            val cold = DataStoreSettingsRepository(context)
            assertEquals(1.35f, cold.fontScale.value)
            assertEquals(true, cold.hideReply.value)

            clearStoreAndAwait()
        }
    }

    /**
     * 挂账 §三-1（组合期 `runBlocking` 读 DataStore）的回归锚点：
     * ① `state` 是**订阅式**读——不预热也能收敛到落盘值（组合期读它就不再触碰同步读路径）；
     * ② `warmUp()` 幂等，且预热后同步 `value` 仍是落盘值（`getResources` 早于 `onCreate` 的兜底语义不变）。
     */
    @Test
    fun stateIsSubscriptionBasedAndWarmUpKeepsSyncValueSemantics() {
        runBlocking {
            clearStoreAndAwait()
            repo.fontScale.set(1.35f)
            repo.hideReply.set(true)
            withTimeout(10_000) {
                while (true) {
                    val p = context.dataStore.data.first()
                    if (p[floatPreferencesKey(SettingsKeys.KEY_FONT_SCALE)] == 1.35f &&
                        p[booleanPreferencesKey(SettingsKeys.KEY_HIDE_REPLY)] == true
                    ) {
                        break
                    }
                    delay(20)
                }
            }

            // 全新实例 + 全程不预热：只靠订阅 state 收敛到落盘值（初值才是 default）
            val cold = DataStoreSettingsRepository(context)
            withTimeout(10_000) {
                while (cold.fontScale.state.value != 1.35f ||
                    cold.hideReply.state.value != true
                ) {
                    delay(20)
                }
            }

            cold.warmUp()
            cold.warmUp() // 幂等
            assertEquals(1.35f, cold.fontScale.value)
            assertEquals(true, cold.hideReply.value)

            clearStoreAndAwait()
        }
    }

    /** `set` 必须**立即**对 `value` 可见（旧 ReadWriteProperty.set 的语义，读后写不等落盘）。 */
    @Test
    fun setIsImmediatelyVisibleThroughValue() {
        runBlocking {
            clearStoreAndAwait()
            repo.littleTail.set("小尾巴")
            repo.radius.set(42)
            // 不等待、不 delay：乐观缓存必须已经生效
            assertEquals("小尾巴", repo.littleTail.value)
            assertEquals(42, repo.radius.value)
        }
    }

    /** 外部（直接 DataStore）写入后，`value` 必须能读到——由共享收集器/预热兜底。 */
    @Test
    fun externalWriteBecomesVisibleThroughValue() {
        runBlocking {
            clearStoreAndAwait()
            context.dataStore.edit { it[stringPreferencesKey(SettingsKeys.KEY_LITTLE_TAIL)] = "外部写的" }
            withTimeout(10_000) {
                while (repo.littleTail.value != "外部写的") delay(20)
            }
            assertEquals("外部写的", repo.littleTail.value)
            clearStoreAndAwait()
        }
    }

    // ───────────────────────── ④ 单一事实源：访问器与注入同实例 ─────────────────────────

    @Test
    fun contextAccessorAndFactoryShareTheSameInstance() {
        assertSame(settingsRepository(context), context.appPreferences)
        assertSame(settingsRepository(context), settingsRepository(context))
    }

    // ───────────────────────── ② 62 项写入 → 逐键落盘 ─────────────────────────

    @Test
    fun allSettingsPersistKeyByKey() = runBlocking {
        clearStoreAndAwait()
        repo.userLikeLastRequestUnix.set(1L)
        repo.ignoreBatteryOptimizationsDialog.set(true)
        repo.appIcon.set("t")
        repo.useThemedIcon.set(true)
        repo.debugMode.set(true)
        repo.autoSign.set(true)
        repo.autoSignTime.set("t")
        repo.blockVideo.set(true)
        repo.showFollowedOnly.set(true)
        repo.checkCIUpdate.set(true)
        repo.collectThreadSeeLz.set(true)
        repo.collectThreadDescSort.set(true)
        repo.customPrimaryColor.set("t")
        repo.customStatusBarFontDark.set(true)
        repo.toolbarPrimaryColor.set(true)
        repo.defaultSortType.set("t")
        repo.darkTheme.set("t")
        repo.doNotUsePhotoPicker.set(true)
        repo.useDynamicColorTheme.set(true)
        repo.followSystemNight.set(true)
        repo.fontScale.set(2.0f)
        repo.forumFabFunction.set("t")
        repo.hideBlockedContent.set(true)
        repo.hideExplore.set(true)
        repo.defaultStart.set(1)
        repo.hideForumIntroAndStat.set(true)
        repo.incognitoMode.set(true)
        repo.hideMedia.set(true)
        repo.hideReply.set(true)
        repo.homePageScroll.set(true)
        repo.homePageShowHistoryForum.set(true)
        repo.imageDarkenWhenNightMode.set(true)
        repo.imageLoadType.set("t")
        repo.imeHeight.set(1)
        repo.liftUpBottomBar.set(true)
        repo.listItemsBackgroundIntermixed.set(true)
        repo.listSingle.set(true)
        repo.littleTail.set("t")
        repo.loadPictureWhenScroll.set(true)
        repo.oldTheme.set("t")
        repo.oksignSlowMode.set(true)
        repo.oksignUseOfficialOksign.set(true)
        repo.oksignFailAutoStop.set(true)
        repo.picWatermarkType.set("t")
        repo.postOrReplyWarning.set(true)
        repo.radius.set(1)
        repo.signDay.set(1)
        repo.showBlockTip.set(true)
        repo.showBothUsernameAndNickname.set(true)
        repo.showDisagreeButton.set(true)
        repo.showExperimentalFeatures.set(true)
        repo.showShortcutInThread.set(true)
        repo.showTopForumInNormalList.set(true)
        repo.statusBarDarker.set(true)
        repo.theme.set("t")
        repo.translucentBackgroundAlpha.set(1)
        repo.translucentBackgroundBlur.set(1)
        repo.translucentBackgroundTheme.set(1)
        repo.translucentThemeBackgroundPath.set("t")
        repo.translucentPrimaryColor.set("t")
        repo.useCustomTabs.set(true)
        repo.useWebView.set(true)

        val prefs: Preferences = withTimeout(10_000) {
            var snap: Preferences? = null
            while (true) {
                snap = context.dataStore.data.first()
                if (snap!!.asMap().size == 62) break
                delay(20)
            }
            snap!!
        }
        assertEquals(1L, prefs[longPreferencesKey(SettingsKeys.KEY_USER_LIKE_LAST_REQUEST_UNIX)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_IGNORE_BATTERY_OPTIMIZATIONS_DIALOG)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_APP_ICON)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_USE_THEMED_ICON)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_DEBUG_MODE)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_AUTO_SIGN)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_AUTO_SIGN_TIME)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_BLOCK_VIDEO)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_SHOW_FOLLOWED_ONLY)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_CHECK_CI_UPDATE)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_COLLECT_THREAD_SEE_LZ)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_COLLECT_THREAD_DESC_SORT)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_CUSTOM_PRIMARY_COLOR)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_CUSTOM_STATUS_BAR_FONT_DARK)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_TOOLBAR_PRIMARY_COLOR)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_DEFAULT_SORT_TYPE)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_DARK_THEME)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_DO_NOT_USE_PHOTO_PICKER)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_USE_DYNAMIC_COLOR_THEME)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_FOLLOW_SYSTEM_NIGHT)])
        assertEquals(2.0f, prefs[floatPreferencesKey(SettingsKeys.KEY_FONT_SCALE)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_FORUM_FAB_FUNCTION)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_HIDE_BLOCKED_CONTENT)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_HIDE_EXPLORE)])
        assertEquals(1, prefs[intPreferencesKey(SettingsKeys.KEY_DEFAULT_START)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_HIDE_FORUM_INTRO_AND_STAT)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_INCOGNITO_MODE)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_HIDE_MEDIA)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_HIDE_REPLY)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_HOME_PAGE_SCROLL)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_HOME_PAGE_SHOW_HISTORY_FORUM)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_IMAGE_DARKEN_WHEN_NIGHT_MODE)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_IMAGE_LOAD_TYPE)])
        assertEquals(1, prefs[intPreferencesKey(SettingsKeys.KEY_IME_HEIGHT)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_LIFT_UP_BOTTOM_BAR)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_LIST_ITEMS_BACKGROUND_INTERMIXED)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_LIST_SINGLE)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_LITTLE_TAIL)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_LOAD_PICTURE_WHEN_SCROLL)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_OLD_THEME)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_OKSIGN_SLOW_MODE)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_OKSIGN_USE_OFFICIAL_OKSIGN)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_OKSIGN_FAIL_AUTO_STOP)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_PIC_WATERMARK_TYPE)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_POST_OR_REPLY_WARNING)])
        assertEquals(1, prefs[intPreferencesKey(SettingsKeys.KEY_RADIUS)])
        assertEquals(1, prefs[intPreferencesKey(SettingsKeys.KEY_SIGN_DAY)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_SHOW_BLOCK_TIP)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_SHOW_BOTH_USERNAME_AND_NICKNAME)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_SHOW_DISAGREE_BUTTON)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_SHOW_EXPERIMENTAL_FEATURES)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_SHOW_SHORTCUT_IN_THREAD)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_SHOW_TOP_FORUM_IN_NORMAL_LIST)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_STATUS_BAR_DARKER)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_THEME)])
        assertEquals(1, prefs[intPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_BACKGROUND_ALPHA)])
        assertEquals(1, prefs[intPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_BACKGROUND_BLUR)])
        assertEquals(1, prefs[intPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_BACKGROUND_THEME)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_THEME_BACKGROUND_PATH)])
        assertEquals("t", prefs[stringPreferencesKey(SettingsKeys.KEY_TRANSLUCENT_PRIMARY_COLOR)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_USE_CUSTOM_TABS)])
        assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.KEY_USE_WEB_VIEW)])
    }

    // ───────────────────────── ⑤ save 读-改-写 ─────────────────────────

    @Test
    fun saveIsSerializedReadModifyWrite() = runBlocking {
        // radius 默认 8；并发则各自读 8 写 9，串行才得 10。
        // 必须先等清库落盘，否则从残留值上累加（偶发红）。
        clearStoreAndAwait()
        repo.radius.save { it + 1 }
        repo.radius.save { it + 1 }
        withTimeout(10_000) {
            while (repo.radius.snapshot() != 10) delay(20)
        }
        assertEquals(10, repo.radius.snapshot())
    }

    // ───────────────────────── 基线解析（风格对齐 SettingsKeysAlignmentTest） ─────────────────────────

    private data class BaselineEntry(val name: String, val type: String, val default: String?)

    /**
     * 解析 `tools/settings-baseline.json`。注意 `default` 存的是**原 APU 声明的 Kotlin 源码文本**
     * （JSON 转义后），故 `0L` / `"09:00"` / `LauncherIcons.DEFAULT_ICON` 形态各异；
     * `default: null` 表示旧声明没写 `defaultValue`、靠代理类型默认兜底。
     */
    private fun parseBaseline(file: File): List<BaselineEntry> {
        val text = file.readText()
        val entry = Regex(
            """\{[^{}]*?"name":\s*"([^"]+)"[^{}]*?"type":\s*"([^"]+)"[^{}]*?"default":\s*("(?:[^"\\]|\\.)*"|null)"""
        )
        return entry.findAll(text).map {
            val raw = it.groupValues[3]
            BaselineEntry(
                name = it.groupValues[1],
                type = it.groupValues[2],
                default = if (raw == "null") null else raw.substring(1, raw.length - 1).replace("\\\"", "\""),
            )
        }.toList()
    }

    /** 把基线里的 Kotlin 源码文本还原成期望值（类型由 `type` 字段裁定）。 */
    private fun expectedDefault(e: BaselineEntry): Any? {
        val raw = e.default
            ?: return when (e.type) {
                "boolean" -> false
                "int" -> 0
                "long" -> 0L
                "float" -> 0F
                else -> null
            }
        return when {
            // 原为 app 侧常量的默认值 → Phase 5 已迁入 core:data 的 SettingsKeys 常量
            raw == "LauncherIcons.DEFAULT_ICON" -> SettingsKeys.DEFAULT_APP_ICON
            raw == "ThemeUtil.THEME_DEFAULT" -> SettingsKeys.THEME_DEFAULT
            raw == "TRANSLUCENT_THEME_LIGHT" -> SettingsKeys.TRANSLUCENT_THEME_LIGHT
            raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 ->
                raw.substring(1, raw.length - 1)

            raw == "true" || raw == "false" -> raw.toBoolean()
            raw.endsWith("L") -> raw.dropLast(1).toLong()
            raw.endsWith("f") -> raw.dropLast(1).toFloat()
            else -> raw.toInt()
        }
    }

    private fun findBaseline(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            val candidate = File(dir, "tools/settings-baseline.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: error("向上找不到 tools/settings-baseline.json")
        }
    }
}
