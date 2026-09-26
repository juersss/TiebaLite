package com.huanchengfly.tieba.post.utils.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.utils.ColorUtils
import com.huanchengfly.tieba.post.utils.ThemeUtil
import com.huanchengfly.tieba.post.core.data.appPreferences
fun Color.darken(i: Float = 0.1F): Color {
    return Color(ColorUtils.getDarkerColor(toArgb(), i))
}

fun Color.calcStatusBarColor(): Color {
    val context = App.INSTANCE
    var darkerStatusBar = true
    val isToolbarPrimaryColor =
        context.appPreferences.toolbarPrimaryColor.value
    val isDynamicColor = context.appPreferences.useDynamicColorTheme.value
    if (ThemeUtil.isTranslucentTheme() || !isToolbarPrimaryColor || isDynamicColor) {
        darkerStatusBar = false
    } else if (!context.appPreferences.statusBarDarker.value) {
        darkerStatusBar = false
    }
    return if (darkerStatusBar) darken() else this
}
