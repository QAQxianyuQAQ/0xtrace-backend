package com.qaqxianyuqaq.backend.util;

import java.security.SecureRandom;

/**
 * 业务主键生成器。
 *
 * <p>统一生成带业务前缀的随机 ID（如 usr_、sub_、audit_），避免暴露自增主键与记录规模。</p>
 */
public final class IdGenerator {

    /** 去除易混淆字符后的字典表。 */
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 随机部分长度，18 位可提供约 90 bit 随机度，足以避免碰撞。 */
    private static final int RANDOM_LENGTH = 18;

    private IdGenerator() {
    }

    /** 生成带前缀的业务主键。 */
    public static String next(String prefix) {
        return prefix + "_" + randomToken(RANDOM_LENGTH);
    }

    /** 生成请求 ID，用于错误响应与审计串联。 */
    public static String requestId() {
        return "req_" + randomToken(RANDOM_LENGTH);
    }

    /** 生成二维码位置凭证明文令牌，长度为 32 字节随机量的十六进制表示。 */
    public static String locationToken() {
        byte[] buffer = new byte[32];
        RANDOM.nextBytes(buffer);
        return DigestUtil.hex(buffer);
    }

    /** 生成指定长度的随机字符串。 */
    public static String randomToken(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
