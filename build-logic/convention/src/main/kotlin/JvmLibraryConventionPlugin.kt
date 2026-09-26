import com.huanchengfly.tieba.post.buildlogic.configureKotlinJvm
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencyLocking

/**
 * `tblite.jvm.library`：纯 JVM 模块（未来的 core:common 等）的约定。
 * 只配 Kotlin/Java 17 档位与依赖锁定，不带任何 Android 插件。
 *
 * 注意：Hilt 在纯 JVM 模块需要 hilt-core + ksp，届时按需在模块脚本里补。
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.jvm")

            configureKotlinJvm()

            dependencyLocking { lockAllConfigurations() }
        }
    }
}
