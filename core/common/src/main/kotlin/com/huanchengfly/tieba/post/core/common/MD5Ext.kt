package com.huanchengfly.tieba.post.core.common

/**
 * MD5 扩展（String / ByteArray）。3b-prep-2（2026-09-17）从 app 根包 `Extensions.kt` 搬来，
 * 实现与 [MD5Util] 逐字不变——签名的 `|V<sign>` 后缀与上传的 `resourceId` 都依赖它的输出。
 */
fun String.toMD5(): String = MD5Util.toMd5(this)

fun ByteArray.toMD5(): String = MD5Util.toMd5(this)
