package com.qaqxianyuqaq.backend.domain.vo.contestant;

import java.util.List;

/** CSV 导入结果摘要。 */
public record ImportResultVO(int imported, int skipped, int failed, List<ImportFailureVO> items) {
}
