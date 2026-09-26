import com.android.build.api.dsl.LibraryExtension
import com.huanchengfly.tieba.post.buildlogic.configureKotlinAndroid
import com.huanchengfly.tieba.post.buildlogic.intVersionOf
import com.huanchengfly.tieba.post.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.dependencyLocking

/**
 * `tblite.android.library`：library 模块的通用约定。
 *
 * 与 application 约定的差异：不设 targetSdk 默认值（library 无独立目标版本语义）、
 * 统一注入 junit 单测依赖。namespace 由各模块脚本自行声明（AGP 8 强制）。
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.library")
            apply(plugin = "org.jetbrains.kotlin.android")

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                defaultConfig.minSdk = intVersionOf("minSdk")
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                testOptions.animationsDisabled = true
            }

            dependencies {
                "testImplementation"(libs.findLibrary("junit.junit").get())
            }

            dependencyLocking { lockAllConfigurations() }
        }
    }
}
