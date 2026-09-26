package com.huanchengfly.tieba.post.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Compose 模块的共用配置：buildFeatures、BOM 平台依赖、编译器选项。 */
internal fun Project.configureAndroidCompose(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        buildFeatures.apply {
            compose = true
        }

        dependencies {
            val bom = libs.findLibrary("compose.bom").get()
            "implementation"(platform(bom))
            "androidTestImplementation"(platform(bom))
            "implementation"(libs.findLibrary("compose.ui.tooling.preview").get())
            "debugImplementation"(libs.findLibrary("compose.ui.tooling").get())
        }
    }

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        // 稳定性配置是仓库级文件（根 compose_stability_configuration.txt）
        stabilityConfigurationFile.set(
            project.rootProject.layout.projectDirectory
                .file("compose_stability_configuration.txt").asFile,
        )
        // Compose 稳定性报告与指标采集显著拖慢编译（实测 compileReleaseKotlin +69%），
        // 产物仅供分析，故按需开启：-PcomposeMetrics=true
        if (project.hasProperty("composeMetrics")) {
            metricsDestination.set(project.layout.buildDirectory.dir("compose_metrics"))
            reportsDestination.set(project.layout.buildDirectory.dir("compose_metrics"))
        }
    }
}
