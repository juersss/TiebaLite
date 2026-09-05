package com.huanchengfly.tieba.post.api

import com.huanchengfly.tieba.post.api.models.AgreeBean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * opAgree 响应日志（调试用，进程内存活，不落盘）。
 *
 * 背景：差分模型的显示 = 服务端基准 + delta(my) − delta(server)，当用户反馈
 * "切换赞踩后刷新回弹"时，需要知道**操作那一刻服务端说了什么**：
 * error_code 是否真的为 0、data.agree.score（服务端操作后的权威计数）是多少。
 * 该响应此前在 .map 里被整个丢弃。pb 刷新接口的回显与 opAgree 响应对不上时，
 * 这份日志就是区分"服务端没记账"还是"刷新接口滞后"的证据。
 *
 * key 与 UI 诊断的 (objType, objId) 对齐：OBJ_THREAD 用 threadId，
 * OBJ_POST/OBJ_SUB_POST 用请求的 postId 参数。
 */
object OpResponseLog {
    /** 每个对象保留最近几条 */
    private const val MAX_PER_OBJECT = 8

    /** 最多跟踪多少个对象（防无界增长） */
    private const val MAX_TRACKED_OBJECTS = 200

    data class Entry(
        val time: String,
        val agreeType: Int,
        val opType: Int,
        val errorCode: String?,
        val errorMsg: String?,
        /** 服务端返回的操作后计数（data.agree.score），可能为 null（服务端没下发） */
        val score: String?,
    ) {
        fun describe(objType: Int): String = buildString {
            append(time)
            append("  agreeType=").append(agreeType)
            append(
                when (agreeType) {
                    AgreeParams.TYPE_AGREE -> "(赞)"
                    AgreeParams.TYPE_DISAGREE -> "(踩)"
                    else -> ""
                }
            )
            append(" op=").append(if (opType == AgreeParams.OP_DO) "执行" else "撤销")
            append("  objType=").append(objType)
            append("  error_code=").append(errorCode ?: "null")
            if (!errorMsg.isNullOrBlank()) append(" msg=").append(errorMsg)
            append("  score=").append(score ?: "无")
        }
    }

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val entries = LinkedHashMap<String, ArrayDeque<Entry>>()

    fun record(
        objType: Int,
        objId: Long,
        agreeType: Int,
        opType: Int,
        bean: AgreeBean,
    ) = enqueue(
        objType, objId,
        Entry(
            time = timeFormat.format(Date()),
            agreeType = agreeType,
            opType = opType,
            errorCode = bean.errorCode,
            errorMsg = bean.errorMsg,
            score = bean.data?.agree?.score,
        )
    )

    /**
     * 异常路径的失败响应（error_code 为数字时由 FailureResponseInterceptor 抛出，
     * .map/onEach 都不会执行）。真机实证：取消踩被拒（ERR_USER_NOT_DISAGREE）
     * 走的正是这条路——不记录的话，用户看到的 toast 在诊断里是隐形的。
     */
    fun recordFailure(
        objType: Int,
        objId: Long,
        agreeType: Int,
        opType: Int,
        errorCode: String?,
        errorMsg: String?,
    ) = enqueue(
        objType, objId,
        Entry(
            time = timeFormat.format(Date()),
            agreeType = agreeType,
            opType = opType,
            errorCode = errorCode,
            errorMsg = errorMsg,
            score = null,
        )
    )

    private fun enqueue(objType: Int, objId: Long, entry: Entry) {
        synchronized(lock) {
            val key = "${objType}_$objId"
            val deque = entries.getOrPut(key) { ArrayDeque() }
            if (deque.size >= MAX_PER_OBJECT) deque.removeFirst()
            deque.addLast(entry)
            if (entries.size > MAX_TRACKED_OBJECTS) {
                // LinkedHashMap 插入序，最早写入的对象整组丢弃
                val oldest = entries.keys.first()
                entries.remove(oldest)
            }
        }
    }

    /** 该对象最近的响应，旧→新 */
    fun recent(objType: Int, objId: Long): List<Entry> = synchronized(lock) {
        entries["${objType}_$objId"]?.toList() ?: emptyList()
    }

    /** 仅供单测 */
    internal fun resetForTest() = synchronized(lock) {
        entries.clear()
    }
}
