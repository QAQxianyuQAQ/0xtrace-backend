package com.qaqxianyuqaq.backend.service.admin.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.dto.admin.AdminRoleUpdateDTO;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.domain.vo.admin.ManagedAccountVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.admin.AdminAccountService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 最高管理员账号权限服务实现。 */
@Service
public class AdminAccountServiceImpl implements AdminAccountService {

    private final UserMapper userMapper;
    private final PermissionAssert permissionAssert;

    public AdminAccountServiceImpl(UserMapper userMapper, PermissionAssert permissionAssert) {
        this.userMapper = userMapper;
        this.permissionAssert = permissionAssert;
    }

    @Override
    public PageVO<ManagedAccountVO> administrators(String q, PageQueryDTO query, UserPrincipal operator) {
        // 1. 只允许最高管理员读取其他管理员账号
        permissionAssert.assertSuperAdmin(operator);

        // 2. 只查询普通管理员，确保最高管理员身份不会出现在可管理列表
        var wrapper = Wrappers.<User>lambdaQuery().eq(User::getRole, UserRole.ADMIN)
                .like(q != null && !q.isBlank(), User::getUsername, q == null ? null : q.trim())
                .orderByDesc(User::getCreatedAt);
        IPage<User> page = userMapper.selectPage(new Page<>(query.page(), query.pageSize()), wrapper);

        // 3. 返回无凭据的账号摘要并保留统一分页元信息
        List<ManagedAccountVO> items = page.getRecords().stream().map(this::toVO).toList();
        return PageUtil.toPage(page, items);
    }

    @Override
    @Transactional
    public ManagedAccountVO updateRole(String userId, AdminRoleUpdateDTO request, UserPrincipal operator) {
        // 1. 在业务边界再次断言操作者权限，避免绕过控制器调用服务
        permissionAssert.assertSuperAdmin(operator);

        // 2. 仅接受普通管理员与选手两种目标角色
        UserRole targetRole = switch (request.role()) {
            case "admin" -> UserRole.ADMIN;
            case "contestant" -> UserRole.CONTESTANT;
            default -> throw new ValidationFailedException("role", "仅支持 admin 或 contestant");
        };

        // 3. 读取目标并保护最高管理员与操作者本人
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("账号不存在");
        }
        if (user.getRole() == UserRole.SUPER_ADMIN || userId.equals(operator.userId())) {
            throw new ConflictException("最高管理员账号不可在普通管理员管理中变更");
        }
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.CONTESTANT) {
            throw new ConflictException("该账号角色不可变更");
        }

        // 4. 角色未变化时保持幂等，否则原子更新角色并递增令牌版本
        if (user.getRole() != targetRole) {
            int updated = userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, userId)
                    .eq(User::getRole, user.getRole())
                    .set(User::getRole, targetRole)
                    .setSql("token_version = COALESCE(token_version, 0) + 1"));
            if (updated != 1) {
                throw new ConflictException("账号权限已变更，请刷新后重试");
            }
            user = userMapper.selectById(userId);
        }

        // 5. 返回变更后的非敏感账号信息
        return toVO(user);
    }

    /** 组装普通管理员安全摘要。 */
    private ManagedAccountVO toVO(User user) {
        return new ManagedAccountVO(user.getId(), user.getUsername(), user.getRole().apiValue(),
                user.getStatus().apiValue(), user.getCreatedAt(), user.getLastLoginAt());
    }
}
