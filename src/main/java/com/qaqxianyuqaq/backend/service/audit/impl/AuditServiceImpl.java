package com.qaqxianyuqaq.backend.service.audit.impl;

import com.qaqxianyuqaq.backend.domain.dto.audit.AuditCommandDTO;
import com.qaqxianyuqaq.backend.domain.dto.admin.AuditLogQueryDTO;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.SecurityUtils;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.util.AuditSnapshotUtil;
import com.qaqxianyuqaq.backend.util.RecordTimeRange;
import com.qaqxianyuqaq.backend.util.RequestUtil;
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

    private final PermissionAssert permissionAssert;

    public AuditServiceImpl(AuditLogMapper auditLogMapper, PermissionAssert permissionAssert) {
        this.auditLogMapper = auditLogMapper;
        this.permissionAssert = permissionAssert;
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
        entity.setBeforeJson(AuditSnapshotUtil.sanitize(command.before()));
        entity.setAfterJson(AuditSnapshotUtil.sanitize(command.after()));
        entity.setRequestId(command.requestId());
        entity.setClientIp(command.clientIp());
        entity.setCreatedAt(TimeUtil.now());

        // 2. 落库保存，作为后台操作可追溯的权威记录
        auditLogMapper.insert(entity);
    }

    @Override
    public PageVO<AuditLogVO> page(AuditLogQueryDTO filter, PageQueryDTO query, UserPrincipal principal) {
        // 1. 完整审计包含跨账号操作和来源信息，仅允许最高管理员检索
        permissionAssert.assertSuperAdmin(principal);
        RecordTimeRange time = RecordTimeRange.parse(filter.from(), filter.to());
        String action = filter.action(), entityType = filter.entityType(), actor = filter.actor();
        // 2. 组装查询条件，动作、对象类型与操作者均为可选筛选项
        var wrapper = Wrappers.<AuditLog>lambdaQuery()
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .eq(entityType != null && !entityType.isBlank(), AuditLog::getEntityType, entityType)
                .like(actor != null && !actor.isBlank(), AuditLog::getActorName, actor)
                .eq(filter.actorId() != null && !filter.actorId().isBlank(), AuditLog::getActorId, filter.actorId())
                .eq(!"team".equals(entityType) && filter.entityId() != null && !filter.entityId().isBlank(),
                        AuditLog::getEntityId, filter.entityId())
                .eq(filter.requestId() != null && !filter.requestId().isBlank(), AuditLog::getRequestId, filter.requestId())
                .ge(time.from() != null, AuditLog::getCreatedAt, time.from())
                .le(time.to() != null, AuditLog::getCreatedAt, time.to())
                .orderByDesc(AuditLog::getCreatedAt).orderByDesc(AuditLog::getId);

        // 3. 队伍对象检索涵盖改队前后关系，避免仅在目标队伍可见
        if ("team".equals(entityType) && filter.entityId() != null && !filter.entityId().isBlank()) {
            String escaped = filter.entityId().replace("!", "!!").replace("%", "!%").replace("_", "!_");
            wrapper.apply("(entity_id = {0} OR before_json LIKE {1} ESCAPE '!' OR after_json LIKE {1} ESCAPE '!')",
                    filter.entityId(), "%\"teamId\":\"" + escaped + "\"%");
        }

        // 4. 执行分页查询，避免一次性拉取全部审计记录
        IPage<AuditLog> page = auditLogMapper.selectPage(new Page<>(query.page(), query.pageSize()), wrapper);

        // 5. 转换为对外响应模型
        List<AuditLogVO> items = page.getRecords().stream()
                .map(log -> new AuditLogVO(log.getId(), log.getActorName(), log.getActorId(),
                        log.getAction(), log.getEntityType(), log.getEntityId(), log.getRequestId(),
                        log.getClientIp(), AuditSnapshotUtil.sanitize(log.getBeforeJson()),
                        AuditSnapshotUtil.sanitize(log.getAfterJson()), log.getCreatedAt()))
                .toList();
        return PageUtil.toPage(page, items);
    }
    @Override
    public void recordTeamChange(String action, String teamId, String before, String after) {
        // 1. 复用当前认证身份与请求编号，不信任客户端自报操作者
        UserPrincipal actor = SecurityUtils.currentPrincipalOrNull();

        // 2. 在调用方现有事务中追加真实变更，禁止通过删除原记录伪造历史
        record(new AuditCommandDTO(actor == null ? null : actor.userId(),
                actor == null ? null : actor.getUsername(), action, "team", teamId,
                before, after, RequestUtil.currentRequestId(), null));
    }
}
