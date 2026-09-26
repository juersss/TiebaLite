package com.huanchengfly.tieba.post.selfcheck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一键自检面板（**仅 debug 版存在**）。
 *
 * 启动方式：
 * ```
 * adb shell am start -n com.huanchengfly.tieba.post/.selfcheck.SelfCheckActivity
 * ```
 *
 * 为什么做这个：只靠"打开主页看看有没有崩"验证不了功能好坏——请求参数被改坏、
 * 页面能开但内容为空、交互点不动，都躲得过这种冒烟。自检面板把关键不变量变成
 * 可一键运行的断言，且跑在真实设备上，能碰到单测碰不到的数据层与链路。
 */
class SelfCheckActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SelfCheckScreen() }
    }
}

private data class CaseState(
    val case: SelfCheckCase,
    val status: Status,
    val detail: String = "",
    val durationMs: Long = 0L,
) {
    enum class Status { PENDING, RUNNING, PASSED, FAILED, SKIPPED }
}

@Composable
private fun SelfCheckScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states = remember {
        mutableStateListOf<CaseState>().apply {
            defaultSelfCheckCases.forEach { add(CaseState(it, CaseState.Status.PENDING)) }
        }
    }
    var running by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf("") }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (running) return@Button
                        running = true
                        scope.launch {
                            runAll(context, states) { running = false; summary = it }
                        }
                    }, enabled = !running) {
                        Text(if (running) "运行中…" else "一键跑全部")
                    }
                    Button(onClick = { context.copyToClipboard(reportOf(states)) }) {
                        Text("复制报告")
                    }
                }

                if (running) {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp))
                }
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    states.forEachIndexed { index, state ->
                        CaseRow(state)
                        if (index != states.lastIndex) {
                            Text(
                                "",
                                fontSize = 2.sp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaseRow(state: CaseState) {
    val (mark, color) = when (state.status) {
        CaseState.Status.PASSED -> "✅" to Color(0xFF1B7F3B)
        CaseState.Status.FAILED -> "❌" to Color(0xFFC62828)
        CaseState.Status.SKIPPED -> "⏭️" to Color(0xFF8A6D00)
        CaseState.Status.RUNNING -> "⏳" to Color.Gray
        CaseState.Status.PENDING -> "•" to Color.Gray
    }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            "$mark ${state.case.group} · ${state.case.name}" +
                if (state.durationMs > 0) "  (${state.durationMs}ms)" else "",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        if (state.detail.isNotEmpty()) {
            Text(
                state.detail,
                fontSize = 12.sp,
                color = color,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
    }
}

private suspend fun runAll(
    context: Context,
    states: MutableList<CaseState>,
    onDone: (String) -> Unit,
) = withContext(Dispatchers.Default) {
    // 每次运行重置只读缓存与生命周期探针：上一次的数据/已 resumed 记录不能给这一次判绿
    ProbeData.reset()
    NavProbe.install(context)

    var passed = 0
    var failed = 0
    var skipped = 0

    for (i in states.indices) {
        val case = states[i].case
        states[i] = CaseState(case, CaseState.Status.RUNNING)

        if (case.requiresLogin && !isLoggedIn()) {
            states[i] = CaseState(case, CaseState.Status.SKIPPED, "需要登录态")
            skipped++
            continue
        }

        val started = System.currentTimeMillis()
        val result = runCatching { case.check(context) }
        val elapsed = System.currentTimeMillis() - started

        when (val outcome = result.getOrElse { it.toFailureReason() }) {
            is SelfCheckOutcome.Passed -> {
                states[i] = CaseState(case, CaseState.Status.PASSED, "", elapsed)
                passed++
            }

            is SelfCheckOutcome.Skipped -> {
                states[i] = CaseState(case, CaseState.Status.SKIPPED, outcome.reason, elapsed)
                skipped++
            }

            is SelfCheckOutcome.Failed -> {
                states[i] = CaseState(case, CaseState.Status.FAILED, outcome.reason, elapsed)
                failed++
            }
        }
    }
    onDone("通过 $passed · 失败 $failed · 跳过 $skipped")
}

/**
 * 异常 → 失败原因。断言失败只报断言消息（前缀"断言失败"反而是噪音）；
 * 其它异常说明用例自己炸了，必须与断言失败区分开，否则会被当成"实现有问题"误判。
 */
private fun Throwable.toFailureReason(): SelfCheckOutcome.Failed =
    if (this is AssertionError) {
        SelfCheckOutcome.Failed(message ?: "断言失败（无消息）")
    } else {
        SelfCheckOutcome.Failed("用例崩溃 —— ${shortMessage()}")
    }


private fun reportOf(states: List<CaseState>): String = buildString {
    appendLine("TiebaLite 自检报告 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
    states.forEach {
        appendLine("${it.status} | ${it.case.group} · ${it.case.name} | ${it.durationMs}ms | ${it.detail}")
    }
}

private fun Context.copyToClipboard(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("selfcheck", text))
}
