package com.qaqxianyuqaq.backend.domain.vo.common;

/** 分页元信息，与接口契约 8.1 的分页返回结构保持一致。 */
public record PaginationVO(int page, int pageSize, long total, int totalPages) {
}
