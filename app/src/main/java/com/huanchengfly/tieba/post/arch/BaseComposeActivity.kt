package com.huanchengfly.tieba.post.arch

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.stoyanvuchev.systemuibarstweaker.SystemBarStyle
import com.stoyanvuchev.systemuibarstweaker.SystemUIBarsTweaker
import com.stoyanvuchev.systemuibarstweaker.rememberSystemUIBarsTweaker
import com.huanchengfly.tieba.post.activities.BaseActivity
import com.huanchengfly.tieba.post.ui.common.theme.compose.TiebaLiteTheme
import com.huanchengfly.tieba.post.ui.common.windowsizeclass.WindowSizeClass
import com.huanchengfly.tieba.post.ui.common.windowsizeclass.calculateWindowSizeClass
import com.huanchengfly.tieba.post.utils.AccountUtil.LocalAccountProvider
import com.huanchengfly.tieba.post.utils.DebugTraceLog
import com.huanchengfly.tieba.post.utils.ThemeUtil

abstract class BaseComposeActivityWithParcelable<DATA : Parcelable> : BaseComposeActivityWithData<DATA>() {
    abstract val dataExtraKey: String

    override fun parseData(intent: Intent): DATA? {
        return intent.extras?.getParcelable(dataExtraKey)
    }
}

abstract class BaseComposeActivityWithData<DATA> : BaseComposeActivity() {
    var data: DATA? = null

    abstract fun parseData(intent: Intent): DATA?

    override fun onCreate(savedInstanceState: Bundle?) {
        data = parseData(intent)
        super.onCreate(savedInstanceState)
    }

    @Composable
    final override fun Content() {
        data?.let { data ->
            Content(data)
        }
    }

    @Composable
    abstract fun Content(data: DATA)
}

abstract class BaseComposeActivity : BaseActivity<Nothing>() {
    override val isNeedImmersionBar: Boolean = false
    override val isNeedFixBg: Boolean = false
    override val isNeedSetTheme: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 外部定位:浏览进度回退排查——记录 Activity 重建(旋转/进程回收后恢复会让
        // 组合状态走恢复路径,是进度回退的关键上下文)
        DebugTraceLog.log(
            "ACTIVITY",
            "${javaClass.simpleName} onCreate restoredFromSavedState=${savedInstanceState != null}"
        )

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            TiebaLiteTheme {
                val systemUIBarsTweaker = rememberSystemUIBarsTweaker()
                SideEffect {
                    val statusBarDarkIcons = ThemeUtil.isStatusBarFontDark()
                    val navigationBarDarkIcons = ThemeUtil.isNavigationBarFontDark()

                    systemUIBarsTweaker.tweakStatusBarStyle(
                        SystemBarStyle(
                            color = Color.Transparent,
                            darkIcons = statusBarDarkIcons
                        )
                    )
                    systemUIBarsTweaker.tweakNavigationBarStyle(
                        SystemBarStyle(
                            color = Color.Transparent,
                            darkIcons = navigationBarDarkIcons
                        )
                    )
                }

                LaunchedEffect(key1 = "onCreateContent") {
                    onCreateContent(systemUIBarsTweaker)
                }

                LocalAccountProvider {
                    CompositionLocalProvider(
                        LocalWindowSizeClass provides calculateWindowSizeClass(activity = this)
                    ) {
                        Content()
                    }
                }
            }
        }
    }

    /**
     * 在创建内容前执行
     *
     * @param systemUIBarsTweaker SystemUIBarsTweaker
     */
    open fun onCreateContent(
        systemUIBarsTweaker: SystemUIBarsTweaker
    ) {}

    @Composable
    abstract fun Content()

    override fun onStart() {
        super.onStart()
        DebugTraceLog.log("ACTIVITY", "${javaClass.simpleName} onStart")
    }

    override fun onStop() {
        super.onStop()
        DebugTraceLog.log("ACTIVITY", "${javaClass.simpleName} onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugTraceLog.log("ACTIVITY", "${javaClass.simpleName} onDestroy")
    }

    fun handleCommonEvent(event: CommonUiEvent) {
        when (event) {
            is CommonUiEvent.Toast -> {
                Toast.makeText(this, event.message, event.length).show()
            }

            else -> {}
        }
    }

    companion object {
        val LocalWindowSizeClass =
            staticCompositionLocalOf<WindowSizeClass> {
                WindowSizeClass.calculateFromSize(DpSize(0.dp, 0.dp))
            }
    }
}



sealed interface CommonUiEvent : UiEvent {
    object ScrollToTop : CommonUiEvent

    object NavigateUp : CommonUiEvent

    data class Toast(
        val message: CharSequence,
        val length: Int = android.widget.Toast.LENGTH_SHORT
    ) : CommonUiEvent

    @Composable
    fun BaseViewModel<*, *, *, *>.bindScrollToTopEvent(lazyListState: LazyListState) {
        onEvent<ScrollToTop> {
            // 外部定位:任何"回到顶部"都必须有明确的触发源记录,否则无法区分
            // 主动回顶与"进度回退"假象
            DebugTraceLog.log(
                "SCROLL_TOP",
                "${this@bindScrollToTopEvent.javaClass.simpleName} CommonUiEvent.ScrollToTop → scrollToItem(0,0)"
            )
            lazyListState.scrollToItem(0, 0)
        }
    }
}