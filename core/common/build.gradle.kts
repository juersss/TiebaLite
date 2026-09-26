plugins {
    alias(libs.plugins.tblite.jvm.library)
}

dependencies {
    // api：这些类型出现在模块公开签名里（GsonUtil.getGson(): Gson、AppScope: CoroutineScope），
    // 下游模块要能编译就必须看得到，故不能用 implementation。
    api(libs.google.gson)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.junit)
}

// 纯 JVM 模块没有 Android 变体，测试任务就叫 test、结果落在 build/test-results/test。
// 为了让门禁（run-tests.sh）与 CI 只维护一套口径，这里补两个同名别名，并把 JUnit XML
// 输出到与 Android 模块一致的目录——否则 `testDebugUnitTest` 在 JVM 模块上不存在，
// 门禁会静默少统计整个模块的用例（2026-09-16 实测：漏掉 ImageUrlUtilTest 11 例，246→235）。
tasks.named<Test>("test") {
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/testDebugUnitTest"))
}
tasks.register("testDebugUnitTest") { dependsOn("test") }
tasks.register("cleanTestDebugUnitTest") { dependsOn("cleanTest") }
