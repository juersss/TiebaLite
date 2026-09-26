// 结构大改 Phase 5：DataStore 设置层（dataStore 委托 + get/put 扩展 + AppPreferencesUtils
// + SettingsRepository）从 app 搬入。通用档位由 tblite.android.library 约定插件收口；
// 本文件只留 data 特有的东西。红线：DataStore 文件名 `app_preferences` 与全部键名一字不动。
plugins {
    alias(libs.plugins.tblite.android.library)
    alias(libs.plugins.tblite.hilt)
}

android {
    namespace = "com.huanchengfly.tieba.post.core.data"
}

dependencies {
    // JobQueue（0ranko0P 同款串行化写入）在 core:common，纯 JVM 实现；不进公开签名。
    implementation(projects.core.common)

    // api：Context.dataStore 扩展返回 DataStore<Preferences>、Settings<T> 公开签名含 Flow
    // ——app 侧 compose 辅助（rememberPreferenceAsState 等）与 DataStorePreference 直接引用，
    // 必须经 api 可见（3b-move 的教训：公开签名用 implementation 下游编译不过）。
    api(libs.androidx.datastore.preferences)
    api(libs.kotlinx.coroutines.core)

    // Settings<T> 标记 @Immutable（与 core:database 的实体同口径，只取注解不开 compose）
    implementation(libs.compose.runtime)

    // Hilt：@Binds 的 DataModule 在本模块，@ApplicationContext 注入 DataStoreSettingsRepository。
    // hilt-android/ksp(hilt-compiler) 由 tblite.hilt 注入，无需在此重复声明。

    // Robolectric 跑 DataStore 落盘与键名对齐测试（ApplicationProvider 来自 androidx.test:core）；
    // junit 由约定插件注入。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// 与 app 同口径：lint 基线挂账存量、只对新增 Error 阻断（CI 跑全量 lintDebug）。
// 这些错误是模块拆分前就在 app 里、被 app/lint-baseline.xml 盖住的存量项。
android {
    lint {
        baseline = file("lint-baseline.xml")
    }
}
