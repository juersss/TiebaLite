package com.huanchengfly.tieba.post.selfcheck

import android.content.Context
import androidx.core.content.FileProvider
import com.github.panpf.sketch.request.DownloadRequest
import com.github.panpf.sketch.request.DownloadResult
import com.github.panpf.sketch.request.execute
import com.huanchengfly.tieba.post.core.network.model.protos.pbPage.PbPageResponse
import com.huanchengfly.tieba.post.ui.common.renders
import com.huanchengfly.tieba.post.core.common.upgradeImageUrlToHttps
import com.huanchengfly.tieba.post.ui.common.PicContentRender
import java.io.File

/**
 * 图片链路组：真实下载落盘 + 分享 URI 可达。
 *
 * 这条链路是 09-14「大图整屏纯黑」事故的现场——当时问题是图页接口下发 http 地址被
 * 网络策略（禁明文）拒掉，而"点进主页看看"完全发现不了。所以这里不查 URL 字符串，
 * 而是**真的下载一份字节**下来验落盘与魔数。
 *
 * 走 [DownloadRequest]（Sketch）而不是自己 new OkHttpClient：与
 * `ImageUtil.downloadForShare` 同一条链路（同一 HTTP 栈、同一磁盘缓存），
 * 网络策略的变化会如实反映出来。
 */

/** 图片 URL 的来源，失败信息里要能看出"这张图是从哪来的" */
internal data class ImageSource(val url: String, val from: String)

internal object ImageProbe {
    /**
     * 取一张**真实存在**的图片 URL。优先级：
     * 1. 帖子页首屏里的图片内容（走生产渲染链 `content.renders` → [PicContentRender]，
     *    即 UI 上真正渲染的那张图，顺带覆盖"V22 图片有没有下发"）；
     * 2. 吧头像（每个吧必有，不赌"这个帖刚好带图"）。
     */
    suspend fun pickImageUrl(): ImageSource {
        val pb = runCatching { ProbeData.pbPage() }.getOrNull()
        if (pb != null) {
            pb.threadImages().firstOrNull()?.let { return ImageSource(it, "帖子页 pb 图片内容") }
        }
        val (forumName, frs) = ProbeData.frsPage()
        val avatar = frs.data_?.forum?.avatar?.takeIf { it.isNotBlank() }
        if (avatar != null) return ImageSource(avatar, "吧「$forumName」头像")
        throw IllegalStateException("吧页与帖子页响应里都没有可用的图片 URL")
    }
}

/** 帖子页首屏里所有可下载的图片 URL（原图优先，原图缺失回落渲染用的缩略图） */
private fun PbPageResponse.threadImages(): List<String> {
    val posts = data_?.post_list.orEmpty() + listOfNotNull(data_?.first_floor_post)
    val urls = ArrayList<String>()
    posts.forEach { post ->
        post.content.renders
            .filterIsInstance<PicContentRender>()
            .forEach { render ->
                val url = render.originUrl.takeIf { it.isNotBlank() } ?: render.picUrl
                if (url.isNotBlank()) urls += url
            }
    }
    return urls
}

/** 下载到内存：返回字节数组，失败带原因 */
private suspend fun downloadBytes(context: Context, url: String): Result<ByteArray> = runCatching {
    val result = DownloadRequest(context, url).execute()
    if (result !is DownloadResult.Success) {
        // 不读 result.error（跨版本字段名不稳），报出结果类型即可定位
        throw IllegalStateException("Sketch 未返回 Success，实际 ${result::class.java.simpleName}")
    }
    result.data.data.newInputStream().use { it.readBytes() }
}

/** 图片格式识别：返回格式名，识别不出返回 null */
private fun imageFormatOf(bytes: ByteArray): String? {
    if (bytes.size < 12) return null
    fun at(i: Int) = bytes[i].toInt() and 0xFF
    return when {
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "JPEG"
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "PNG"
        at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> "GIF"
        at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "WEBP"
        at(0) == 0x42 && at(1) == 0x4D -> "BMP"
        at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> "HEIF/ISO-BMFF"
        else -> null
    }
}

