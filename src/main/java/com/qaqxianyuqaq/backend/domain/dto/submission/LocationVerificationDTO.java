package com.qaqxianyuqaq.backend.domain.dto.submission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 位置核验请求：payload 为现场二维码原始内容。 */
public record LocationVerificationDTO(
        @NotBlank(message = "二维码内容不能为空")
        @Size(max = 256, message = "二维码内容过长")
        String payload,
        @Size(max = 64, message = "requestId 长度不能超过 64")
        String requestId) {
}
