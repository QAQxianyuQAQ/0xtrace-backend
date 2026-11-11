package com.qaqxianyuqaq.backend.util;

import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.StorageException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/** 头像校验与重编码，拒绝主动内容并去除输入文件的附加数据。 */
public final class AvatarImageUtil {

    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final long MAX_PIXELS = 16_777_216L;
    public static final int SIZE = 512;

    private AvatarImageUtil() { }

    /** 先检查体积及尺寸，再解码并居中裁剪为固定大小的 PNG，避免信任扩展名。 */
    public static byte[] normalize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationFailedException("file", "头像文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new StorageException(ErrorCode.PAYLOAD_TOO_LARGE, "头像文件不能超过 8 MiB");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!mime.equals("image/jpeg") && !mime.equals("image/png")) {
            throw invalidImage();
        }
        try (var source = file.getInputStream();
             var input = new MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(mime.equals("image/png") && format.equals("png"))
                        && !(mime.equals("image/jpeg") && (format.equals("jpeg") || format.equals("jpg")))) {
                    throw invalidImage();
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8192 || height > 8192
                        || (long) width * height > MAX_PIXELS) {
                    throw new ValidationFailedException("file", "头像尺寸不能超过 8192 边长及 16777216 总像素");
                }
                BufferedImage original = reader.read(0);
                if (original == null) throw invalidImage();
                BufferedImage avatar = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                var graphics = avatar.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int side = Math.min(width, height);
                    int x = (width - side) / 2;
                    int y = (height - side) / 2;
                    graphics.drawImage(original, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
                } finally {
                    graphics.dispose();
                    original.flush();
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(avatar, "png", output)) throw invalidImage();
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidImage();
        }
    }

    /** 对象键只使用服务端账号主键与随机版本，路径参数无法越界访问其他文件。 */
    public static String storageKey(String userId, String avatarId) {
        if (userId == null || !userId.matches("usr_[a-z0-9]{1,36}")
                || avatarId == null || !avatarId.matches("avatar_[a-z0-9]{18}")) {
            throw new NotFoundException("头像不存在");
        }
        // shortcut: 兼容既有 challenges/* 对象授权，独立头像授权可用后再迁移存储前缀。
        return "challenges/profile-avatars/" + userId + "/" + avatarId + ".png";
    }

    /** 返回同源、带版本的读取地址，避免泄露 MinIO 内网地址或凭据。 */
    public static String url(String userId, String avatarId) {
        storageKey(userId, avatarId);
        return "/api/v1/avatars/" + userId + "/" + avatarId;
    }

    /** 仅回收当前账号通过本接口创建的对象，不处理外部地址和其他账号文件。 */
    public static String previousStorageKey(String userId, String url) {
        String prefix = "/api/v1/avatars/" + userId + "/";
        if (url == null || !url.startsWith(prefix)) return null;
        try {
            return storageKey(userId, url.substring(prefix.length()));
        } catch (NotFoundException exception) {
            return null;
        }
    }

    /** 非法格式统一返回受控错误，避免暴露解码器内部信息。 */
    private static StorageException invalidImage() {
        return new StorageException(ErrorCode.UNSUPPORTED_FILE_TYPE, "头像仅支持可正常解码的 PNG/JPEG 图片");
    }
}
