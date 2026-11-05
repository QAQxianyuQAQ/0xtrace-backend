package com.qaqxianyuqaq.backend.util;

import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.StorageException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import static org.assertj.core.api.Assertions.*;

/** 校验图片内容与路径边界，不依赖扩展名或客户端声明的 MIME。 */
class AvatarImageUtilTest {
    @Test
    void normalizesPngAndJpegAndRemovesTrailingPayload() throws Exception {
        for (String format : new String[] { "png", "jpeg" }) {
            byte[] source = image(format);
            byte[] withPayload = Arrays.copyOf(source, source.length + 14);
            System.arraycopy("SECRET_PAYLOAD".getBytes(), 0, withPayload, source.length, 14);
            byte[] png = AvatarImageUtil.normalize(new MockMultipartFile("file", "../../untrusted.exe",
                    "image/" + format, withPayload));
            BufferedImage result = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(result.getWidth()).isEqualTo(512);
            assertThat(result.getHeight()).isEqualTo(512);
            assertThat(png[0]).isEqualTo((byte) 137);
            assertThat(new String(png, java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain("SECRET_PAYLOAD");
        }
    }

    @Test
    void rejectsEmptySpoofedAndUnsupportedImages() throws Exception {
        assertThatThrownBy(() -> AvatarImageUtil.normalize(null)).isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> AvatarImageUtil.normalize(new MockMultipartFile("file", new byte[0])))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> AvatarImageUtil.normalize(new MockMultipartFile("file", "fake.png", "image/png",
                "<svg onload='alert(1)'/>".getBytes()))).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> AvatarImageUtil.normalize(new MockMultipartFile("file", "real.svg", "image/svg+xml",
                image("png")))).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> AvatarImageUtil.normalize(new MockMultipartFile("file", "fake.jpg", "image/jpeg",
                image("png")))).isInstanceOf(StorageException.class);
    }

    @Test
    void rejectsSizeAndPixelBombBeforeDecoding() throws Exception {
        assertThatThrownBy(() -> AvatarImageUtil.normalize(new MockMultipartFile("file", "big.png", "image/png",
                new byte[AvatarImageUtil.MAX_BYTES + 1]))).isInstanceOf(StorageException.class);
        byte[] image = image("png");
        ByteBuffer.wrap(image).putInt(16, 8192).putInt(20, 8192);
        CRC32 crc = new CRC32();
        crc.update(image, 12, 17);
        ByteBuffer.wrap(image).putInt(29, (int) crc.getValue());
        assertThatThrownBy(() -> AvatarImageUtil.normalize(new MockMultipartFile("file", "bomb.png", "image/png", image)))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void onlyDeletesManagedAvatarsOwnedByTheSameAccount() {
        String user = "usr_example123";
        String avatar = "avatar_0123456789abcdefgh";
        String url = AvatarImageUtil.url(user, avatar);
        assertThat(AvatarImageUtil.previousStorageKey(user, url)).isEqualTo("challenges/profile-avatars/" + user + "/" + avatar + ".png");
        assertThat(AvatarImageUtil.previousStorageKey("usr_other", url)).isNull();
        assertThat(AvatarImageUtil.previousStorageKey(user, "https://example.com/avatar.png")).isNull();
        assertThatThrownBy(() -> AvatarImageUtil.storageKey(user, "../../file")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> AvatarImageUtil.storageKey("../etc", avatar)).isInstanceOf(NotFoundException.class);
    }

    private byte[] image(String format) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(48, 96, BufferedImage.TYPE_INT_RGB), format, bytes)).isTrue();
        return bytes.toByteArray();
    }
}
