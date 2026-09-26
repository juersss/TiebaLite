// 多模块下的类型安全项目访问器：`projects.core.common` 代替裸字符串 `project(":core:common")`。
// 不开启时 `projects` 会被解析成 TaskContainer.projects（实测报 receiver type mismatch）。
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    // 约定插件（tblite.*）：来自本仓库的 build-logic 独立构建。
    // 多模块化的第一步——把 Android/Kotlin/Compose/Hilt 的通用档位从各模块脚本收口到
    // build-logic/convention，模块脚本只留自己特有的东西。
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/jcenter")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io")
        maven("https://developer.huawei.com/repo/")
        maven("https://developer.hihonor.com/repo")
    }
}

// 曾在此 apply de.fayard.refreshVersions 0.60.6：该插件靠 versions.properties 记录"可用更新"，
// 而本项目版本统一由 gradle/libs.versions.toml 管理，versions.properties 长期只有注释、
// 零版本条目（2026-09-14 实测），即插件处于空转状态——还会把"可升级到 xx"的噪音引进来，
// 与我们对依赖的"不可回退"实证约束冲突（见 app/build.gradle.kts 注释）。故移除，
// 升级依赖改为显式改 libs.versions.toml + 重算 lockfile。

rootProject.name = "TiebaLite"
include(":app")
// 结构大改 Phase 2 起逐个落位：core 之间只允许依赖 core:common
include(":core:common")
// Phase 3b-move：api 树（Retrofit/Wire/请求参数）+ 321 个 proto 迁入
include(":core:network")
// Phase 4：Room 数据库层（AppDatabase + 实体 + DAO + 迁移 + schemas）迁入
include(":core:database")
// Phase 5：DataStore 设置层（dataStore 委托 + 扩展 + AppPreferencesUtils + SettingsRepository）迁入
include(":core:data")
