package com.qaqxianyuqaq.backend.domain.dto.challenge;

import jakarta.validation.constraints.Min;

/** 额外加分设置，均为非负整数。 */
public record BonusesDTO(
        @Min(value = 0, message = "首解加分不能为负") Integer first,
        @Min(value = 0, message = "第二解加分不能为负") Integer second,
        @Min(value = 0, message = "第三解加分不能为负") Integer third,
        @Min(value = 0, message = "其他名次加分不能为负") Integer other) {
}
