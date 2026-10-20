package com.huanchengfly.tieba.post.api.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `uploadPicture` 响应模型的双键名 / 可缺省契约（2026-10-20）。
 *
 * 回归点（审查 §二 P3）：模型此前只有 `error_code` / `error_msg` 带了 `@SerialName`，
 * 其余 snake_case 键（`chunk_no` / `pic_info` / `origin_pic` / `pic_url`）在 kotlinx.serialization
 * 的严格键名下解不出来——成功响应会因为缺 `chunkNo` 抛 `MissingFieldException`，
 * 上传失败响应同样抛错，**真实的 error_msg 被异常文本覆盖**。
 */
class UploadPictureResultBeanTest {

    /** 与 `RetrofitTiebaApi.json` 同配置，保证单测覆盖线上解码路径。 */
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun decodesSnakeCaseSuccessResponse() {
        val body = """
            {"error_code":"0","error_msg":"","resource_id":"abc","chunk_no":"2",
             "pic_id":"p1",
             "pic_info":{"origin_pic":{"width":"1080","height":"1920","type":"jpg","pic_url":"https://x/o.jpg"},
                         "big_pic":{"width":"720","height":"1280","type":"jpg","pic_url":"https://x/b.jpg"},
                         "small_pic":{"width":"360","height":"640","type":"jpg","pic_url":"https://x/s.jpg"}}}
        """.trimIndent()

        val bean = json.decodeFromString(UploadPictureResultBean.serializer(), body)

        assertEquals("0", bean.errorCode)
        assertEquals("2", bean.chunkNo)
        assertEquals("abc", bean.resourceId)
        assertEquals("p1", bean.picId)
        assertNotNull(bean.picInfo)
        assertEquals("https://x/o.jpg", bean.picInfo?.originPic?.picUrl)
        assertEquals("1080", bean.picInfo?.originPic?.width)
        assertEquals("https://x/s.jpg", bean.picInfo?.smallPic?.picUrl)
    }

    /** 上传被拒时服务端只回错误码/文案，没有 chunk_no —— 必须能解出来并把文案交回 UI。 */
    @Test
    fun decodesErrorEnvelopeWithoutChunkNo() {
        val body = """{"error_code":"340006","error_msg":"图片审核未通过"}"""

        val bean = json.decodeFromString(UploadPictureResultBean.serializer(), body)

        assertEquals("340006", bean.errorCode)
        assertEquals("图片审核未通过", bean.errorMsg)
        assertEquals("", bean.chunkNo)
        assertNull(bean.picInfo)
    }

    /** 万一某个端点改回 camelCase，双键名仍能解（不因为一个键名猜错就整条链路失败）。 */
    @Test
    fun decodesCamelCaseVariantToo() {
        val body = """
            {"errorCode":"0","errorMsg":"","resourceId":"r","chunkNo":"1",
             "picInfo":{"originPic":{"width":"1","height":"2","type":"jpg","picUrl":"https://x/a.jpg"}}}
        """.trimIndent()

        val bean = json.decodeFromString(UploadPictureResultBean.serializer(), body)

        assertEquals("1", bean.chunkNo)
        assertEquals("https://x/a.jpg", bean.picInfo?.originPic?.picUrl)
    }

    /** 空对象也不能抛：字段全部有默认值，解码失败会把错误码语义丢掉。 */
    @Test
    fun decodesEmptyObjectWithDefaults() {
        val bean = json.decodeFromString(UploadPictureResultBean.serializer(), "{}")
        assertEquals("", bean.errorCode)
        assertEquals("", bean.chunkNo)
    }
}
