package com.qaqxianyuqaq.backend.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/** 兼容学校前端的 AES-CBC 编码；公开固定密钥仅用于协议兼容，传输仍必须依赖 HTTPS。 */
public final class NjuptCredentialUtil {
    private NjuptCredentialUtil() { }

    public static String encrypt(String value, String checkKey) {
        if (checkKey == null || !checkKey.matches("[0-9]{13}")) {
            throw new IllegalArgumentException("学校认证 checkKey 必须为 13 位数字");
        }
        byte[] key = ("iam" + checkKey).getBytes(StandardCharsets.US_ASCII);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
            return HexFormat.of().formatHex(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("学校认证加密不可用");
        }
    }
}
