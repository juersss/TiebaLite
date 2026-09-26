// 结构大改 Phase 3b-move：api 树（151 文件）与 321 个 proto 从 app 搬入。
// 通用档位（SDK/Java/Kotlin/Robolectric/依赖锁定）由 tblite.android.library 约定插件收口；
// 本文件只留 network 特有的东西：wire 代码生成与依赖清单。
plugins {
    alias(libs.plugins.tblite.android.library)
    alias(libs.plugins.tblite.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wire)
}

wire {
    sourcePath {
        srcDir("src/main/protos")
    }

    kotlin {
        android = true
    }
}

android {
    namespace = "com.huanchengfly.tieba.post.core.network"
}

dependencies {
    // core 之间只允许依赖 core:common（§8.1）。
    // api：helios/toJson/toMD5/GsonUtil 的类型出现在本模块公开签名里，下游必须可见。
    api(projects.core.common)

    // api：wire 生成类（Message/Adapter）与 gson 注解出现在公开 API 签名里；
    // coroutines-core 的 Flow/Deferred 同样是接口返回类型。okio 随 wire-runtime 传递可见。
    api(libs.wire.runtime)
    api(libs.google.gson)
    api(libs.kotlinx.coroutines.core)

    // Retrofit 接口方法签名含 okhttp/retrofit 类型（FormBody、Converter 等），
    // 实现类与调用方都需可见 → api；纯内部使用的降级 implementation。
    api(libs.okhttp3.core)
    api(libs.retrofit2.core)
    implementation(libs.retrofit2.converter.wire)

    // 请求参数/响应模型用到的序列化
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    // ApiResult 在 suspend 里 withContext(Dispatchers.Main)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)

    // 4 个响应 Bean 用 @androidx.compose.runtime.Immutable——只取注解，不开 compose
    implementation(libs.compose.runtime)

    // api 树自带 @Module(TiebaApi) 与 @EntryPoint(SessionProviders)，
    // hilt-android/hilt-compiler 由 tblite.hilt 注入。javax.annotation.concurrent.Immutable
    // （ThreadStoreBean）来自 jsr305——原先经 app 的传递依赖可见，现在显式声明。
    implementation(libs.jsr305)

    testImplementation(libs.junit.junit)
}

// 与 app 同口径：lint 基线挂账存量、只对新增 Error 阻断（CI 跑全量 lintDebug）。
// 这些错误是模块拆分前就在 app 里、被 app/lint-baseline.xml 盖住的存量项。
android {
    lint {
        baseline = file("lint-baseline.xml")
    }
}
