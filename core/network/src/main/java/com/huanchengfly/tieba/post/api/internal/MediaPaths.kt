package com.huanchengfly.tieba.post.api.internal

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * MediaStore 查询工具。3b-prep-2（2026-09-17）从 `FileUtil.getRealPathFromUri` 逐字搬出
 * （app 侧原方法保留为同名转发）——`PhotoInfoBean` 要用它，而 FileUtil 是 app 私有工具类。
 *
 * 注意：这是**旧链路**的 `_data` 列查询，云端图片/仅持 URI 读授权时会失败；
 * 上传应优先走 FileUtil 里容错化的解析（见其 KDoc）。
 */
object MediaPaths {

    @JvmStatic
    fun getRealPathFromUri(context: Context, contentUri: Uri?): String {
        val proj = arrayOf(MediaStore.Images.Media.DATA)
        context.contentResolver.query(contentUri!!, proj, null, null, null).use { cursor ->
            if (cursor != null) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                cursor.moveToFirst()
                return cursor.getString(columnIndex)
            }
        }
        return ""
    }
}
