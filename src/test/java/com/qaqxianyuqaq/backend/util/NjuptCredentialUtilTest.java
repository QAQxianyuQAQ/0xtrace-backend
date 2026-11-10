package com.qaqxianyuqaq.backend.util;

import org.junit.jupiter.api.Test;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

class NjuptCredentialUtilTest {
    @Test
    void shouldMatchProvidedVectorAndPreserveUtf8AndPadding() throws Exception {
        assertThat(NjuptCredentialUtil.encrypt("B25041114", "1629428467008"))
                .isEqualTo("d731f8de34f229e1ad49cde8eac4f8ac");
        var key = "iam1629428467008".getBytes(StandardCharsets.US_ASCII);
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        for (String plain : new String[]{"", "1234567890123456", " 学校密码测试 ", "a".repeat(256)}) {
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
            String encrypted = NjuptCredentialUtil.encrypt(plain, "1629428467008");
            assertThat(encrypted).matches("[a-f0-9]+");
            assertThat(new String(cipher.doFinal(HexFormat.of().parseHex(encrypted)), StandardCharsets.UTF_8)).isEqualTo(plain);
        }
        assertThatThrownBy(() -> NjuptCredentialUtil.encrypt("value", "bad-key")).isInstanceOf(IllegalArgumentException.class);
    }
}
