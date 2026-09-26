import com.android.build.api.dsl.ApplicationExtension
import com.huanchengfly.tieba.post.buildlogic.configureKotlinAndroid
import com.huanchengfly.tieba.post.buildlogic.intVersionOf
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencyLocking

/**
 * `tblite.android.application`：application 模块的通用约定。
 *
 * 收口内容：Android/Kotlin 档位（compileSdk/minSdk/buildTools/compileOptions/jvmTarget）、
 * targetSdk、Robolectric 单测口径、依赖锁定。
 * 应用模块特有的 applicationId / 版本号 / 签名 / 混淆 / 产物命名仍留在模块脚本里。
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.application")
            apply(plugin = "org.jetbrains.kotlin.android")

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.minSdk = intVersionOf("minSdk")
                defaultConfig.targetSdk = intVersionOf("targetSdk")
                testOptions.animationsDisabled = true
            }

            // 供应链：锁定全部解析配置（历史口径）。新增依赖后需
            // ./gradlew.bat :app:dependencies --write-locks 重算。
            dependencyLocking { lockAllConfigurations() }
        }
    }
}
