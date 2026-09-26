import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.huanchengfly.tieba.post.buildlogic"

// 约定插件自身按 JDK 17 编译（与项目构建的 JDK 一致，与目标设备的 Android 版本无关）。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly：这些只是"约定插件编译期要看见的 API"，不进任何产物；
    // 真正应用插件的是被配置的子工程（其类路径来自根 build.gradle.kts 的 apply false）。
    compileOnly(libs.android.gradle)
    compileOnly(libs.compose.compiler.gradle)
    compileOnly(libs.kotlin.gradle)
    // 注意：不要引入 ksp-gradle / 其它 Kotlin 2.3 编译的插件 jar——Gradle 8.14.5 的
    // Kotlin DSL 编译器是 Kotlin 2.0，读不了 2.3 版元数据（实测报 "binary version of
    // its metadata is 2.3.0, expected version is 2.0.0"）。约定插件不直接引用 KSP API，
    // KSP 插件由 tblite.hilt 在各模块上 apply，无需进这里的编译类路径。
}

gradlePlugin {
    plugins {
        register("androidApplicationCompose") {
            id = libs.plugins.tblite.android.application.compose.get().pluginId
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
        register("androidApplication") {
            id = libs.plugins.tblite.android.application.asProvider().get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = libs.plugins.tblite.android.library.compose.get().pluginId
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.tblite.android.library.asProvider().get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidTest") {
            id = libs.plugins.tblite.android.test.get().pluginId
            implementationClass = "AndroidTestConventionPlugin"
        }
        register("hilt") {
            id = libs.plugins.tblite.hilt.get().pluginId
            implementationClass = "HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = libs.plugins.tblite.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
