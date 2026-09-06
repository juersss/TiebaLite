package com.huanchengfly.tieba.post.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.BOUNDARY
import com.huanchengfly.tieba.post.api.booleanToString
import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean
import com.huanchengfly.tieba.post.api.retrofit.RetrofitTiebaApi
import com.huanchengfly.tieba.post.api.retrofit.body.MyMultipartBody
import com.huanchengfly.tieba.post.api.retrofit.body.buildMultipartBody
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.utils.ImageUtil
import com.huanchengfly.tieba.post.utils.MD5Util
import com.huanchengfly.tieba.post.utils.appPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile

class ImageUploader(
    private val forumName: String,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 512000

        const val IMAGE_MAX_SIZE = 5242880
        const val ORIGIN_IMAGE_MAX_SIZE = 10485760

        // 上传解码长边上限:超过则按 2 的幂降采样,防 8K/全景图全尺寸解码 OOM
        const val MAX_UPLOAD_DECODE_DIM = 8192

        // 上传解码总像素上限(外部审查-内存预算):8192 长边约束挡不住两轴均大的
        // 方形图——8192×8192 的 ARGB_8888 约 256MiB。8M 像素 ≈ 32MiB ARGB_8888,
        // 超限图会走 1080P 缩放分支,上传可见效果不变
        const val MAX_UPLOAD_PIXELS = 8L * 1024 * 1024

        const val PIC_WATER_TYPE_NO = "0"
        const val PIC_WATER_TYPE_USER_NAME = "1"
        const val PIC_WATER_TYPE_FORUM_NAME = "2"
    }

    fun uploadImages(
        filePaths: List<String>,
        isOriginImage: Boolean = false,
    ): Flow<List<UploadPictureResultBean>> {
        return filePaths.asFlow()
            .map { filePath ->
                uploadSinglePicture(filePath, isOriginImage)
            }
            .runningFold<UploadPictureResultBean, MutableList<UploadPictureResultBean>>(initial = mutableListOf()) { list, result ->
                list.add(result)
                list
            }
            .filter { it.size == filePaths.size }
    }

    private suspend fun compressImage(
        filePath: String,
        isOriginImage: Boolean
    ): File {
        val originFile = File(filePath)
        val fileLength = originFile.length()
        val maxSize = if (isOriginImage) ORIGIN_IMAGE_MAX_SIZE else IMAGE_MAX_SIZE
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("temp", ".tmp")
        }
        try {
            withContext<Unit>(Dispatchers.IO) {
                if (isOriginImage && fileLength <= maxSize) {
                    originFile.copyTo(tempFile, true)
                } else {
                    val bitmap = decodeForUpload(filePath)
                    val firstCompressResult = ImageUtil.compressImage(bitmap, quality = 95)
                    tempFile.writeBytes(firstCompressResult)
                    if (firstCompressResult.size > maxSize) {
                        // 压缩尺寸至 1080P
                        val width = bitmap.width
                        val height = bitmap.height
                        val scale = if (width > height) {
                            1080f / width
                        } else {
                            1080f / height
                        }
                        if (scale < 1) {
                            val newWidth = (width * scale).toInt()
                            val newHeight = (height * scale).toInt()
                            val newBitmap =
                                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                            tempFile.writeBytes(ImageUtil.compressImage(newBitmap, quality = 95))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // 压缩中途失败(OOM/读盘错):不留孤儿 temp 文件
            withContext(NonCancellable + Dispatchers.IO) { tempFile.delete() }
            throw t
        }
        return tempFile
    }

    /**
     * 上传路径的位图解码:普通尺寸照片(长边 ≤ [MAX_UPLOAD_DECODE_DIM] 且总像素
     * ≤ [MAX_UPLOAD_PIXELS])行为与原实现完全一致;超限的图按 2 的幂降采样——
     * 长边上限挡 8K/全景图全尺寸解码 OOM,总像素上限挡"长边不超限但两轴都大"
     * 的方形图(8192×8192 ARGB_8888 约 256MiB,外部审查-内存预算)。
     * 这类图本来也几乎必然走 1080P 缩放分支,最终输出不变。
     */
    private fun decodeForUpload(filePath: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_UPLOAD_DECODE_DIM ||
            bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_UPLOAD_PIXELS
        ) {
            sample *= 2
        }
        return if (sample == 1) {
            BitmapFactory.decodeFile(filePath)
        } else {
            BitmapFactory.decodeFile(
                filePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun uploadSinglePicture(
        filePath: String,
        isOriginImage: Boolean = false,
    ): UploadPictureResultBean {
        val file = compressImage(filePath, isOriginImage)
        // 外部审查-元数据一致性:宽高改从最终上传文件读取。压缩可能缩放/降采样,
        // 旧实现读原图尺寸随分块一起上传,元数据与实际内容不一致
        val option = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, option)
        val width = option.outWidth
        val height = option.outHeight
        check(width > 0 && height > 0) { "图片宽高不正确" }
        // 同步段(校验/MD5/分块读取)失败则临时文件不会被下方 onCompletion 触及,单独清理
        val requestBodies = try {
            val fileLength = file.length()
            val maxSize = if (isOriginImage) ORIGIN_IMAGE_MAX_SIZE else IMAGE_MAX_SIZE
            check(fileLength <= maxSize) { "图片大小超过限制" }
            val fileMd5 = MD5Util.toMd5(file)
            val isMultipleChunkSize = fileLength % chunkSize == 0L
            val totalChunkNum = fileLength / chunkSize + if (isMultipleChunkSize) 0 else 1
            val picWatermarkType =
                App.INSTANCE.appPreferences.picWatermarkType ?: PIC_WATER_TYPE_FORUM_NAME
            (0 until totalChunkNum).map { chunk ->
                val isFinish = chunk == totalChunkNum - 1
                val curChunkSize = if (isFinish) {
                    if (isMultipleChunkSize) {
                        chunkSize
                    } else {
                        fileLength % chunkSize
                    }
                } else {
                    chunkSize
                }.toInt()
                val chunkBytes = ByteArray(curChunkSize)
                withContext(Dispatchers.IO) {
                    RandomAccessFile(file, "r").use {
                        it.seek(chunk * chunkSize.toLong())
                        it.read(chunkBytes)
                    }
                }
                buildMultipartBody(BOUNDARY) {
                    setType(MyMultipartBody.FORM)
                    addFormDataPart("alt", "json")
                    addFormDataPart("chunkNo", "${chunk + 1}")
                    if (forumName.isNotEmpty()) addFormDataPart("forum_name", forumName)
                    addFormDataPart("groupId", "1")
                    addFormDataPart("height", "$height")
                    addFormDataPart("isFinish", isFinish.booleanToString())
                    addFormDataPart("is_bjh", "0")
                    addFormDataPart("pic_water_type", picWatermarkType)
                    addFormDataPart("resourceId", "$fileMd5$chunkSize")
                    addFormDataPart("saveOrigin", isOriginImage.booleanToString())
                    addFormDataPart("size", "$fileLength")
                    if (forumName.isNotEmpty()) addFormDataPart("small_flow_fname", forumName)
                    addFormDataPart("width", "$width")
                    addFormDataPart("chunk", "file", chunkBytes.toRequestBody())
                }
            }
        } catch (t: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            throw t
        }
        return requestBodies.asFlow()
            .flatMapConcat { RetrofitTiebaApi.OFFICIAL_TIEBA_API.uploadPicture(it) }
            .catch {
                throw UploadPictureFailedException(it.getErrorCode(), it.getErrorMessage())
            }
            .onCompletion {
                // NonCancellable:集合被取消时此处默认会立即再抛 CancellationException,
                // 删除不执行——R7 点名的"取消不删临时文件"正是这个机制
                withContext(NonCancellable + Dispatchers.IO) {
                    file.delete()
                }
            }
            .last()
    }
}

class UploadPictureFailedException(
    override val code: Int = -1,
    override val message: String = "上传图片失败",
) : TiebaException(message)