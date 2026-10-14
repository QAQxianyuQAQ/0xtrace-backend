package com.qaqxianyuqaq.backend.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 对称加解密工具。
 *
 * <p>用于题目答案的静态加密存储：数据库只保存密文，管理端接口按需解密回显。</p>
 */
public final class CryptoUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM 认证标签长度（bit）。 */
    private static final int TAG_LENGTH_BITS = 128;

    /** 初始化向量长度（byte）。 */
    private static final int IV_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    /** 使用密钥加密明文，返回 Base64(IV||密文)。 */
    public static String encrypt(String plainText, String secret) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyOf(secret), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("答案加密失败", exception);
        }
    }

    /** 解密 Base64(IV||密文)。 */
    public static String decrypt(String cipherText, String secret) {
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyOf(secret), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("答案解密失败", exception);
        }
    }

    /** 由配置密钥派生出固定长度的 AES 密钥。 */
    private static SecretKeySpec keyOf(String secret) {
        return new SecretKeySpec(DigestUtil.sha256(secret.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
