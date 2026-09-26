import com.huanchengfly.tieba.post.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/**
 * `tblite.hilt`：依赖注入约定——应用 KSP + Hilt，并注入 hilt-android / hilt-compiler。
 *
 * 模块脚本因此不再需要写 `implementation(libs.hilt.android)` 与 `ksp(libs.hilt.compiler)`；
 * `androidx.hilt:hilt-compiler`（@HiltViewModel 支持）与 hilt-navigation-compose 仍由
 * 各模块按需声明——并非每个模块都用到 ViewModel 注入。
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.google.devtools.ksp")

            dependencies {
                "ksp"(libs.findLibrary("hilt.compiler").get())
            }

            // Android 模块（application / library）才有 hilt-android 插件与运行时
            pluginManager.withPlugin("com.android.base") {
                apply(plugin = "com.google.dagger.hilt.android")
                dependencies {
                    "implementation"(libs.findLibrary("hilt.android").get())
                }
            }
        }
    }
}
