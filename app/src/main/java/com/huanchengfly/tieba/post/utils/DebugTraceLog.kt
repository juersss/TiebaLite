package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.os.Build
import com.huanchengfly.tieba.post.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Debug 构建专用的全量追踪日志(定位"吧内浏览进度偶尔回退"这类偶现问题)。
 *
 * 仅在 `BuildConfig.DEBUG` 构建里由 [install] 激活;Release 构建中 [log] 等
 * 调用点在 enabled=false 下第一条语句即返回,零 IO、零格式化开销。
 *
 * 设计目标:偶现问题不需要"复现前先打开记录"——所有埋点条目**实时追加**到
 * 会话日志文件,复现后直接取文件即可看到回退发生前后的完整上下文:
 *
 * - 进度记录:吧列表滚动位置(首可见项索引/偏移/锚点键)、列表数据变更
 *   (整表替换/保位合并/翻页的尺寸与首尾帖子 id)、导航进出、Activity 生命周期、
 *   回顶指令等关键事件全部带毫秒级时间戳落盘;
 * - 完整日志:文件位于应用外部私有目录 `logs/`(无需存储权限),
 *   `Android/data/<包名>/files/logs/trace_*.log`,按进程会话分文件,
 *   单文件超限自动轮转,默认只保留最新 [DEFAULT_KEEP_FILES] 份。
 *
 * 实现说明:内存环保留最近 [MAX_MEMORY_ENTRIES] 条([snapshot] 可整体导出);
 * 所有文件操作串行在单一后台线程,写入失败静默降级(仅 printStackTrace),
 * 绝不影响主流程——它只是诊断探针,不能成为新的故障源。
 */
object DebugTraceLog {
    private const val LOG_DIR = "logs"
    private const val MEMORY_LOG_TAG = "TRACE"
    internal const val MAX_MEMORY_ENTRIES = 6000
    internal const val DEFAULT_MAX_FILE_BYTES = 8L * 1024 * 1024
    internal const val DEFAULT_KEEP_FILES = 4

    @Volatile
    private var enabled = false

    /** 钩子层据此零成本直通(DebugTraceHooks 内部查询) */
    internal val isActive: Boolean get() = enabled

    private val ring = ArrayDeque<String>(MAX_MEMORY_ENTRIES)

    /** 全部文件读写串行在这一条线程上:调用方(含主线程)只做格式化与入队。
     * 守护线程:进程退出不被日志线程阻塞(JVM 单测环境同样需要) */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "debug-trace-log").apply { isDaemon = true }
    }

    // 仅在 executor 线程上访问
    private var writer: OutputStreamWriter? = null
    private var currentFile: File? = null
    private var currentFileBytes: Long = 0
    private var maxFileBytes = DEFAULT_MAX_FILE_BYTES
    private var keepFiles = DEFAULT_KEEP_FILES
    private var logsDir: File? = null

    /** Debug 构建在 Application.onCreate 调用一次;Release 不调用 */
    fun install(context: Context) {
        if (!BuildConfig.DEBUG) return
        val dir = context.getExternalFilesDir(LOG_DIR) ?: File(context.filesDir, LOG_DIR)
        val header = "v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
            "${Build.MODEL} API${Build.VERSION.SDK_INT} pid=${android.os.Process.myPid()}"
        start(dir, header)
    }

    /** 拆出可注入目录的核心,便于纯 JVM 单测 */
    internal fun start(dir: File, header: String) {
        enabled = true
        logsDir = dir
        executor.execute {
            runCatching { dir.mkdirs() }
        }
        log(MEMORY_LOG_TAG, "session start: $header")
    }

    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = formatLine(tag, message)
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > MAX_MEMORY_ENTRIES) ring.removeFirst()
        }
        executor.execute {
            runCatching { appendLineLocked(line) }
                .onFailure { it.printStackTrace() }
        }
    }

    /** 最近 [MAX_MEMORY_ENTRIES] 条的内存快照(时间正序) */
    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }

    fun sessionFile(): File? = currentFile

    private fun formatLine(tag: String, message: String): String {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val thread = Thread.currentThread().name.substringBefore('@')
        return "$ts [$thread] $tag: $message"
    }

    /** 仅在 executor 线程调用 */
    private fun appendLineLocked(line: String) {
        if (writer == null || currentFileBytes >= maxFileBytes) {
            rotateLocked()
        }
        val w = writer ?: return
        w.write(line)
        w.write("\n")
        w.flush()
        currentFileBytes += line.length + 1
    }

    /** 仅在 executor 线程调用 */
    private fun rotateLocked() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        val dir = logsDir ?: return
        val fileName = "trace_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".log"
        val file = File(dir, fileName)
        writer = OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8)
        currentFile = file
        currentFileBytes = file.length()
        // 清理超出保留数量的旧文件(按修改时间,留最新的 keepFiles-1 份历史)
        runCatching {
            dir.listFiles { f -> f.name.startsWith("trace_") && f.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keepFiles)
                ?.forEach { it.delete() }
        }
    }
}
