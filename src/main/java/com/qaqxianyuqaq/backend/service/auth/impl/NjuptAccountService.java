package com.qaqxianyuqaq.backend.service.auth.impl;

import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.exception.BusinessException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.repository.user.NjuptIdentityMapper;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** 首次学校登录只建立普通选手账号；创建和身份绑定必须原子提交。 */
@Service
public class NjuptAccountService {
    private final UserMapper users;
    private final NjuptIdentityMapper identities;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;

    public NjuptAccountService(UserMapper users, NjuptIdentityMapper identities,
                               PasswordEncoder encoder, PlatformTransactionManager manager) {
        this.users = users;
        this.identities = identities;
        this.encoder = encoder;
        this.transaction = new TransactionTemplate(manager);
    }

    public User resolve(String subject) {
        // 1. 在独立事务中读取或创建关联，任何约束冲突均回滚整次创建。
        try {
            return transaction.execute(status -> createOrLoad(subject));
        } catch (DuplicateKeyException exception) {
            // 2. 并发首次登录只接受另一事务已提交的同一身份，不接管同名本地账号。
            return transaction.execute(status -> load(subject));
        }
    }

    private User createOrLoad(String subject) {
        // 1. 已存在的身份保持原账号、队伍、积分及停用状态。
        if (identities.findUserId(subject) != null) return load(subject);

        // 2. 使用专属用户名与不可知随机密码建立普通选手，学校密码不进入数据库。
        User user = new User();
        user.setId(IdGenerator.next("usr"));
        user.setUsername("njupt:" + subject);
        user.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));
        user.setRole(UserRole.CONTESTANT);
        user.setStatus(AccountStatus.ACTIVE);
        user.setNickname(subject);
        user.setTokenVersion(0);
        user.setCreatedAt(TimeUtil.now());
        users.insert(user);

        // 3. 唯一关联随账号原子提交，禁止一个学校身份创建多个赛事身份。
        identities.insert(subject, user.getId());
        return user;
    }

    private User load(String subject) {
        // 1. 仅从专属关联回查账号；缺失关联时拒绝按用户名猜测绑定。
        String userId = identities.findUserId(subject);
        User user = userId == null ? null : users.selectById(userId);
        if (user == null) throw new BusinessException(ErrorCode.CONFLICT, "学校身份关联冲突，请联系赛事管理员");
        return user;
    }
}
