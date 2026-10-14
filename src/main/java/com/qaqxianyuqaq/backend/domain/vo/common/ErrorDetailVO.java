package com.qaqxianyuqaq.backend.domain.vo.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 错误明细：错误码、可对外披露的描述与字段级原因。 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorDetailVO(String code, String message, List<FieldErrorVO> details) {
}
