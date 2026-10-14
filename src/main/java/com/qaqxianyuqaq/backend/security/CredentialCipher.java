package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.util.CryptoUtil;
import org.springframework.stereotype.Component;

/**
 * 题目答案加解密组件。
 *
 * <p>答案以 AES-GCM 密文落库，仅管理端读取接口按需解密，避免明文答案泄漏。</p>
 */
@Component
public class CredentialCipher {

    private final TraceQuestProperties properties;

    public CredentialCipher(TraceQuestProperties properties) {
        this.properties = properties;
    }

    /** 加密答案。 */
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        return CryptoUtil.encrypt(plainText, properties.getSecurity().getAnswerSecret());
    }

    /** 解密答案，密文缺失时返回 null。 */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        return CryptoUtil.decrypt(cipherText, properties.getSecurity().getAnswerSecret());
    }
}
