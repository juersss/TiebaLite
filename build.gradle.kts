// Top-level build file where you can add configuration options common to all sub-projects/modules.
// 根工程只做"插件标记"声明（apply false）：子工程/约定插件按 id 应用这些插件时，
// 版本由这里的版本目录统一给出。约定插件内部 apply 的每一个 Android/Kotlin/Hilt
// 插件 id，都必须在此登记，否则子工程类路径上找不到（实测报 "Plugin with id ... not found"）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    //alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.wire) apply false

    // Dependency Analysis plugin only supports AGP 8.0.0-8.4.0, commented out for AGP 8.13.0
    // alias(libs.plugins.dependency.analysis)
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory.asFile.get())
}