import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties

// 读取 application.properties
val appProperties = Properties().apply {
    file("${rootProject.projectDir}/application.properties").inputStream().use { load(it) }
}

// 签名凭据解析 + release fail-closed 守卫已外置到 signing.gradle.kts（Phase 7）：
// 凭据解析顺序（env → ~/.tieba-personal.properties → 仓库内 keystore.properties）、
// signingConfig("config") 的创建、以及 validateReleaseSigning 任务都在那里。
// 本脚本只消费结果：`signingConfigs.findByName("config")` 取得到就用，取不到回落 debug。
// 口令刻意不经过 extra 传递（extra 属于 project properties，`gradlew properties` 与构建扫描会打印）。

// 约定插件（build-logic/convention）：
//   tblite.android.application(.compose) 接管 compileSdk/minSdk/buildTools/targetSdk、
//   compileOptions、Kotlin jvmTarget、Compose（buildFeatures + BOM + 编译器选项）、
//   Robolectric 单测口径与依赖锁定；
//   tblite.hilt 接管 KSP + Hilt 插件与 hilt-android/hilt-compiler 依赖。
// 本文件只保留 application 特有的东西：applicationId/版本号/签名/混淆/产物命名，
// 以及 wire、Room KSP 参数、lint 基线等业务强相关配置。
plugins {
    alias(libs.plugins.tblite.android.application)
    alias(libs.plugins.tblite.android.application.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.tblite.hilt)
    alias(libs.plugins.kotlin.ksp)
}

// 必须在 plugins{} 之后 apply：脚本里要取 android 扩展来建 signingConfig。
// 注意是 **Groovy** 脚本（.gradle）而非 .kts：applied 脚本拿不到被应用插件的扩展访问器，
// Kotlin DSL 是静态编译的，`android { }` / ApplicationExtension 都会解析失败（实测 10 个错误）。
apply(from = "signing.gradle")

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

// 321 个 proto 与 wire 代码生成已随 Phase 3b-move 迁入 core:network（见 core/network/build.gradle.kts）。
// Room schema 导出参数随 Phase 4 迁入 core:database（`room.schemaLocation` 见其模块脚本）。

