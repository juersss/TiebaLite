package com.huanchengfly.tieba.post.selfcheck

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.material.ExperimentalMaterialApi
import com.huanchengfly.tieba.post.activities.AppFontSizeActivity
import com.huanchengfly.tieba.post.ui.page.NavGraphs
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * UI 导航组。
 *
 * 本应用是**单 Activity + Compose 路由**架构（清单里只有 11 个 activity，业务页面几乎全是
 * `@Destination` 路由），所以"逐页 startActivity"能覆盖的目标天然很少。本组因此拆成两块：
 * 1. 路由表完整性——直接读 composedestinations 生成的 `NavGraphs`，把"关键路由还在不在"
 *    变成断言（包名/文件搬家把 route 改掉，深链与通知跳转就会静默落到主页）；
 * 2. 页面可达性——真的 startActivity 并等它 resumed，配"残留进行中标记"检测进程崩溃。
 */

/** 页面可达性的等待上限：冷启动/Compose 首次组合在低端机上可能偏慢 */
private const val NAV_TIMEOUT_MS = 15_000L

/**
 * 上次导航是否走完了。
 *
 * 用 SharedPreferences（且 `commit()` 同步落盘）而不是内存标记：进程若在导航中被
 * 崩溃/杀死，内存里的东西一并没了，**只有落盘标记能留下来**——下次打开面板读到
 * 残留标记就是证据。清标记用 `remove().commit()` 同理。
 */
internal object NavPendingMarker {
    private const val PREFS_NAME = "selfcheck_nav_state"
    private const val KEY_PENDING = "pending_launch"

    fun mark(context: Context, targetClassName: String) {
        prefs(context).edit()
            .putString(KEY_PENDING, "$targetClassName@${System.currentTimeMillis()}")
            .commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).commit()
    }

    /** @return 形如 `类名@时间戳`，无残留返回 null */
    fun read(context: Context): String? = prefs(context).getString(KEY_PENDING, null)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 生命周期探针：把"某个类 resumed 了"变成可等待的信号。
 *
 * 为什么不用"resumed 集合里有没有它"：目标页可能**本来就已在栈里**（比如用户是从别的
 * 页面点进面板的），那样一查就在，用例会恒绿。这里只认"本次 arm 之后**新发生**的
 * onActivityResumed"，杜绝这种假绿。
 */
internal object NavProbe {
    private val pendingClass = AtomicReference<String?>(null)
    private val signal = AtomicReference<CompletableDeferred<Activity>?>(null)
    private val instances = ConcurrentHashMap<String, WeakReference<Activity>>()

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext as Application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                instances[activity.javaClass.name] = WeakReference(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                instances[activity.javaClass.name] = WeakReference(activity)
                val awaited = pendingClass.get()
                if (awaited != null && awaited == activity.javaClass.name) {
                    signal.get()?.complete(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                instances.remove(activity.javaClass.name)
            }
        })
    }

    /** 布置等待：下一个 [cls] 的 onActivityResumed 会唤醒 [awaitResumed] */
    fun arm(cls: Class<out Activity>) {
        signal.set(CompletableDeferred())
        pendingClass.set(cls.name)
    }

    suspend fun awaitResumed(timeoutMs: Long): Activity {
        val deferred = signal.get() ?: error("未 arm 就等待 resumed")
        return withTimeout(timeoutMs) { deferred.await() }
    }

    fun disarm() {
        pendingClass.set(null)
        signal.set(null)
    }
}

/**
 * 上次导航崩溃残留。**必须排在本组最前**——它是"上一轮"的证据，
 * 本轮的导航用例还没开始写标记。
 */
object NavResidualCase : SelfCheckCase {
    override val group = "UI 导航"
    override val name = "上次导航崩溃残留"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val leftover = NavPendingMarker.read(context) ?: return pass()
        NavPendingMarker.clear(context)
        val target = leftover.substringBefore('@')
        val at = leftover.substringAfter('@', "")
            .toLongOrNull()
            ?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) }
            ?: "未知时间"
        return fail(
            "上次导航到 $target（$at）没有走完：进程很可能在导航期间崩溃/被杀。" +
                "请查 logcat 里该时刻前后的异常，再重跑本组确认。"
        )
    }
}

/**
 * 路由表完整性。
 *
 * composedestinations 在编译期生成 `NavGraphs`，`DestinationsNavHost` 直接吃它——
 * startRoute 不在自己的目的地集合里、路由字符串被改掉，运行时是"跳转静默失效"而不是
 * 编译错误，只有跑起来才看得见。这里把三件事钉住：
 * 起点在图内、路由非空、以及**深链/通知依赖的关键路由仍然存在**。
 */
object NavRouteTableCase : SelfCheckCase {
    override val group = "UI 导航"
    override val name = "路由表完整性与关键路由"

