package com.huanchengfly.tieba.post.api.models.protos

import com.google.gson.Gson
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.models.AgreeBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 赞踩差分计数模型的纯函数单测。
 *
 * 这些函数无 Android 依赖（[Agree] 是 Wire 生成的纯 Kotlin 类、[AgreeBean] 用 Gson 构造），
 * 因此放在 JVM 单测里即可，不需要 Robolectric 或 instrumentation。
 *
 * 覆盖三类内容：
 * 1. 差分数学本身（displayDelta / reverted）——模型正确性的地基；
 * 2. 播种与权威匹配的回归测试（对应修复 §3.7 / §3.6）；
 * 3. 服务端结果三分类（对应修复 §3.5）。
 *
 * 若后续要补 [com.huanchengfly.tieba.post.utils.OpRecordStore] 的测试，
 * 需要先把它从 `object` 单例 + 直传 Context 改成可注入的接口（见审查报告 §3.21）。
 */
class AgreeOpTest {

    // ---------- 差分数学 ----------

    @Test
    fun opCountDelta_mapsThreeStates() {
        assertEquals(1L, opCountDelta(MyAgreeOp.AGREE))
        assertEquals(-1L, opCountDelta(MyAgreeOp.DISAGREE))
        assertEquals(0L, opCountDelta(MyAgreeOp.NONE))
    }

    @Test
    fun displayDelta_freshAgreeIsPlusOne() {
        // 刚点赞:意图 AGREE,基准尚未反映 → 显示 +1
        assertEquals(1L, OpRecord(MyAgreeOp.AGREE, MyAgreeOp.NONE).displayDelta())
    }

