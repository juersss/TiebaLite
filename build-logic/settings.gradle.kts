/*
 * build-logic 是一个独立构建（included build），根 settings.gradle.kts 通过
 * pluginManagement { includeBuild("build-logic") } 引入。
 *
 * 关键点：
 * 1. versionCatalogs 复用根仓库的 gradle/libs.versions.toml——依赖版本只有一处事实源，
 *    约定插件自身需要的 AGP/Kotlin/KSP/Compose 编译器插件也从同一 toml 取版本；
 * 2. Gradle 不会把根工程的 gradle.properties 传给 included build
 *    （gradle/gradle#2534），故这里单独声明 org.gradle.* 开关；
 * 3. 仓库口径与根工程一致（阿里云镜像 + google/central + jitpack），避免两边解析出
 *    不同产物。
 */
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io")
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
