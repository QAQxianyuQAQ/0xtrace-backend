package com.qaqxianyuqaq.backend.domain.vo.common;

import java.util.List;

/** 统一分页响应：items 为当前页数据，pagination 为分页元信息。 */
public record PageVO<T>(List<T> items, PaginationVO pagination) {
}
