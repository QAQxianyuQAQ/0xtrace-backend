package com.qaqxianyuqaq.backend.util;

import com.qaqxianyuqaq.backend.domain.dto.challenge.LocationPayloadDTO;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 现场位置二维码常量与载荷解析。
 *
 * <p>载荷格式固定为 TRACEQUEST|LOCATION|{题目编号}|{随机令牌}，令牌为十六进制随机值。</p>
 */
public final class LocationConstants {

    /** 支持位置二维码的题目分类。 */
    public static final String LOCATION_CATEGORY = "图寻";

    /** 载荷前缀。 */
    private static final String PREFIX = "TRACEQUEST|LOCATION|";

    /** 载荷结构校验：题目编号 2-24 位大写英数及 _ -，令牌为 32-128 位十六进制。 */
    private static final Pattern PAYLOAD_PATTERN =
            Pattern.compile("^TRACEQUEST\\|LOCATION\\|([A-Z0-9_-]{2,24})\\|([0-9a-fA-F]{32,128})$");

    private LocationConstants() {
    }

    /** 组装二维码明文载荷。 */
    public static String payloadOf(String challengeCode, String token) {
        return PREFIX + challengeCode + "|" + token;
    }

    /** 解析载荷，格式非法时返回 null。 */
    public static LocationPayloadDTO parse(String payload) {
        if (payload == null) {
            return null;
        }
        Matcher matcher = PAYLOAD_PATTERN.matcher(payload.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new LocationPayloadDTO(matcher.group(1), matcher.group(2).toLowerCase());
    }
}
