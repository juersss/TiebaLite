package com.huanchengfly.tieba.post.ui.widgets.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.OpResponseLog
import com.huanchengfly.tieba.post.api.models.protos.Agree
import com.huanchengfly.tieba.post.api.models.protos.MyAgreeOp
import com.huanchengfly.tieba.post.api.models.protos.OpRecord
import com.huanchengfly.tieba.post.api.models.protos.displayDelta
import com.huanchengfly.tieba.post.api.models.protos.opCountDelta
import com.huanchengfly.tieba.post.api.models.protos.serverEchoOp

/**
 * 赞踩诊断（调试模式）。
 *
 * 差分模型的界面只显示一个数字，排查"计数不对/图标不亮"时看不到背后的账本。
 * 调试模式开启后长按赞/踩按钮，弹出该对象的完整诊断：
 * 服务端回显原始字段、本地 OpRecord（my/server）、差值计算过程、最终显示值，
 * 支持一键复制，便于反馈问题时直接贴出。
 */
data class AgreeDebugInfo(
    val objType: Int,
    val objId: Long,
    val serverAgree: Agree?,
    val record: OpRecord,
    val hasRecord: Boolean,
) {
    fun buildText(): String = buildString {
        appendLine("【赞踩诊断】${objTypeName(objType)} id=$objId")
        appendLine("── 服务端回显（只提供计数基准）──")
        val a = serverAgree
        if (a == null) {
            appendLine("agree = null（本楼层无回显）")
        } else {
            appendLine("diffAgreeNum(基准) = ${a.diffAgreeNum}")
            appendLine("agreeNum(赞数) = ${a.agreeNum}")
            appendLine("disagreeNum(踩数) = ${a.disagreeNum}")
            appendLine("agreeType = ${a.agreeType}（5=踩）")
            appendLine("hasAgree = ${a.hasAgree}（已知不可靠，不参与判断）")
            appendLine("回显推断 serverEchoOp = ${a.serverEchoOp()}")
        }
        appendLine("── 本地记录 OpRecord ───")
        if (!hasRecord) {
            appendLine("无记录（显示 = 基准，图标不亮）")
        } else {
            appendLine("my(意图) = ${record.my}")
            appendLine("server(对齐标记) = ${record.server}")
            appendLine("delta(my) = ${opCountDelta(record.my)}  delta(server) = ${opCountDelta(record.server)}")
            appendLine("displayDelta = ${record.displayDelta()}")
        }
        appendLine("── 最终显示 ───────────")
        val base = a?.diffAgreeNum ?: 0L
        appendLine("显示计数 = 基准($base) + displayDelta(${record.displayDelta()}) = ${base + record.displayDelta()}")
        appendLine("赞亮 = ${record.my == MyAgreeOp.AGREE}  踩亮 = ${record.my == MyAgreeOp.DISAGREE}")
        // 操作响应日志：排查"切换后刷新回弹"的关键证据——
        // score 是服务端操作后报的权威计数。score 与刷新后基准对不上 =
        // 服务端记账问题/刷新接口滞后；score 与基准一致但态度回显矛盾 = 同上。
        val log = OpResponseLog.recent(objType, objId)
        appendLine("── 最近操作响应（opAgree，旧→新）──")
        if (log.isEmpty()) {
            appendLine("无（本次进程内未对该对象操作过）")
        } else {
            log.forEach { appendLine(it.describe(objType)) }
        }
        appendLine("App 版本: ${com.huanchengfly.tieba.post.BuildConfig.VERSION_NAME}")
    }
}

fun objTypeName(objType: Int): String = when (objType) {
    AgreeParams.OBJ_THREAD -> "主帖(OBJ_THREAD)"
    AgreeParams.OBJ_POST -> "楼层(OBJ_POST)"
    AgreeParams.OBJ_SUB_POST -> "楼中楼(OBJ_SUB_POST)"
    else -> "objType=$objType"
}

@Composable
fun AgreeDebugDialog(
    info: AgreeDebugInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val text = info.buildText()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.title_agree_debug)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = text, style = MaterialTheme.typography.caption)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, text)
            }) {
                Text(stringResource(id = R.string.btn_agree_debug_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.button_cancel))
            }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("agree_debug", text))
}
