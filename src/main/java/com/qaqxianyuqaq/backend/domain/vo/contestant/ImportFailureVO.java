package com.qaqxianyuqaq.backend.domain.vo.contestant;

/** CSV 导入失败明细。 */
public record ImportFailureVO(int line, String username, String reason) {
}
