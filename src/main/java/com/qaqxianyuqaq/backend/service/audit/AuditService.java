package com.qaqxianyuqaq.backend.service.audit;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.audit.AuditCommandDTO;
import com.qaqxianyuqaq.backend.domain.vo.admin.AuditLogVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;

/** 审计服务：写入与检索后台操作审计记录。 */
public interface AuditService {

    /** 记录一条审计日志。 */
    void record(AuditCommandDTO command);

    /** 分页检索审计日志。 */
    PageVO<AuditLogVO> page(String action, String entityType, String actor, PageQueryDTO query);
}
