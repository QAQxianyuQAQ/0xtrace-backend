package com.qaqxianyuqaq.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要与安全比较工具。
 *
 * <p>用于令牌哈希、提交内容摘要与常量时间比较，禁止在业务代码中直接构造 MessageDigest。</p>
 */
public final class DigestUtil {

    private DigestUtil() {
    }

    /** 计算 UTF-8 文本的 SHA-256 十六进制摘要。 */
    public static String sha256Hex(String plainText) {
        return hex(sha256(plainText.getBytes(StandardCharsets.UTF_8)));
    }

    /** 计算字节数组的 SHA-256 摘要。 */
    public static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 JDK 必备算法，缺失属于不可恢复的环境错误
            throw new IllegalStateException("SHA-256 摘要算法不可用", exception);
        }
    }

    /** 字节数组转十六进制字符串。 */
    public static String hex(byte[] content) {
        return HexFormat.of().formatHex(content);
    }

    /** 常量时间比较两个字符串，避免比较耗时泄漏信息。 */
    public static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
