package com.huanchengfly.tieba.post.api.models.protos

import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.models.AgreeBean

/**
 * 我当前对一条内容的态度。三者互斥，不存在"既赞又踩"。
 *
 * 背景：此前 UI 分别用两个独立布尔（hasAgree / hasDisagree）判断图标点亮，
 * 彼此没有互斥约束，导致踩与赞的图标可能同时亮起。改用单一三态后，
 * UI 只从这里派生两个布尔，结构上杜绝双亮。
 */
enum class MyAgreeOp { NONE, AGREE, DISAGREE }

/**
 * 单个对象赞踩记录:my 是用户当前意图(含乐观未确认),server 是服务端最后一次确认。
 * 显示计数 = 服务端基准 diffAgreeNum + [displayDelta],差分模型不改动计数字段本身。
 */
data class OpRecord(
    val my: MyAgreeOp = MyAgreeOp.NONE,
    val server: MyAgreeOp = MyAgreeOp.NONE,
)

fun opCountDelta(op: MyAgreeOp): Long = when (op) {
    MyAgreeOp.AGREE -> 1L
    MyAgreeOp.DISAGREE -> -1L
    MyAgreeOp.NONE -> 0L
}

fun OpRecord.displayDelta(): Long = opCountDelta(my) - opCountDelta(server)

/**
 * 列表卡"赞数"专用的差分。列表卡(FeedCard.ThreadAgreeBtn)的计数基准是原始 agreeNum
 * (赞数)而非帖子页的 diffAgreeNum(赞−踩),踩轴变化不得影响赞数——否则帖子页在途的踩
 * 会让列表卡数字凭空 −1、赞踩切换态凭空 +2。只按赞轴计算乐观偏移。
 */
fun OpRecord.agreeCountDelta(): Long =
    (if (my == MyAgreeOp.AGREE) 1L else 0L) - (if (server == MyAgreeOp.AGREE) 1L else 0L)

/** 请求失败/被拒:回退未确认的乐观意图 */
fun OpRecord.reverted(): OpRecord = copy(my = server)

/**
 * 从服务端 agree 回显推断"我"的状态,**仅用于给无记录的对象播种初始记录**
 * (历史点赞/其他端点的点赞恢复)。已有记录的对象一律以本地记录为准。
 *
 * 播种源只认 agreeType:**不使用 hasAgree**。has_agree 会被回显成 1
 * (疑似"有过操作"的语义,见 [OpRecordStore] 文档注释),拿它播种会把从未
 * 点赞的帖子判成 AGREE;而播种后紧接着的 confirm 会把 my=server=AGREE 落盘,
 * 此后每次 rebase 都只做 srv=my、无法纠正,错误状态被永久保留。
 * 漏播种的代价只是 UI 暂时不亮(用户下一次操作即可补齐),远比误点亮安全。
 */
fun Agree?.serverEchoOp(): MyAgreeOp = when {
    this == null -> MyAgreeOp.NONE
    agreeType == AgreeParams.TYPE_DISAGREE -> MyAgreeOp.DISAGREE
    else -> MyAgreeOp.NONE
}

/**
 * /c/c/agree/opAgree 的服务端错误码。
 *
 * 此前 AgreeBean.errorCode 全项目零读取，任何响应都被当成成功，
 * 服务端拒绝的请求客户端感知不到，本地与服务端状态持续漂移。
 */
object AgreeErrorCodes {
    const val OK = "0"

    /** 你已取消倒赞（即当前不在踩状态） */
    const val ERR_USER_HAS_CANCEL_DISAGREE = "ERR_USER_HAS_CANCEL_DISAGREE"

    /** 你已赞过 */
    const val ERR_USER_HAS_AGREED = "ERR_USER_HAS_AGREED"

    /** 你没赞过（试图取消一个不存在的赞） */
    const val ERR_USER_HAS_NO_AGREE = "ERR_USER_HAS_NO_AGREE"
}

/**
 * 服务端明确陈述"你当前处于（或不在）某个状态"的反馈。
 * 收到这些反馈时应当无条件信任服务端，把本地状态对齐过去。
 *
 * 用模糊匹配而非精确码表：同一个语义（如"已踩过"）在不同端点/版本下的
 * 字符串码拼写不一致，精确匹配会漏——漏掉的会走"普通失败→回滚"，
 * 把本应采纳的服务端陈述当成请求失败，造成状态与计数漂移。
 *
 * 输入有两类来源，因此英文与中文都要匹配：
 * - 路径 A：[AgreeBean.errorCode]（error_code 为字符串时），取值为英文码，如
 *   "ERR_USER_HAS_AGREED"，下面的英文分支即服务于此，勿删。
 * - 路径 B：error_code 为数字时由 FailureResponseInterceptor 抛出
 *   [com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException]，
 *   message 取自 CommonResponse.errorMsg，是**中文**。此前只有英文分支，
 *   该路径恒返回 null，整个机制形同虚设，故补充下列中文模式。
 *
 * 中文部分属于**启发式匹配**：贴吧未公开错误码表，只能按 error_msg 字面反推。
 * 匹配不上仍返回 null（走通用失败→回滚），无回归风险。
 * TODO：埋点采集真实 error_code / error_msg 后收敛为精确码表，移除这里的中文猜测。
 *
 * 顺序要点：先判"取消 / 否定"语义（已取消赞、未赞、NO_AGREE），再判
 * HAS_AGREE / 已赞类。顺序颠倒会因子串包含而误判（如"未赞过"含"赞过"）。
 * 英文分支内 HAS_AGREED 同样含 HAS_AGREE，现有先后顺序不可调整。
 */
