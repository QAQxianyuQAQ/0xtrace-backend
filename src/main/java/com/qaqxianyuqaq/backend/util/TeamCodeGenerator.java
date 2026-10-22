package com.qaqxianyuqaq.backend.util;

import java.security.SecureRandom;

/** 队伍标识码生成器：使用易录入的大写字母与数字，避免混淆字符。 */
public final class TeamCodeGenerator {

    /** 去除 I、O、0、1 等易混字符，降低现场录入错误。 */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** 标识码随机部分长度。 */
    private static final int RANDOM_LENGTH = 10;

    private TeamCodeGenerator() {
    }

    /** 生成带固定前缀的不可预测队伍标识码。 */
    public static String next() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder("TQ-");
        for (int index = 0; index < RANDOM_LENGTH; index++) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }
}
