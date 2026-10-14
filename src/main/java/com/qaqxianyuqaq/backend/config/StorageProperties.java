package com.qaqxianyuqaq.backend.config;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 上传限制参数（对应 tracequest.storage）。 */
@Getter
@Setter
public class StorageProperties {

    /** 附件单文件上限，默认 50MB。 */
    private long maxAttachmentBytes = 50L * 1024 * 1024;

    /** 封面单文件上限，契约规定 10MB。 */
    private long maxCoverBytes = 10L * 1024 * 1024;

    /** 允许的封面 MIME 类型。 */
    private List<String> coverContentTypes = List.of("image/png", "image/jpeg", "image/webp", "image/avif");

    /** 附件禁止上传可执行类型，避免处置风险。 */
    private List<String> blockedAttachmentExtensions =
            List.of("exe", "dll", "so", "sh", "bat", "cmd", "msi", "jar", "php", "jsp");
}
