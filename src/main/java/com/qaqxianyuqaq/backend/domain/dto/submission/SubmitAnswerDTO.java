package com.qaqxianyuqaq.backend.domain.dto.submission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 答案提交请求。 */
public record SubmitAnswerDTO(
        @NotBlank(message = "答案不能为空")
        @Size(max = 512, message = "答案长度不能超过 512")
        String answer,
        @Size(max = 64, message = "requestId 长度不能超过 64")
        String requestId) {
}
