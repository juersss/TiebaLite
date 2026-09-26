package com.huanchengfly.tieba.post.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** 版本目录访问器（build-logic 内由 settings.gradle.kts 从根 toml 装载）。 */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * 读档位（纯字符串），例如 `versionOf("buildTools")` → "36.0.0"。
 *
 * SDK 档位、构建工具版本等一律走 toml，避免散落在各模块脚本里各写一份。
 */
fun Project.versionOf(alias: String): String =
    libs.findVersion(alias).get().requiredVersion

/** 读数字档位，例如 `intVersionOf("compileSdk")` → 36。 */
fun Project.intVersionOf(alias: String): Int = versionOf(alias).toInt()
