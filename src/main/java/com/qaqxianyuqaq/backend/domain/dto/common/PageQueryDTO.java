package com.qaqxianyuqaq.backend.domain.dto.common;

/**
 * 分页查询参数。
 *
 * @param page     页码，从 1 开始
 * @param pageSize 每页条数，默认 20、最大 100
 */
public record PageQueryDTO(Integer page, Integer pageSize) {
}
