// 结构大改 Phase 4：Room 数据库层（AppDatabase + 7 DAO + 7 实体 + 两条手写迁移 + schemas/）
// 从 app 搬入。通用档位由 tblite.android.library 约定插件收口；本文件只留 database 特有的东西。
plugins {
    alias(libs.plugins.tblite.android.library)
    alias(libs.plugins.tblite.hilt)
    alias(libs.plugins.kotlin.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.huanchengfly.tieba.post.core.database"
}

dependencies {
    // 实体 Block.getKeywords() 用 core:common 的 fromJson（Phase 4 随实体从 app 根包迁入）。
    implementation(projects.core.common)

    // api：DAO 返回 Flow<T>（coroutines-core）、AppDatabase/DAO 类型与 RoomDatabase 基类
    // （room-runtime/room-ktx）都是本模块公开签名，app 侧 DatabaseUtil/注入点直接引用。
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)

    // 实体上的 @androidx.compose.runtime.Immutable/@Stable——只取注解，不开 compose
    implementation(libs.compose.runtime)

    // DatabaseModule（@Module @Provides）与 AppDatabaseEntryPoint 留在本模块：
    // 迁移常量的 internal 可见性要求生产与测试同模块（Migration39To40Test 用**生产同一条**迁移）。
    // hilt-android/ksp(hilt-compiler) 由 tblite.hilt 注入；room-compiler 走 KSP。
    ksp(libs.androidx.room.compiler)

    // 3 个 Room 测试（DAO/迁移/schema 守卫）随迁：Robolectric 跑平台 SQLite，
    // ApplicationProvider 来自 androidx.test:core；junit 由约定插件注入。
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
