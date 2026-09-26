package com.huanchengfly.tieba.post.api.params

import com.huanchengfly.tieba.post.core.common.helios.Base32
import com.huanchengfly.tieba.post.core.common.helios.Hasher

object CuidUtils {
    fun getNewCuid(): String {
        val cuid = UIDUtil.cUID
        val encode = Base32.encode(Hasher.hash(cuid.toByteArray()))
        return "$cuid|V$encode"
    }
}