android {
    defaultConfig {
        applicationId = "com.huanchengfly.tieba.post"
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
        // compose 由 tblite.android.application.compose 开启
        buildConfig = true
        viewBinding = true
    }
    // signingConfigs 的创建在 signing.gradle.kts（顶部 apply 的那份脚本）。
    // 本模块只负责在 buildTypes 里引用它：取得到 config 就用正式签名，取不到回落 debug，
    // release 的 fail-closed 由 signing.gradle.kts 注册的 validateReleaseSigning 任务在**执行期**裁决。
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            isJniDebuggable = true
            multiDexEnabled = true
            // debug 允许回落:纯克隆(无 keystore.properties)也能出本地调试包
            signingConfig = signingConfigs.findByName("config")
                ?: signingConfigs.getByName("debug")
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
            // fail-closed(外部审查 1.1/2):keystore.properties 整体缺失时 config 签名
            // 不会创建——此前静默回落 debug 签名发布 release,与"拒绝静默降级"的设计
            // 意图相悖(装过正式包的设备将无法再覆盖升级)。校验不在本配置块内抛错
            // (配置期抛错会连累 assembleDebug/单测/IDE 同步,见外部审查-2),
            // 而是先回落 debug 让配置总能完成,执行期由 taskGraph.whenReady 拦截
            signingConfig = signingConfigs.findByName("config")
                ?: signingConfigs.getByName("debug")
        }
    }
    // compileOptions / Kotlin jvmTarget / compileSdk / minSdk / targetSdk / buildTools
    // 全部由 tblite.android.application 约定插件从 gradle/libs.versions.toml 统一设置。
    // 外部审查-静态分析:CI 跑 lintDebug 并对新增 Error 阻断。存量问题
    // (71 Error:LocalContext 取资源/AppLink scheme/反射等)基线化挂账——
    // lint 只报基线之外的新错误,存量债逐项清偿后由 updateLintBaseline 收缩基线
    lint {
        baseline = file("lint-baseline.xml")
        // 依赖版本提醒是信息级噪音,且 updateLintBaseline 会把检出目录的绝对路径
        // 写进基线(location 的 $HOME 前缀)——去敏后禁用,防止路径条目再生
        disable += "NewerVersionAvailable"
        // lintVital 只分析 fatal 子集,与全量 lint 的共享基线必然错位
        // (每次 release 刷 780 条"baseline not found"噪音)。lint 把关统一由
        // 全量 lintDebug 承担(CI 的 Unit Tests & Lint 工作流每次 push/PR 都跑)
        checkReleaseBuilds = false
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

// Kotlin jvmTarget=17 由 tblite.android.application 约定插件统一设置(此前仅 compileOptions
// 锁了 javac,Kotlin 编译随 JDK 漂移,换机/IDE 直跑会报 Inconsistent JVM-target compatibility)。

dependencies {
    // 结构大改 Phase 2：纯 JVM 通用件（AppScope/GZIPUtils/RC442/GsonUtil/ImageUrlUtil）
    implementation(projects.core.common)
    // 结构大改 Phase 3b-move：api 树 + 321 proto（wire 生成类经其 api() 传递可见）
    implementation(projects.core.network)
    // 结构大改 Phase 4：Room 数据库层（AppDatabase/实体/DAO/迁移；Room 类型经其 api() 传递可见）
    implementation(projects.core.database)
    // 结构大改 Phase 5：DataStore 设置层（dataStore 委托/扩展/AppPreferencesUtils/SettingsRepository；
    // datastore 类型经其 api() 传递可见，app 不再直接声明 datastore 依赖）
    implementation(projects.core.data)

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

    // hilt-android / hilt-compiler 由 tblite.hilt 约定插件注入
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

    // compose-bom 平台与 ui-tooling 由 tblite.android.application.compose 约定插件注入
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

    // Android Studio Preview support（ui-tooling/preview 由 compose 约定插件注入）

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
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.window)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.swiperefreshlayout)

    //Test
    testImplementation(libs.junit.junit)
    // ViewModel 构造级测试的 Main dispatcher(Dispatchers.setMain),仅测试类路径,不进 APK
    testImplementation(libs.kotlinx.coroutines.test)
    // HomePartialChangeTest 需要 Looper/main 线程语义,走 Robolectric(2026-09-14 引入)。
    // Room 迁移/DAO 测试三件套(room-testing 等)已随 Phase 4 迁入 core:database。
    testImplementation(libs.robolectric)
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
    // Room 全套（runtime/ktx/compiler/schemas/迁移）已随 Phase 4 迁入 core:database;
    // app 侧零直接 androidx.room import（实测），Room 类型经其 api() 传递可见。
    implementation(libs.com.jaredrummler.colorpicker)

    implementation(libs.github.matisse)
    implementation(libs.xx.permissions) {
        // Jetifier 保持关闭的前提:依赖字节码零 android/support 引用。
        // ★此前这里写着"XXPermissions 26.8 …字节码 139 个类零 android/support 引用
        // (已解包核验)"——该核验结论是错的,并因此导致线上崩溃(2026-09-12 实机定位):
        //   26.8 实测 139 个类中 **88 个**引用 android/support(NonNull 85 / Nullable 55 /
        //   v4/app/Fragment 39 / FragmentActivity 13 / v4/util/LruCache 3 …),
        //   配合 exclude(support) + enableJetifier=false → 运行期
        //   NoClassDefFoundError: android/support/v4/util/LruCache
        //   (触发点 com.hjq.permissions.permission.PermissionLists.<clinit>)。
        // 对策:升级到 28.0(实测 0 处 android/support 引用)后 exclude 才真正成立。
        // 教训:核验 class/dex 的二进制内容必须用 grep -a 等不会跳过二进制文件的工具,
        // 用默认跳过二进制的手段(如某些 rg 配置)会得到"零引用"的伪结论。
        exclude(group = "com.android.support")
    }
    implementation(libs.com.gyf.immersionbar.immersionbar)

    implementation(libs.com.github.yalantis.ucrop)

    //implementation(libs.com.jakewharton.butterknife)
    //ksp(libs.com.jakewharton.butterknife.compiler)

    // ksp(libs.kotlin.metadata.jvm)
}

// ── release 签名 fail-closed 已外置到 signing.gradle.kts（Phase 7）──────────────
// validateReleaseSigning 任务的注册、以及它对 assembleRelease / bundleRelease / packageRelease /
// publishRelease* 的 dependsOn 接线，都随凭据解析一起搬到了那里——两者本来就是一件事
// （fail-closed 的"原因"就是凭据是否可用），分开维护只会漂移。
// 验收口径不变：无凭据 assembleDebug 成功、无凭据 assembleRelease 失败、有效签名 assembleRelease 成功。
// （依赖锁定由 tblite.android.application 约定插件统一开启；升级依赖时用
//   ./gradlew :app:dependencies --write-locks 重算锁定文件。）