    // 生成的 NavGraphs.root 带 @ExperimentalMaterialApi（主页的 DestinationsNavHost 同样要 opt-in）
    @OptIn(ExperimentalMaterialApi::class)
    override suspend fun check(context: Context): SelfCheckOutcome {
        val graph = NavGraphs.root
        expect(graph.route.isNotBlank()) { "根导航图 route 为空" }

        val destinations = graph.destinationsByRoute
        expect(destinations.isNotEmpty()) {
            "根导航图未注册任何目的地 —— DestinationsNavHost 会立刻崩"
        }
        val byBaseRoute = destinations.values.map { it.baseRoute }.toSet()
        expect(graph.startRoute.baseRoute in byBaseRoute) {
            "startRoute=<${graph.startRoute.baseRoute}> 不在自己声明的目的地集合里，" +
                "冷启动会直接崩（子集：${byBaseRoute.sorted().take(5)}…）"
        }
        val blanks = destinations.values.filter { it.route.isBlank() }.map { it.baseRoute }
        expect(blanks.isEmpty()) { "存在 route 为空的目的地：$blanks" }

        // 关键路由：深链(tblite://)、通知点击、分享落地页都按这些名字跳
        val golden = listOf(
            "main_page", "thread_page", "forum_page", "sub_posts_page", "user_profile_page",
            "search_page", "history_page", "notifications_page", "login_page", "web_view_page",
        )
        val missing = golden.filterNot { it in byBaseRoute }
        expect(missing.isEmpty()) {
            "关键路由缺失：$missing —— 深链/通知跳转会静默落到主页（当前共 ${byBaseRoute.size} 个目的地）"
        }
        return pass()
    }
}

/**
 * 深链可解析（Phase 6.5 新增）。
 *
 * **只解析、不拉起**：`MainActivityV2` 是 `launchMode="singleTask"`，从面板里拉它会把面板自己
 * 清掉（见 [NavAppFontSizeCase] 的说明），所以这里用 `queryIntentActivities` 只验证解析结果。
 *
 * manifest 声明了 3 类深链：`tblite://`（自研 scheme）、`com.baidu.tieba://unidispatch`（官方
 * 免登录分发）、`https://tieba.baidu.com/...`（网页链接落地）。任何一类解析不到，分享 / 通知 /
 * 外链跳转都会**静默失效**（不崩不报错），正是打包改包名/改 manifest 最容易踩的那一档。
 */
object DeepLinkResolvableCase : SelfCheckCase {
    override val group = "UI 导航"
    override val name = "深链可解析（只解析不拉起）"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val pm = context.packageManager
        val cases = listOf(
            "tblite://main_page" to "自研 scheme",
            "com.baidu.tieba://unidispatch" to "官方 unidispatch",
            "https://tieba.baidu.com/p/1234567890" to "网页链接落地",
        )
        val broken = mutableListOf<String>()
        for ((uri, what) in cases) {
            // **必须 setPackage**：不限定包的 queryIntentActivities 在 Android 11+ 会被包可见性
            // 过滤，http/https 这类"别的应用也可能声明"的 scheme 尤其容易被滤掉，从而报出
            // **假红**（实测：不带 setPackage 时这一条红，而系统 `pm query-activities` 能解析到
            // 本包的 MainActivityV2 —— 是断言写法问题，不是 manifest 问题）。
            // 限定本包后只在本包内做 filter 匹配，正是我们要断言的"这条 filter 还生效吗"。
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setPackage(context.packageName)
            val resolved = runCatching { pm.queryIntentActivities(intent, 0) }
                .getOrDefault(emptyList())
            if (resolved.isEmpty()) broken += "$what($uri)"
        }

        expect(broken.isEmpty()) {
            "以下深链在本包内解析不到：$broken —— 分享/通知/外链跳转会静默失效"
        }
        return pass()
    }
}

/**
 * 页面可达性：startActivity → 等 resumed → 收尾。
 *
 * 选 [AppFontSizeActivity] 而不是主页 `MainActivityV2`：
 * - 主页是 `launchMode="singleTask"`，从面板里拉它会**清掉它上面的活动**（也就是把自检
 *   面板自己干掉），跑不到报告那一步；
 * - 主页冷启会连带拉首页关注流（2700+ 吧），把自检变成性能测试，不是这里的职责。
 * 这个页面轻量、无入参、纯 View 体系，正好覆盖"传统 Activity + 主题 + DataBinding
 * 这条与 Compose 路由不同的启动路径"。
 */
object NavAppFontSizeCase : SelfCheckCase {
    override val group = "UI 导航"
    override val name = "页面可达：应用字体大小页"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val target = AppFontSizeActivity::class.java
        NavProbe.install(context)
        // 先落盘标记再拉起：这一步之后进程若没了，下次进来就能看到残留
        NavPendingMarker.mark(context, target.name)
        NavProbe.arm(target)

        val intent = Intent(context, target)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            NavProbe.disarm()
            NavPendingMarker.clear(context)
            return fail("startActivity 抛异常 —— ${t.shortMessage()}")
        }

        val activity = try {
            NavProbe.awaitResumed(NAV_TIMEOUT_MS)
        } catch (t: Throwable) {
            // 没等到 resumed 不等于崩溃（可能只是慢/被系统拦），清掉标记避免误报"残留"
            NavProbe.disarm()
            NavPendingMarker.clear(context)
            return fail("${target.simpleName} 在 ${NAV_TIMEOUT_MS}ms 内未 resumed —— ${t.shortMessage()}")
        }

        // 走完了：清标记 + 收掉页面，别把自检留下的页面堆给用户
        NavPendingMarker.clear(context)
        NavProbe.disarm()
        val alive = !activity.isFinishing
        withContext(Dispatchers.Main) { runCatching { activity.finish() } }
        expect(alive) { "${target.simpleName} resumed 后已处于 finishing 状态（自己把自己结束了）" }
        return pass()
    }
}
