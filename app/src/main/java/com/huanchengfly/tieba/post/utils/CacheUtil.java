package com.huanchengfly.tieba.post.utils;

import com.huanchengfly.tieba.post.core.common.MD5Util;
import android.content.Context;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import com.huanchengfly.tieba.post.core.common.GsonUtil;

public final class CacheUtil {
    private CacheUtil() {
    }

    /** 外部缓存目录不可用时(权限拒绝/存储未挂载)回落内部缓存,不得 null 进 File 构造崩 */
    @Nullable
    private static File cacheDir(Context context) {
        File external = context.getExternalCacheDir();
        return external != null ? external : context.getCacheDir();
    }

    @Nullable
    public static <T> T getCache(Context context, String cacheId, Type typeOfT) {
        // 键只由 cacheId 派生:此前读用 tClass 名、写用运行时类名,两边对不上,
        // 磁盘缓存永不命中(每次进页都重拉壁纸接口)
        File cacheFile = new File(cacheDir(context), MD5Util.toMd5(cacheId));
        if (cacheFile.exists()) {
            // readFile 可空(不可读/读失败):null 进 base64Decode 会 NPE
            String cached = FileUtil.readFile(cacheFile);
            if (cached != null) {
                try {
                    return GsonUtil.getGson().fromJson(base64Decode(cached), typeOfT);
                } catch (JsonSyntaxException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    public static void putCache(Context context, String cacheId, Object object) {
        File cacheFile = new File(cacheDir(context), MD5Util.toMd5(cacheId));
        try {
            if (cacheFile.exists() || cacheFile.createNewFile()) {
                try {
                    FileUtil.writeFile(cacheFile, base64Encode(GsonUtil.getGson().toJson(object)), false);
                } catch (JsonSyntaxException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 转发到 api 层实现（3b-prep-2）：api 包要用同一份编码，但搬不动这个带文件缓存的类 */
    public static String base64Encode(String s) {
        return com.huanchengfly.tieba.post.api.internal.ApiEncoding.base64Encode(s);
    }

    /** 同上 */
    public static String base64Decode(String s) {
        return com.huanchengfly.tieba.post.api.internal.ApiEncoding.base64Decode(s);
    }
}