private fun authoritativeOpFromRaw(raw: String?): MyAgreeOp? {
    if (raw.isNullOrBlank()) return null
    // 中文字符不受 uppercase 影响,英文与中文统一在 s 上做子串匹配
    val s = raw.uppercase()
    return when {
        // 你已取消踩 / 你已取消赞:当前既不在赞也不在踩
        "CANCEL_DISAGREE" in s || "已取消踩" in s || "已取消赞" in s -> MyAgreeOp.NONE

        // 你当前不在踩/赞状态(试图撤销一个服务端没记的操作)。
        // 真机实证:主帖"点踩"服务端回 error_code=0 却不记账,随后的"取消踩"
        // 便收到 ERR_USER_NOT_DISAGREE——这是权威陈述,必须对齐 NONE 而非回滚,
        // 否则用户永远"取消不掉"(回滚=把图标弹回点亮)。
        "NOT_DISAGREE" in s || "NOT_AGREE" in s || "NOT_AGREED" in s ||
            "未踩" in s || "没有踩" in s || "还没踩" in s -> MyAgreeOp.NONE

        // 你没赞过(试图取消一个不存在的赞)
        "NO_AGREE" in s || "未赞" in s || "未点赞" in s ||
            "没有赞" in s || "还没赞" in s -> MyAgreeOp.NONE

        // 你已踩过(重复点踩):服务端已记录踩,本地对齐为踩
        "HAS_DISAGREE" in s || "HAS_DISAGREED" in s || "ALREADY_DISAGREE" in s ||
            "已踩过" in s || "已经踩过" in s || "已点踩" in s || "已经点踩" in s -> MyAgreeOp.DISAGREE

        // 你已赞过
        "HAS_AGREE" in s || "ALREADY_AGREE" in s || "HAS_AGREED" in s ||
            "已赞过" in s || "已经赞过" in s || "已点赞" in s || "已经点赞" in s -> MyAgreeOp.AGREE

        else -> null
    }
}

sealed interface OpAgreeResult {
    val objId: Long

    /** 服务端接受 */
    data class Ok(override val objId: Long, val disagree: Boolean) : OpAgreeResult

    /** 服务端权威拒绝：本地状态须无条件对齐服务端 */
    data class Authoritative(override val objId: Long, val code: String, val msg: String) :
        OpAgreeResult

    /** 其他业务错误：toast + 局部回滚 */
    data class Business(override val objId: Long, val code: String, val msg: String) : OpAgreeResult
}

/**
 * 把 AgreeBean 的 errorCode 映射为三态结果。
 * 修复前这里无脑返回 Success，是状态错乱 bug 的直接根因。
 */
fun AgreeBean.toOpAgreeResult(objId: Long, intendedDisagree: Boolean): OpAgreeResult = when {
    errorCode == null || errorCode == AgreeErrorCodes.OK ->
        OpAgreeResult.Ok(objId, intendedDisagree)

    authoritativeOpFromRaw(errorCode) != null ->
        OpAgreeResult.Authoritative(objId, errorCode!!, errorMsg.orEmpty())

    else ->
        OpAgreeResult.Business(objId, errorCode!!, errorMsg.orEmpty())
}

/**
 * 按服务端 error_msg 反推"我"当前处于什么状态。
 *
 * 必要性：错误可能通过两条路径到达——
 *   1. AgreeBean.errorCode 字段（error_code 为字符串时，拦截器解析 Int 失败、不抛异常）
 *   2. FailureResponseInterceptor 抛出的 TiebaApiException（error_code 为数字时）
 * 第 2 条路径下 .map 根本不会被调用，只能在 .catch 里依据 message 判断。
 * 返回 null 表示无法识别，按通用失败处理。
 */
fun serverOpFromErrorMessage(msg: String?): MyAgreeOp? = authoritativeOpFromRaw(msg)

/**
 * 由错误码反推服务端认为"我"处于什么状态。
 * 无法识别时保守返回 NONE（即"未赞未踩"）。
 */
fun serverOpFromErrorCode(code: String?): MyAgreeOp = authoritativeOpFromRaw(code) ?: MyAgreeOp.NONE
