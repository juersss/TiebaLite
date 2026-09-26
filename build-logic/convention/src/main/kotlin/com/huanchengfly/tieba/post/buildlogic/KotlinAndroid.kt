package com.huanchengfly.tieba.post.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Android 模块（application / library / test）共用的 Kotlin + Android 档位。 */
internal fun Project.configureKotlinAndroid(
    // AGP 8.13 的 CommonExtension 有 6 个类型参数（AGP 9 / NIA 示例是 4 个），
    // 且各泛型分位在 application/library/test 下不同，故这里用星投影；
    // 依赖具体子类型的配置（defaultConfig.minSdk 等）由各插件自己写。
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        compileSdk = intVersionOf("compileSdk")
        buildToolsVersion = versionOf("buildTools")

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        // Robolectric 需要真实资源表才能起 Application
        testOptions.unitTests.isIncludeAndroidResources = true

        configureRobolectricUnitTests()
    }

    configureKotlin<KotlinAndroidProjectExtension>()
}

/** 纯 JVM 模块（未来的 core:common 等）的 Kotlin 档位。 */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    configureKotlin<KotlinJvmProjectExtension>()
}

/**
 * Robolectric 单测口径统一（2026-09-14 引入，2026-09-15 收口到约定插件）：
 * - 仓库镜像：android-all jar 默认从 repo1.maven.org 拉，本机/CI 走阿里云；
 * - tieba.schemas.dir：Room 迁移测试要从磁盘读导出的 schema，单测工作目录随
 *   AGP/IDE 变化，故显式传绝对路径（无 schema 的模块传了也无害）。
 *
 * 用 tasks.withType<Test> 而非 `testOptions.unitTests.all {}`：后者的 Kotlin DSL 糖
 * 在约定插件（非脚本）上下文里无法推断参数类型，前者对所有 Test 任务等价生效。
 */
internal fun Project.configureRobolectricUnitTests() {
    val schemasDir = layout.projectDirectory.dir("schemas").asFile.absolutePath
    tasks.withType<Test>().configureEach {
        systemProperty(
            "robolectric.dependency.repo.url",
            "https://maven.aliyun.com/repository/central",
        )
        systemProperty("tieba.schemas.dir", schemasDir)
    }
}

private inline fun <reified T : KotlinBaseExtension> Project.configureKotlin() = configure<T> {
    when (this) {
        is KotlinAndroidProjectExtension -> compilerOptions
        is KotlinJvmProjectExtension -> compilerOptions
        else -> error("Unsupported project extension $this ${T::class}")
    }.apply {
        // 显式固定 jvmTarget=17：此前仅 compileOptions 锁了 javac，Kotlin 编译随 JDK 漂移，
        // 换机/IDE 直跑（JAVA_HOME=JDK 21）会报 "Inconsistent JVM-target compatibility"。
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}
