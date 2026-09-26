package com.huanchengfly.tieba.post.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 架构依赖规则的**机器校验**（2026-09-17 新增）。
 *
 * 背景：结构大改的依赖方向三规则原先只写在 `README`/`README.md` 里，靠人审。
 * Phase 3b 起要搬 652 个文件，靠眼睛盯 import 必然漏——规则要么机器可拦，要么等于没有。
 *
 * 校验的规则（对应进度交接 §8.1）：
 * 1. **core 模块不得依赖 app**：core 源码里的 `import com.huanchengfly.tieba.post.*`
 *    必须落在某个 core 模块自己声明的包里（或它自己 `namespace` 覆盖的包内）；
 * 2. **core 之间只允许依赖 core:common**：core:X 不得 import core:Y（Y≠common）的包；
 * 3. **app 可依赖任意 core**（不限制，故不检查）；
 * 4. **core 模块不得 vendor 外部包名**：自己声明的包必须以 `com.huanchengfly.tieba.post.` 开头
 *    （§8.5 明确不采纳"把 androidx 包名搬进自己模块"的反例）；
 * 5. core 模块的 `build.gradle.kts` 不得出现对 `:app` 的项目依赖。
 *
 * 为什么放在 `core:common` 的测试源集里：门禁（`run-tests.sh`）已聚合所有模块的
 * `testDebugUnitTest` XML，放这里零配置即可进 CI；新建独立 arch 模块会触发一次
 * 全量重编译，性价比不划算。
 *
 * 规则**自我维护**：新模块只要出现在 `settings.gradle.kts` 的 `include(...)` 里就被自动纳入，
 * 不需要改这个测试。规则本身也有守卫——[checkerDetectsViolationsOnFixture] 用临时夹具证明
 * "违规真的会被抓到"，防止校验器静默失效（今天已经踩过一次：`isCore` 判定写错导致规则空跑）。
 */
class ArchitectureRulesTest {

    private val repoRoot: File = findRepoRoot()

    // ── 对真实仓库跑规则 ──────────────────────────────────────────────────

    @Test
    fun coreModulesMustNotDependOnAppNorOnSiblingCoreModules() {
        val violations = dependencyViolations(repoRoot)
        report(violations, "依赖方向违规")
    }

    @Test
    fun coreModulePackagesMustStayInProjectNamespace() {
        report(namespaceViolations(repoRoot), "core 模块包名越界")
    }

    @Test
    fun coreModulesMustNotDeclareProjectDependencyOnApp() {
        report(buildFileViolations(repoRoot), "core 模块声明了对 :app 的项目依赖")
    }

    /** 防假绿：扫描面本身也要断言——规则空跑（扫不到文件/模块）时必须报错，而不是"通过" */
    @Test
    fun scanSurfaceIsSane() {
        val modules = scanModules(repoRoot)
        assertTrue("未从 settings.gradle.kts 解析到任何模块", modules.isNotEmpty())
        assertTrue("未找到 :app 模块——模块解析口径可能已失效", modules.any { it.gradlePath == ":app" })
        assertTrue("未找到任何 :core:* 模块——模块解析口径可能已失效", modules.any { it.isCore })

        val totalFiles = modules.sumOf { it.sourceFiles.size }
        assertTrue(
            "只扫到 $totalFiles 个源文件，远低于预期——路径解析或目录遍历出错，本测试会静默放过一切",
            totalFiles > 100,
        )
        val appPackages = modules.first { it.gradlePath == ":app" }.declaredPackages
        assertTrue("app 模块没解析出任何 package，扫描口径异常", appPackages.isNotEmpty())
    }

    /**
     * Compose 的 `painterResource` 不得指向**自适应图标**（`mipmap-anydpi-v26|v31` 下的
     * `<adaptive-icon>` XML）：`painterResource` 只支持 VectorDrawable 与位图，拿到
     * adaptive-icon 会当场抛 `IllegalArgumentException: Only VectorDrawables and rasterized
     * asset types are supported`——点"关于"整页崩溃就是这么来的（2026-09-17 MuMu 实机）。
     *
     * 这类问题编译期无警告、lint 也不报，只能靠规则拦。
     */
    @Test
    fun composePainterResourceMustNotPointToAdaptiveIcon() {
        report(adaptiveIconViolations(repoRoot), "painterResource 指向自适应图标（API 26+ 必崩）")
    }

