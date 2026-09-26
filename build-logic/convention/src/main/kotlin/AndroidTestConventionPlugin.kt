import com.android.build.api.dsl.TestExtension
import com.huanchengfly.tieba.post.buildlogic.configureKotlinAndroid
import com.huanchengfly.tieba.post.buildlogic.intVersionOf
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * `tblite.android.test`：`com.android.test` 模块（基准测试 / macrobenchmark）的约定。
 * 该模块类型不能依赖 app，用于生成 Baseline Profile 与启动/滚动基准。
 */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.test")
            apply(plugin = "org.jetbrains.kotlin.android")

            extensions.configure<TestExtension> {
                configureKotlinAndroid(this)
                defaultConfig.minSdk = intVersionOf("minSdk")
                defaultConfig.targetSdk = intVersionOf("targetSdk")
            }
        }
    }
}
