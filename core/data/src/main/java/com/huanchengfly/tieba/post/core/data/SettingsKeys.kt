package com.huanchengfly.tieba.post.core.data

/**
 * `app_preferences` DataStore 的**键名与默认值单一事实源**（Phase 5，2026-09-17）。
 *
 * 红线：DataStore 文件名 `app_preferences` 与全部键名一字不动。Phase 6.5 之后
 * [SettingsRepository] 是**唯一设置实现**（旧 `AppPreferencesUtils` 代理层已删除），
 * 全部读写都从这里取键——键名无法漂移。键名清单由 `tools/extract-settings-baseline.py`
 * 从迁移前的 AppPreferencesUtils 提取（`tools/settings-baseline.json`），
 * 改键名必须先改基线再重新生成（`tools/gen-settings-files.py`）。
 */
object SettingsKeys {
    // ── 默认值常量（原为 app 侧 ThemeUtil/LauncherIcons 的值，迁入 core:data 保持单一事实源）──
    /** 原 ThemeUtil.THEME_DEFAULT：主题名默认值 */
    const val THEME_DEFAULT = "tieba"
    /** 原 ThemeUtil.TRANSLUCENT_THEME_LIGHT：半透明主题浅色模式 */
    const val TRANSLUCENT_THEME_LIGHT = 0
    /** 原 LauncherIcons.DEFAULT_ICON：appIcon 默认值（值为启动 Activity 组件名，manifest 同名） */
    const val DEFAULT_APP_ICON = "com.huanchengfly.tieba.post.MainActivityV2"

    // ── 键名（62 个，键名红线：一字不动）──
    const val KEY_USER_LIKE_LAST_REQUEST_UNIX = "userLikeLastRequestUnix"
    const val KEY_IGNORE_BATTERY_OPTIMIZATIONS_DIALOG = "ignoreBatteryOptimizationsDialog"
    const val KEY_APP_ICON = "app_icon"
    const val KEY_USE_THEMED_ICON = "useThemedIcon"
    const val KEY_DEBUG_MODE = "debug_mode"
    const val KEY_AUTO_SIGN = "auto_sign"
    const val KEY_AUTO_SIGN_TIME = "auto_sign_time"
    const val KEY_BLOCK_VIDEO = "blockVideo"
    const val KEY_SHOW_FOLLOWED_ONLY = "showFollowedOnly"
    const val KEY_CHECK_CI_UPDATE = "checkCIUpdate"
    const val KEY_COLLECT_THREAD_SEE_LZ = "collect_thread_see_lz"
    const val KEY_COLLECT_THREAD_DESC_SORT = "collect_thread_desc_sort"
    const val KEY_CUSTOM_PRIMARY_COLOR = "custom_primary_color"
    const val KEY_CUSTOM_STATUS_BAR_FONT_DARK = "custom_status_bar_font_dark"
    const val KEY_TOOLBAR_PRIMARY_COLOR = "custom_toolbar_primary_color"
    const val KEY_DEFAULT_SORT_TYPE = "default_sort_type"
    const val KEY_DARK_THEME = "dark_theme"
    const val KEY_DO_NOT_USE_PHOTO_PICKER = "doNotUsePhotoPicker"
    const val KEY_USE_DYNAMIC_COLOR_THEME = "useDynamicColorTheme"
    const val KEY_FOLLOW_SYSTEM_NIGHT = "follow_system_night"
    const val KEY_FONT_SCALE = "fontScale"
    const val KEY_FORUM_FAB_FUNCTION = "forumFabFunction"
    const val KEY_HIDE_BLOCKED_CONTENT = "hideBlockedContent"
    const val KEY_HIDE_EXPLORE = "hideExplore"
    const val KEY_DEFAULT_START = "defaultStart"
    const val KEY_HIDE_FORUM_INTRO_AND_STAT = "hideForumIntroAndStat"
    const val KEY_INCOGNITO_MODE = "incognitoMode"
    const val KEY_HIDE_MEDIA = "hideMedia"
    const val KEY_HIDE_REPLY = "hideReply"
    const val KEY_HOME_PAGE_SCROLL = "homePageScroll"
    const val KEY_HOME_PAGE_SHOW_HISTORY_FORUM = "homePageShowHistoryForum"
    const val KEY_IMAGE_DARKEN_WHEN_NIGHT_MODE = "imageDarkenWhenNightMode"
    const val KEY_IMAGE_LOAD_TYPE = "image_load_type"
    const val KEY_IME_HEIGHT = "imeHeight"
    const val KEY_LIFT_UP_BOTTOM_BAR = "liftUpBottomBar"
    const val KEY_LIST_ITEMS_BACKGROUND_INTERMIXED = "listItemsBackgroundIntermixed"
    const val KEY_LIST_SINGLE = "listSingle"
    const val KEY_LITTLE_TAIL = "little_tail"
    const val KEY_LOAD_PICTURE_WHEN_SCROLL = "loadPictureWhenScroll"
    const val KEY_OLD_THEME = "old_theme"
    const val KEY_OKSIGN_SLOW_MODE = "oksign_slow_mode"
    const val KEY_OKSIGN_USE_OFFICIAL_OKSIGN = "oksign_use_official_oksign"
    const val KEY_OKSIGN_FAIL_AUTO_STOP = "oksign_fail_auto_stop"
    const val KEY_PIC_WATERMARK_TYPE = "pic_watermark_type"
    const val KEY_POST_OR_REPLY_WARNING = "postOrReplyWarning"
    const val KEY_RADIUS = "radius"
    const val KEY_SIGN_DAY = "sign_day"
    const val KEY_SHOW_BLOCK_TIP = "showBlockTip"
    const val KEY_SHOW_BOTH_USERNAME_AND_NICKNAME = "show_both_username_and_nickname"
    const val KEY_SHOW_DISAGREE_BUTTON = "show_disagree_btn"
    const val KEY_SHOW_EXPERIMENTAL_FEATURES = "showExperimentalFeatures"
    const val KEY_SHOW_SHORTCUT_IN_THREAD = "showShortcutInThread"
    const val KEY_SHOW_TOP_FORUM_IN_NORMAL_LIST = "show_top_forum_in_normal_list"
    const val KEY_STATUS_BAR_DARKER = "status_bar_darker"
    const val KEY_THEME = "theme"
    const val KEY_TRANSLUCENT_BACKGROUND_ALPHA = "translucent_background_alpha"
    const val KEY_TRANSLUCENT_BACKGROUND_BLUR = "translucent_background_blur"
    const val KEY_TRANSLUCENT_BACKGROUND_THEME = "translucent_background_theme"
    const val KEY_TRANSLUCENT_THEME_BACKGROUND_PATH = "translucent_theme_background_path"
    const val KEY_TRANSLUCENT_PRIMARY_COLOR = "translucent_primary_color"
    const val KEY_USE_CUSTOM_TABS = "use_custom_tabs"
    const val KEY_USE_WEB_VIEW = "use_webview"
}