    private fun adaptiveIconViolations(root: File): List<String> {
        val violations = mutableListOf<String>()
        scanModules(root).forEach { module ->
            val adaptiveIcons = adaptiveIconNames(module)
            if (adaptiveIcons.isEmpty()) return@forEach
            module.sourceFiles.forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        PAINTER_RESOURCE.findAll(line).forEach { m ->
                            val name = m.groupValues[2]
                            if (name in adaptiveIcons) {
                                violations += "${file.rel(root)}:${index + 1} " +
                                    "painterResource(R.mipmap.$name) —— $name 在 " +
                                    "mipmap-anydpi-v26/v31 是 <adaptive-icon>，Compose 会崩；" +
                                    "改用位图/vector 资源（如 drawable 下的同名 PNG）"
                            }
                        }
                    }
                }
            }
        }
        return violations
    }

    /**
     * `api/` 包（`core:network` 的主体）不得 import app 私有包。
     *
     * 3b-prep 期这条规则扫的是 app 内的 `api/` 目录（"搬迁前判据"，当时 `R` 2 处白名单留给 move）。
     * 3b-move 完成后 api 树已在 `core:network` 模块里，故扫描对象改为核心模块自身——
     * 与规则 1 口径一致但**独立成例**，并加扫描面断言：搬迁后若谁把 api 树搬回 app 或路径改名，
     * 本例会因"扫到 0 文件"直接红，而不是静默变成空转的绿灯。
     * `R` 白名单已随 core:network 自建 res（3 条网络文案迁入）删除——api 树现在必须零 app R 引用。
     */
    @Test
    fun apiPackageMustNotDependOnAppInternals() {
        report(apiLeakViolations(repoRoot), "api 树依赖 app 私有包")
    }

    private fun apiLeakViolations(root: File): List<String> {
        val violations = mutableListOf<String>()
        var scannedFiles = 0
        scanModules(root)
            .filter { it.isCore }
            .forEach { module ->
                module.sourceFiles
                    .filter { it.absolutePath.contains(File.separator + "api" + File.separator) }
                    .forEach { file ->
                        scannedFiles++
                        file.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                if (!line.startsWith("import com.huanchengfly.tieba.post.")) return@forEachIndexed
                                val imported = line.removePrefix("import ").trim().removeSuffix(";")
                                val ok = imported.startsWith("com.huanchengfly.tieba.post.api.") ||
                                    imported.startsWith("com.huanchengfly.tieba.post.core.")
                                if (!ok) {
                                    violations += "${file.rel(root)}:${index + 1} api 树不得依赖 app 私有代码：$imported"
                                }
                            }
                        }
                    }
            }
        if (scannedFiles < 100) {
            violations += "api 树只扫到 $scannedFiles 个文件（预期 ≥100）——扫描口径失效，本例是假绿"
        }
        return violations
    }

    /** 该模块 `mipmap-anydpi-v26/v31` 里以 `<adaptive-icon>` 为根的图标名 */
    private fun adaptiveIconNames(module: Module): Set<String> {
        val resDir = File(module.dir, "src/main/res")
        if (!resDir.isDirectory) return emptySet()
        return resDir.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap-anydpi") }
            .orEmpty()
            .flatMap { dir ->
                dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().mapNotNull { xml ->
                    val root = xml.useLines { lines ->
                        lines.firstOrNull { it.contains("<adaptive-icon") }
                    }
                    if (root != null) xml.name.removeSuffix(".xml") else null
                }
            }
            .toSet()
    }

    /**
     * 校验器自己的守卫：用临时夹具证明违规确实会被抓到（且干净夹具不会误报）。
     * 没有这条，"规则全绿"既可能是真的干净，也可能是校验器根本没在工作。
     */
    @Test
    fun checkerDetectsViolationsOnFixture() {
        val fixture = createFixture()
        try {
            val leaky = File(fixture, "core/x/src/main/kotlin/com/huanchengfly/tieba/post/core/x/Leaky.kt")
            val iconUser = File(fixture, "app/src/main/java/com/huanchengfly/tieba/post/IconUser.kt")

            val depViolations = dependencyViolations(fixture)
            assertTrue(
                "夹具里 core:x 引用了 app 的类，校验器却什么都没报：$depViolations",
                depViolations.any { it.contains("不得依赖 app") },
            )
            assertTrue(
                "夹具里 core:x 的 build.gradle.kts 依赖 :app，校验器却没报",
                buildFileViolations(fixture).isNotEmpty(),
            )
            assertTrue(
                "夹具里用 painterResource 引用了自适应图标，校验器却没报",
                adaptiveIconViolations(fixture).any { it.contains("adaptive-icon") },
            )

            // 把违规清掉后必须不再报（证明不是"无脑报错"）
            write(
                leaky,
                """
                package com.huanchengfly.tieba.post.core.x

                class Leaky
                """.trimIndent(),
            )
            write(
                iconUser,
                """
                package com.huanchengfly.tieba.post

                import androidx.compose.ui.res.painterResource

                val icon = painterResource(R.drawable.ic_x)
                """.trimIndent(),
            )
            assertEquals(
                "夹具已修干净，依赖规则仍在报（误报）：${dependencyViolations(fixture)}",
                emptyList<String>(),
                dependencyViolations(fixture),
            )
            assertEquals(
                "夹具已修干净，图标规则仍在报（误报）：${adaptiveIconViolations(fixture)}",
                emptyList<String>(),
                adaptiveIconViolations(fixture),
            )
        } finally {
            fixture.deleteRecursively()
        }
    }

    // ── 规则实现 ──────────────────────────────────────────────────────────

    private data class Module(
        /** Gradle 规范路径，如 `:core:common`（报告里用它，便于对照 settings.gradle.kts） */
        val gradlePath: String,
        val dir: File,
        /** 模块 src 下真实声明的 package（做前缀匹配时取最长命中） */
        val declaredPackages: Set<String>,
        /** `build.gradle.kts` 里 `namespace = "..."` 声明的命名空间（覆盖 generated 产物所在包） */
        val namespaces: Set<String>,
        val sourceFiles: List<File>,
    ) {
        val isCore: Boolean get() = gradlePath.startsWith(":core:")

        fun covers(imported: String): Boolean =
            namespaces.any { imported == it || imported.startsWith("$it.") }
    }

    private fun dependencyViolations(root: File): List<String> {
        val modules = scanModules(root)
        val owners = packageOwners(modules)
        val violations = mutableListOf<String>()

        modules.filter { it.isCore }.forEach { module ->
            module.sourceFiles.forEach { file ->
                importsOf(file).forEach { imported ->
                    val (owner, matched) = ownerOf(imported, owners)
                    when {
                        owner == module || module.covers(imported) -> Unit

                        owner == null -> violations +=
                            "${module.gradlePath} 依赖了归属未知的包（不在任何模块 src 的 package 声明中，" +
                                "也不在本模块 namespace 内）：$imported  ← ${file.rel(root)}"

                        owner.isCore && owner != module -> {
                            // 三规则之一：core 之间只允许依赖 core:common。
                            // （2026-09-17 3b-move 修正：原实现把 →core:common 的合法依赖
                            // 也一并报红，与本测试 KDoc 声明的口径相悖——core:network
                            // 落地才暴露。方向不变：仍是收紧依赖，只是按注释口径放行 common。）
                            if (owner.gradlePath != ":core:common") {
                                violations += "${module.gradlePath} 只能依赖 core:common，却引用了 " +
                                    "${owner.gradlePath} 的 $imported（命中包 $matched）  ← ${file.rel(root)}"
                            }
                        }

                        !owner.isCore ->
                            violations += "${module.gradlePath} 不得依赖 app 侧代码：$imported" +
                                "（命中 ${owner.gradlePath} 的包 $matched）  ← ${file.rel(root)}"
                    }
                }
            }
        }
        return violations
    }

    private fun namespaceViolations(root: File): List<String> =
        scanModules(root).filter { it.isCore }.flatMap { module ->
            module.declaredPackages
                .filterNot { it.startsWith("com.huanchengfly.tieba.post") }
                .map { "${module.gradlePath} 声明了不在本项目包名下的包：$it" }
        }

    private fun buildFileViolations(root: File): List<String> =
        scanModules(root).filter { it.isCore }.flatMap { module ->
            val buildFile = File(module.dir, "build.gradle.kts")
            if (!buildFile.isFile) return@flatMap emptyList()
            buildFile.readLines().withIndex()
                .filter { (_, line) -> APP_PROJECT_DEPENDENCY.containsMatchIn(line) }
                .map { (i, line) ->
                    "${module.gradlePath}/build.gradle.kts:${i + 1} core 不得依赖 app：${line.trim()}"
                }
        }

    // ── 扫描与归属 ────────────────────────────────────────────────────────

    private fun scanModules(root: File): List<Module> {
        val settings = File(root, "settings.gradle.kts")
        if (!settings.isFile) return emptyList()
        return Regex("""include\s*\(\s*["'](:[^"']+)["']""")
            .findAll(settings.readText())
            .map { moduleOf(root, it.groupValues[1]) }
            .filter { it.dir.isDirectory }
            .toList()
    }

    private fun moduleOf(root: File, gradlePath: String): Module {
        val dir = File(root, gradlePath.removePrefix(":").replace(':', '/'))
        val sourceFiles = dir.resolve("src").walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .toList()
        val namespaces = File(dir, "build.gradle.kts")
            .takeIf { it.isFile }
            ?.let { f ->
                Regex("""namespace\s*=\s*["']([^"']+)["']""").findAll(f.readText())
                    .map { it.groupValues[1] }
                    .toSet()
            }
            ?: emptySet()
        return Module(
            gradlePath = gradlePath,
            dir = dir,
            declaredPackages = sourceFiles.mapNotNull { pkgOf(it) }.toSet(),
            namespaces = namespaces,
            sourceFiles = sourceFiles,
        )
    }

    private fun packageOwners(modules: List<Module>): Map<String, Set<Module>> {
        val map = mutableMapOf<String, MutableSet<Module>>()
        modules.forEach { module ->
            module.declaredPackages.forEach { pkg -> map.getOrPut(pkg) { mutableSetOf() } += module }
        }
        return map
    }

    /** 归属判定：src 里声明的 package 取最长命中；迁移期同包可能同时在 app 与 core，优先判给 core */
    private fun ownerOf(
        imported: String,
        owners: Map<String, Set<Module>>,
    ): Pair<Module?, String?> {
        val hit = owners.keys
            .filter { imported == it || imported.startsWith("$it.") }
            .maxByOrNull { it.length }
            ?: return null to null
        val candidates = owners.getValue(hit)
        return (candidates.firstOrNull { it.isCore } ?: candidates.first()) to hit
    }

    private fun importsOf(file: File): List<String> = file.useLines { lines ->
        lines.filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim().removeSuffix(";") }
            .filter { it.startsWith("com.huanchengfly.tieba.post.") }
            .map { it.removeSuffix(".*") }
            .toList()
    }

    private fun pkgOf(file: File): String? = file.useLines { lines ->
        lines.take(30).firstOrNull { it.startsWith("package ") }
            ?.removePrefix("package ")?.trim()?.removeSuffix(";")
    }

    private fun File.rel(root: File): String =
        absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)

    // ── 夹具与报告 ────────────────────────────────────────────────────────

    private fun createFixture(): File {
        val dir = Files.createTempDirectory("tblite-archrules-fixture").toFile()
        write(
            File(dir, "settings.gradle.kts"),
            """
            include(":app")
            include(":core:x")
            """.trimIndent(),
        )
        write(
            File(dir, "app/build.gradle.kts"),
            """android { namespace = "com.huanchengfly.tieba.post" }""",
        )
        write(
            File(dir, "app/src/main/java/com/huanchengfly/tieba/post/App.kt"),
            """
            package com.huanchengfly.tieba.post

            class App
            """.trimIndent(),
        )
        write(
            File(dir, "core/x/build.gradle.kts"),
            """
            namespace = "com.huanchengfly.tieba.post.core.x"
            dependencies {
                implementation(project(":app"))
            }
            """.trimIndent(),
        )
        write(
            File(dir, "core/x/src/main/kotlin/com/huanchengfly/tieba/post/core/x/Leaky.kt"),
            """
            package com.huanchengfly.tieba.post.core.x

            import com.huanchengfly.tieba.post.App

            class Leaky(val app: App)
            """.trimIndent(),
        )
        // 自适应图标 + 拿它喂 painterResource 的调用点（复现"点关于崩溃"那类写法）
        write(
            File(dir, "app/src/main/res/mipmap-anydpi-v26/ic_x.xml"),
            """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@color/ic_x_background"/>
                <foreground android:drawable="@drawable/ic_x_foreground"/>
            </adaptive-icon>
            """.trimIndent(),
        )
        write(
            File(dir, "app/src/main/java/com/huanchengfly/tieba/post/IconUser.kt"),
            """
            package com.huanchengfly.tieba.post

            import androidx.compose.ui.res.painterResource

            val icon = painterResource(R.mipmap.ic_x)
            """.trimIndent(),
        )
        return dir
    }

    private fun write(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun report(violations: List<String>, what: String) {
        if (violations.isEmpty()) return
        val shown = violations.take(25).joinToString("\n  ")
        val more = if (violations.size > 25) "\n  …（共 ${violations.size} 处，只列前 25）" else ""
        throw AssertionError(
            "发现 $what ${violations.size} 处：\n  $shown$more\n" +
                "规则见进度交接 §8.1；包归属按各模块 src 里的 package 声明 + build.gradle.kts 的 " +
                "namespace 推导。要放行请改依赖方向，不要改这个测试。",
        )
    }

    private companion object {
        val APP_PROJECT_DEPENDENCY = Regex(
            """project\s*\(\s*(path\s*=\s*)?["']:app["']|projects\.app\b""",
        )

        /** 抓 `painterResource(R.mipmap.xxx)` / `painterResource(id = R.mipmap.xxx)` */
        val PAINTER_RESOURCE = Regex("""painterResource\s*\(\s*(id\s*=\s*)?R\.mipmap\.(\w+)""")

        fun findRepoRoot(): File {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            while (true) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
                    ?: error("从 ${System.getProperty("user.dir")} 向上找不到 settings.gradle.kts")
            }
        }
    }
}
