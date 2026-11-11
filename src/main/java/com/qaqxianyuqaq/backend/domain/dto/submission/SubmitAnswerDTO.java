package com.qaqxianyuqaq.backend.domain.dto.submission;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.qaqxianyuqaq.backend.util.StrictStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 答案提交请求。 */
public record SubmitAnswerDTO(
        @NotBlank(message = "答案不能为空")
        @Size(max = 512, message = "答案长度不能超过 512")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String answer,
        @Size(max = 64, message = "requestId 长度不能超过 64")
        String requestId) {
}
