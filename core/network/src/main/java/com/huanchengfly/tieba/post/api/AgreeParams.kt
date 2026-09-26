package com.huanchengfly.tieba.post.api

/**
 * /c/c/agree/opAgree 接口协议参数。
 *
 * 该接口同时承担“点赞”与“点踩”两类操作，由 agreeType 区分：
 * - agreeType = 2：赞
 * - agreeType = 5：踩
 *
 * objType 标识操作对象（与 aiotieba 语义一致）：
 * - 1 = 楼层（Post）
 * - 2 = 楼中楼（SubPost）
 * - 3 = 主帖（Thread）
 */
object AgreeParams {
    const val TYPE_AGREE = 2
    const val TYPE_DISAGREE = 5

    const val OP_DO = 0
    const val OP_UNDO = 1

    const val OBJ_POST = 1
    const val OBJ_SUB_POST = 2
    const val OBJ_THREAD = 3

    /**
     * 触发客户端限流时 Failure 携带的错误码，UI 层据此提示用户。
     * 注意与 CommonResponse.ERROR_CODE_UNKNOWN(-1) 只差一位数字,两者毫无关系:
     * 前者是限流哨兵值,后者是错误码解析失败的占位。
     */
    const val RATE_LIMIT_ERROR_CODE = -1001
}