    @Test
    fun displayDelta_freshDisagreeIsMinusOne() {
        assertEquals(-1L, OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.NONE).displayDelta())
    }

    @Test
    fun displayDelta_agreeToDisagreeIsMinusTwo() {
        // 已确认的赞(my=server=AGREE)上切换成踩:显示 -1 -(+1) = -2
        assertEquals(-2L, OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.AGREE).displayDelta())
    }

    @Test
    fun displayDelta_disagreeToAgreeIsPlusTwo() {
        assertEquals(2L, OpRecord(MyAgreeOp.AGREE, MyAgreeOp.DISAGREE).displayDelta())
    }

    @Test
    fun displayDelta_afterRebaseIsZero() {
        // ★ rebase 语义:标记对齐到意图后偏移归零,基准计数已包含该操作。
        // 若 rebase 实现成"清除标记",重载后会重复叠加 delta,再次刷新复合漂移。
        assertEquals(0L, OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE).displayDelta())
        assertEquals(0L, OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.DISAGREE).displayDelta())
    }

    // ---------- agreeCountDelta():列表卡(原始 agreeNum 基准)的赞轴专用差分 ----------

    @Test
    fun agreeCountDelta_disagreeAxisDoesNotTouchAgreeCount() {
        // ★ 列表卡基准是原始 agreeNum(赞数)而非 diffAgreeNum:帖子页在途的踩
        // 不得让列表卡赞数凭空 -1(displayDelta 会给 -1,agreeCountDelta 必须 0)
        assertEquals(0L, OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.NONE).agreeCountDelta())
        assertEquals(0L, OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.DISAGREE).agreeCountDelta())
        assertEquals(0L, OpRecord(MyAgreeOp.NONE, MyAgreeOp.DISAGREE).agreeCountDelta())
    }

    @Test
    fun agreeCountDelta_followsAgreeAxisOnly() {
        assertEquals(1L, OpRecord(MyAgreeOp.AGREE, MyAgreeOp.NONE).agreeCountDelta())
        assertEquals(-1L, OpRecord(MyAgreeOp.NONE, MyAgreeOp.AGREE).agreeCountDelta())
        // 赞踩切换态(踩服务端记着、本地乐观成赞):原始赞数应 +1 而非 displayDelta 的 +2
        assertEquals(1L, OpRecord(MyAgreeOp.AGREE, MyAgreeOp.DISAGREE).agreeCountDelta())
        assertEquals(0L, OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE).agreeCountDelta())
        assertEquals(0L, OpRecord(MyAgreeOp.NONE, MyAgreeOp.NONE).agreeCountDelta())
    }

    // ---------- reverted():回退到对齐标记,而不是清空 ----------

    @Test
    fun reverted_restoresIntentToServerMark() {
        // server=AGREE(此前已确认的赞),乐观叠了一个踩,请求失败回退 → 回到 AGREE 而非 NONE
        val record = OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.AGREE)
        val reverted = record.reverted()
        assertEquals(MyAgreeOp.AGREE, reverted.my)
        assertEquals(MyAgreeOp.AGREE, reverted.server)
        assertEquals(0L, reverted.displayDelta())
    }

    @Test
    fun reverted_clearsUnconfirmedOptimisticUpdate() {
        val reverted = OpRecord(MyAgreeOp.AGREE, MyAgreeOp.NONE).reverted()
        assertEquals(MyAgreeOp.NONE, reverted.my)
        assertEquals(0L, reverted.displayDelta())
    }

    // ---------- 播种（§3.7 回归）:只认 agreeType,不认 hasAgree ----------

    @Test
    fun serverEchoOp_nullIsNone() {
        assertEquals(MyAgreeOp.NONE, null.serverEchoOp())
    }

    @Test
    fun serverEchoOp_ignoresUnreliableHasAgreeEcho() {
        // ★ 回归测试(§3.7):has_agree 会被回显成 1(疑似"有过操作"语义)。
        // 播种若采信它,会把从未点赞的帖子点亮,且 confirm 落盘后每次 rebase 都只做
        // srv=my、无法纠正 → 错误状态被永久保留。
        val agree = Agree(hasAgree = 1, agreeType = 0)
        assertEquals(MyAgreeOp.NONE, agree.serverEchoOp())
    }

    @Test
    fun serverEchoOp_disagreeFromAgreeType() {
        val agree = Agree(agreeType = AgreeParams.TYPE_DISAGREE)
        assertEquals(MyAgreeOp.DISAGREE, agree.serverEchoOp())
    }

    @Test
    fun serverEchoOp_agreeTypeTakesPrecedenceOverHasAgree() {
        val agree = Agree(hasAgree = 1, agreeType = AgreeParams.TYPE_DISAGREE)
        assertEquals(MyAgreeOp.DISAGREE, agree.serverEchoOp())
    }

    // ---------- 权威码识别（§3.6 回归）:中文路径此前恒返回 null ----------

    @Test
    fun serverOpFromErrorMessage_recognizesChineseAlreadyAgreed() {
        // ★ 回归测试(§3.6):error_code 为数字时异常 message 取自 errorMsg,是中文。
        // 修复前只有英文分支,该路径恒返回 null,整个"采纳服务端陈述"机制形同虚设。
        assertEquals(MyAgreeOp.AGREE, serverOpFromErrorMessage("您已赞过"))
        assertEquals(MyAgreeOp.AGREE, serverOpFromErrorMessage("你已经点赞了"))
    }

    @Test
    fun serverOpFromErrorMessage_recognizesChineseAlreadyDisagreed() {
        assertEquals(MyAgreeOp.DISAGREE, serverOpFromErrorMessage("您已踩过"))
        assertEquals(MyAgreeOp.DISAGREE, serverOpFromErrorMessage("你已经点踩了"))
    }

    @Test
    fun serverOpFromErrorMessage_negativeWinsOverPositive() {
        // "未赞过"包含"赞过",若顺序颠倒会被误判成 AGREE。否定语义必须优先。
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorMessage("您还没有赞过"))
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorMessage("你未点赞"))
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorMessage("已取消赞"))
    }

    @Test
    fun serverOpFromErrorMessage_unknownReturnsNull() {
        // 匹配不上仍返回 null → 走"通用失败→回滚",行为不变,无回归风险
        assertNull(serverOpFromErrorMessage("网络开小差了"))
        assertNull(serverOpFromErrorMessage(null))
        assertNull(serverOpFromErrorMessage(""))
    }

    @Test
    fun serverOpFromErrorCode_englishPathStillWorks() {
        // 路径 A:error_code 是字符串(英文码),修复前的既有能力不能破坏
        assertEquals(MyAgreeOp.AGREE, serverOpFromErrorCode("ERR_USER_HAS_AGREED"))
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorCode("ERR_USER_HAS_NO_AGREE"))
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorCode("ERR_USER_HAS_CANCEL_DISAGREE"))
    }

    @Test
    fun serverOpFromErrorCode_notDisagreeIsAuthoritativeNone() {
        // 真机实证(personal.20):主帖点踩服务端回 error_code=0 却不记账,
        // 随后的"取消踩"返回 ERR_USER_NOT_DISAGREE。这是权威陈述,必须对齐 NONE;
        // 修复前匹配不上 → 走通用失败回滚 → 图标弹回点亮,用户"取消不掉"。
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorCode("ERR_USER_NOT_DISAGREE"))
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorCode("ERR_USER_NOT_AGREE"))
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorCode("ERR_USER_NOT_AGREED"))
        // 中文同义路径(异常路径 message)
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorMessage("您还没有踩过"))
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorMessage("你未踩"))
        // 不得把 HAS_DISAGREE 误伤成 NONE(先后顺序回归)
        assertEquals(MyAgreeOp.DISAGREE, serverOpFromErrorCode("ERR_USER_HAS_DISAGREE"))
    }

    @Test
    fun serverOpFromErrorCode_unknownFallsBackToNone() {
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorCode("SOME_UNKNOWN_CODE"))
        assertEquals(MyAgreeOp.NONE, serverOpFromErrorCode(null))
    }

    // ---------- 服务端结果三分类（§3.5 回归） ----------

    @Test
    fun toOpAgreeResult_okWhenErrorCodeIsNullOrZero() {
        assertTrue(agreeBean(errorCode = null).toOpAgreeResult(1L, false) is OpAgreeResult.Ok)
        assertTrue(agreeBean(errorCode = "0").toOpAgreeResult(1L, false) is OpAgreeResult.Ok)
    }

    @Test
    fun toOpAgreeResult_authoritativeOnKnownRejection() {
        // 服务端明确陈述"你已赞过" → 必须归为 Authoritative,由调用方采纳服务端陈述
        val result = agreeBean(errorCode = "ERR_USER_HAS_AGREED", errorMsg = "您已赞过")
            .toOpAgreeResult(1L, false)
        assertTrue(result is OpAgreeResult.Authoritative)
        assertEquals("您已赞过", (result as OpAgreeResult.Authoritative).msg)
    }

    @Test
    fun toOpAgreeResult_businessOnUnknownErrorCode() {
        // ★ 回归测试(§3.5):修复前 5 个列表页无条件 confirm,任何 HTTP 200 都当成功,
        // 业务拒绝(如"你已赞过")被写入记录且永不自愈。
        val result = agreeBean(errorCode = "309001", errorMsg = "操作失败")
            .toOpAgreeResult(1L, false)
        assertTrue(result is OpAgreeResult.Business)
        assertEquals("操作失败", (result as OpAgreeResult.Business).msg)
    }

    // ---------- 工具 ----------

    /**
     * [AgreeBean] 的字段是不可变 `val` 且默认 null,单元测试里无法直接构造,
     * 用 Gson 从 JSON 反序列化来设置 error_code / error_msg。
     */
    private fun agreeBean(errorCode: String?, errorMsg: String? = null): AgreeBean {
        val fields = mutableListOf<String>()
        if (errorCode != null) fields += "\"error_code\": \"$errorCode\""
        if (errorMsg != null) fields += "\"error_msg\": \"$errorMsg\""
        return Gson().fromJson("{${fields.joinToString(",")}}", AgreeBean::class.java)
    }
}
