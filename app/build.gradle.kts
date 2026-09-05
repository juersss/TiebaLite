import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 读取 application.properties
val appProperties = Properties().apply {
    file("${rootProject.projectDir}/application.properties").inputStream().use { load(it) }
}

// 读取 keystore.properties（路径等非敏感项）。
// 注意:口令不再存放在仓库目录内的这个文件里——签名口令的解析顺序:
//   1) 环境变量 TIEBA_KEYSTORE_PASSWORD / TIEBA_KEY_PASSWORD
//   2) 用户级文件 ~/.tieba-personal.properties(仓库之外,打包/分享工作区不会带走)
//   3) 兜底:仓库内 keystore.properties(仅为向后兼容保留;正常应缺省)
// 若三处都取不到口令且 keystore.file 已配置,构建直接失败并给出提示——
// 绝不静默降级到 debug 签名发布 release(覆盖升级依赖固定签名)。
val keystorePropertiesFile = file("${rootProject.projectDir}/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val userKeystoreSecrets = Properties().apply {
    val f = File(System.getProperty("user.home"), ".tieba-personal.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun keystoreSecret(key: String): String? =
    System.getenv(if (key == "keystore.password") "TIEBA_KEYSTORE_PASSWORD" else "TIEBA_KEY_PASSWORD")
        ?: userKeystoreSecrets.getProperty(key)
        ?: keystoreProperties.getProperty(key)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
    //alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.wire)
}

val sha: String? = System.getenv("GITHUB_SHA")
val isCI: String? = System.getenv("CI")
val isSelfBuild = isCI.isNullOrEmpty() || !isCI.equals("true", ignoreCase = true)
val applicationVersionCode = appProperties.getProperty("versionCode").toInt()
var applicationVersionName = appProperties.getProperty("versionName")
val isPerVersion = appProperties.getProperty("isPreRelease").toBoolean()
if (isPerVersion) {
    applicationVersionName += "-${appProperties.getProperty("preReleaseName")}.${appProperties.getProperty("preReleaseVer")}"
}
if (!isSelfBuild && !sha.isNullOrEmpty()) {
    applicationVersionName += "+${sha.substring(0, 7)}"
}

wire {
    sourcePath {
        srcDir("src/main/protos")
    }

    kotlin {
        android = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    buildToolsVersion = "36.0.0"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.huanchengfly.tieba.post"
        minSdk = 23
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = applicationVersionCode
        versionName = applicationVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // 语言资源过滤(0ranko 同款):app 仅中文(values 默认)无本地化目录,不过滤会把
        // AndroidX/Compose 全部 50+ 语言库资源打进 APK。zh-rCN 覆盖库的简中,en 为库默认兜底
        resourceConfigurations.addAll(listOf("en", "zh-rCN"))
        manifestPlaceholders["is_self_build"] = "$isSelfBuild"
    }
    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
    }
    signingConfigs {
        val keystoreFile = keystoreProperties.getProperty("keystore.file", "")
        if (keystoreFile.isNotBlank()) {
            val storePassword = keystoreSecret("keystore.password")
            val keyPassword = keystoreSecret("keystore.key.password")
            if (storePassword == null || keyPassword == null) {
                throw GradleException(
                    "keystore.file 已配置但签名口令缺失:请设置环境变量 TIEBA_KEYSTORE_PASSWORD/TIEBA_KEY_PASSWORD " +
                        "或用户级文件 ~/.tieba-personal.properties。" +
                        "拒绝静默降级到 debug 签名发布 release。"
                )
            }
            create("config") {
                storeFile = file(File(rootDir, keystoreFile))
                this.storePassword = storePassword
                keyAlias = keystoreProperties.getProperty("keystore.key.alias")
                this.keyPassword = keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            isJniDebuggable = true
            multiDexEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            isJniDebuggable = false
            multiDexEnabled = true
        }
        all {
            signingConfig =
                if (signingConfigs.any { it.name == "config" })
                    signingConfigs.getByName("config")
                else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }
    composeCompiler {
        // Compose 稳定性报告与指标采集会显著拖慢编译(实测使 compileReleaseKotlin 从约 2m30s
        // 增加到约 4m24s,即 +69%)。产物仅供分析用,不参与构建,故改为按需开启:
        //   ... -PcomposeMetrics=true
        // 输出目录:app/build/compose_metrics/
        if (project.hasProperty("composeMetrics")) {
            metricsDestination.set(layout.buildDirectory.dir("compose_metrics"))
            reportsDestination.set(layout.buildDirectory.dir("compose_metrics"))
        }

        stabilityConfigurationFile.set(rootProject.layout.projectDirectory.file("compose_stability_configuration.txt").asFile)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "DebugProbesKt.bin"
        }
    }
    namespace = "com.huanchengfly.tieba.post"
    applicationVariants.configureEach {
        val variant = this
        outputs.configureEach {
            val fileName =
                "${variant.buildType.name}-${applicationVersionName}(${applicationVersionCode}).apk"

            (this as BaseVariantOutputImpl).outputFileName = fileName
        }
    }
}

// 显式固定 Kotlin jvmTarget=17:此前仅 compileOptions 锁了 javac,Kotlin 编译随 JDK 漂移,
// 换机/IDE 直跑(JAVA_HOME=JDK 21)会报"Inconsistent JVM-target compatibility"且误导性极强
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    //Local Files
//    implementation fileTree(include: ["*.jar"], dir: "libs")

    implementation(libs.net.swiftzer.semver.semver)
    implementation(libs.godaddy.color.picker)

    implementation(libs.airbnb.lottie)
    implementation(libs.airbnb.lottie.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.compose.destinations.core)
    ksp(libs.compose.destinations.ksp)

    // implementation(libs.androidx.navigation.compose)

    api(libs.wire.runtime)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.accompanist.drawablepainter)

    // Replaced deprecated Accompanist modules with official/third-party alternatives
    implementation(libs.eygraber.placeholder.material)
    implementation(libs.systemuibars.tweaker)

    implementation(libs.sketch.core)
    implementation(libs.sketch.compose)
    implementation(libs.sketch.ext.compose)
    implementation(libs.sketch.gif)
    implementation(libs.sketch.okhttp)

    implementation(libs.zoomimage.compose.sketch)

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    runtimeOnly(libs.compose.runtime.tracing)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.material)
    implementation(libs.compose.material.navigation)
    implementation(libs.compose.material.icons.core)
    // Optional - Add full set of material icons
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.util)
//    implementation "androidx.compose.material3:material3"

    // Android Studio Preview support
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // UI Tests
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugRuntimeOnly(libs.compose.ui.test.manifest)

    implementation(libs.androidx.constraintlayout.compose)

    implementation(libs.github.oaid)

    implementation(libs.org.jetbrains.annotations)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    //AndroidX
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.window)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.swiperefreshlayout)

    //Test
    testImplementation(libs.junit.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestRuntimeOnly(libs.androidx.test.runner)

    //Glide
    implementation(libs.glide.core)
    ksp(libs.glide.ksp)
    implementation(libs.glide.okhttp3.integration)

    implementation(libs.google.material)

    implementation(libs.okhttp3.core)
    implementation(libs.retrofit2.core)
    implementation(libs.retrofit2.converter.wire)

    implementation(libs.google.gson)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.com.jaredrummler.colorpicker)

    implementation(libs.github.matisse)
    implementation(libs.xx.permissions)
    implementation(libs.com.gyf.immersionbar.immersionbar)

    implementation(libs.com.github.yalantis.ucrop)

    //implementation(libs.com.jakewharton.butterknife)
    //ksp(libs.com.jakewharton.butterknife.compiler)

    // ksp(libs.kotlin.metadata.jvm)
}
