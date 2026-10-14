package com.qaqxianyuqaq.backend.util;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PaginationVO;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 分页参数与响应装配工具。
 *
 * <p>契约要求：page 从 1 开始、pageSize 默认 20 且最大 100。</p>
 */
public final class PageUtil {

    /** 默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数上限，防止一次拉取过多数据。 */
    private static final int MAX_PAGE_SIZE = 100;

    private PageUtil() {
    }

    /** 归一化外部传入的分页参数。 */
    public static PageQueryDTO normalize(Integer page, Integer pageSize) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return new PageQueryDTO(safePage, safeSize);
    }

    /** 由持久层分页结果装配统一分页响应。 */
    public static <T> PageVO<T> toPage(IPage<?> source, List<T> items) {
        return new PageVO<>(items, toPagination(source));
    }

    /** 构造分页元信息。 */
    public static PaginationVO toPagination(IPage<?> source) {
        return new PaginationVO((int) source.getCurrent(), (int) source.getSize(), source.getTotal(),
                (int) source.getPages());
    }

    /** 由内存列表构造分页元信息，供缓存命中或无聚合查询的场景使用。 */
    public static PaginationVO of(int page, int pageSize, long total) {
        int totalPages = pageSize <= 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PaginationVO(page, pageSize, total, totalPages);
    }

    /** 裁剪内存列表为当前页数据。 */
    public static <T> List<T> slice(List<T> source, PageQueryDTO query) {
        int from = Math.min((query.page() - 1) * query.pageSize(), source.size());
        int to = Math.min(from + query.pageSize(), source.size());
        return source.subList(from, to);
    }
}