private fun hexOf(bytes: ByteArray): String =
    bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

/**
 * 远端图片下载 + 落盘字节校验。
 *
 * 断言三件事：真的下载成功、字节数 > 0、开头字节是认得出的图片魔数
 * —— 劫持/错误页（HTML、"网络异常"占位图）都会在魔数上现形，而"字节数 > 0"抓不到。
 */
object RemoteImageDownloadCase : SelfCheckCase {
    override val group = "图片链路"
    override val name = "远端图下载与图片魔数"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val source = try {
            ImageProbe.pickImageUrl()
        } catch (t: Throwable) {
            return skipOrFail("取图片 URL 失败 —— ${t.shortMessage()}")
        }
        // 与 PhotoViewItem.downloadTarget 同口径：下载前统一升 https（图页接口下发 http）
        val url = upgradeImageUrlToHttps(source.url)
        val bytes = downloadBytes(context, url).getOrElse {
            return skipOrFail("下载失败（来源：${source.from}）$url —— ${it.shortMessage()}")
        }
        expect(bytes.isNotEmpty()) { "下载 $url 得到 0 字节（来源：${source.from}）" }
        val format = imageFormatOf(bytes)
        expect(format != null) {
            "下载 $url 得到 ${bytes.size} 字节，但魔数不是已知图片格式：${hexOf(bytes.copyOf(12))}" +
                "（来源：${source.from}，疑似被劫持/返回了错误页）"
        }
        return pass()
    }
}

/**
 * 分享 URI 可达：下载 → 落到 `cacheDir/.shareTemp` → 经 FileProvider 转 content:// →
 * 再读回来比对字节数。
 *
 * 覆盖的是"下载按钮/分享按钮静默失效"这一类问题：`file_paths_share_img.xml` 只声明了
 * `.shareTemp` 一个 cache-path，路径一改 `getUriForFile` 就抛 IllegalArgumentException。
 *
 * 边界（不做的事）：**不弹 Chooser、不启任何分享面板**——自检不该在用户脸上弹窗，
 * 也不该产生外部副作用；Chooser 本身由冒烟脚本的点击用例覆盖。
 */
object ShareUriCase : SelfCheckCase {
    override val group = "图片链路"
    override val name = "分享 URI 可达（FileProvider）"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val source = try {
            ImageProbe.pickImageUrl()
        } catch (t: Throwable) {
            return skipOrFail("取图片 URL 失败 —— ${t.shortMessage()}")
        }
        val url = upgradeImageUrlToHttps(source.url)
        val bytes = downloadBytes(context, url).getOrElse {
            return skipOrFail("下载失败（来源：${source.from}）$url —— ${it.shortMessage()}")
        }
        expect(bytes.isNotEmpty()) { "下载 $url 得到 0 字节（来源：${source.from}）" }

        val shareDir = File(context.cacheDir, SHARE_TEMP_DIR)
        expect(shareDir.isDirectory || shareDir.mkdirs()) {
            "分享临时目录不可用：${shareDir.absolutePath}"
        }
        val file = File(shareDir, "selfcheck_${System.currentTimeMillis()}.tmp")
        return try {
            file.outputStream().use { it.write(bytes) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share.FileProvider", file)
            expect(uri.scheme == "content") { "分享 URI 应为 content://，实际 <${uri.scheme}>：$uri" }
            val readBack = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return fail("FileProvider 给出的 URI 打不开：$uri")
            expectEquals(readBack.size, bytes.size, "经 FileProvider 读回的字节数")
            pass()
        } finally {
            // 自检不留垃圾：临时文件当场清掉
            runCatching { file.delete() }
        }
    }
}

/** 与 `res/xml/file_paths_share_img.xml` 的 cache-path 声明一一对应 */
private const val SHARE_TEMP_DIR = ".shareTemp"
