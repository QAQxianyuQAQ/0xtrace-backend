package com.qaqxianyuqaq.backend.service.audit.impl;

import com.qaqxianyuqaq.backend.domain.dto.audit.AuditCommandDTO;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.admin.AuditLogVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.entity.AuditLog;
import com.qaqxianyuqaq.backend.repository.audit.AuditLogMapper;
import com.qaqxianyuqaq.backend.service.audit.AuditService;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.PageUtil;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.List;

/** 审计服务实现。 */
@Service
public class AuditServiceImpl implements AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditServiceImpl(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void record(AuditCommandDTO command) {
        // 1. 组装审计实体，补齐主键与时间戳
        AuditLog entity = new AuditLog();
        entity.setId(IdGenerator.next("audit"));
        entity.setActorId(command.actorId());
        entity.setActorName(command.actorName());
        entity.setAction(command.action());
        entity.setEntityType(command.entityType());
        entity.setEntityId(command.entityId());
        entity.setBeforeJson(command.before());
        entity.setAfterJson(command.after());
        entity.setRequestId(command.requestId());
        entity.setClientIp(command.clientIp());
        entity.setCreatedAt(TimeUtil.now());

        // 2. 落库保存，作为后台操作可追溯的权威记录
        auditLogMapper.insert(entity);
    }

    @Override
    public PageVO<AuditLogVO> page(String action, String entityType, String actor, PageQueryDTO query) {
        // 1. 组装查询条件，动作、对象类型与操作者均为可选筛选项
        var wrapper = Wrappers.<AuditLog>lambdaQuery()
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .eq(entityType != null && !entityType.isBlank(), AuditLog::getEntityType, entityType)
                .like(actor != null && !actor.isBlank(), AuditLog::getActorName, actor)
                .orderByDesc(AuditLog::getCreatedAt);

        // 2. 执行分页查询，避免一次性拉取全部审计记录
        IPage<AuditLog> page = auditLogMapper.selectPage(new Page<>(query.page(), query.pageSize()), wrapper);

        // 3. 转换为对外响应模型
        List<AuditLogVO> items = page.getRecords().stream()
                .map(log -> new AuditLogVO(log.getId(), log.getActorName(), log.getActorId(),
                        log.getAction(), log.getEntityType(), log.getEntityId(), log.getRequestId(),
                        log.getClientIp(), log.getBeforeJson(), log.getAfterJson(), log.getCreatedAt()))
                .toList();
        return PageUtil.toPage(page, items);
    }
}
