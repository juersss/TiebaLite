package com.huanchengfly.tieba.post.core.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MD5Util {

    private static final char[] yT = {48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 65, 66, 67, 68, 69, 70};

    public static String p(byte[] paramArrayOfByte) throws NoSuchAlgorithmException {
        MessageDigest localMessageDigest = MessageDigest.getInstance("MD5");
        localMessageDigest.update(paramArrayOfByte);
        return toHexString(localMessageDigest.digest());
    }

    public static String toHexString(byte[] paramArrayOfByte) {
        if (paramArrayOfByte == null)
            return null;
        StringBuilder localStringBuilder = new StringBuilder(paramArrayOfByte.length * 2);
        int i = 0;
        while (true) {
            if (i >= paramArrayOfByte.length)
                return localStringBuilder.toString();
            localStringBuilder.append(yT[((paramArrayOfByte[i] & 0xF0) >>> 4)]);
            localStringBuilder.append(yT[(paramArrayOfByte[i] & 0xF)]);
            i += 1;
        }
    }

    public static String toMd5(String paramString) {
        if (paramString == null) {
            return null;
        }
        try {
            paramString = p(paramString.getBytes(StandardCharsets.UTF_8));
            return paramString;
        } catch (Exception e) {
        }
        return null;
    }

    public static String toMd5(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return p(bytes);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 读盘失败必须上抛:吞掉返回 "" 会让上传方拿空 md5 拼 resourceId——
     * 所有分片退化为同一标识,服务端按 resourceId 去重时会串图/误判已上传。
     * 调用方(ImageUploader)已在 try 内,异常走失败路径并清理临时文件。
     */
   
    public static String toMd5(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("MD5 目标不是文件: " + file);
        }
        MessageDigest digest;
        FileInputStream in = null;
        byte[] buffer = new byte[1024];
        int len;
        try {
            digest = MessageDigest.getInstance("MD5");
            in = new FileInputStream(file);
            while ((len = in.read(buffer, 0, 1024)) != -1) {
                digest.update(buffer, 0, len);
            }
        } catch (Exception e) {
            throw e instanceof java.io.IOException ? (java.io.IOException) e : new IOException(e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return toHexString(digest.digest());
    }
}