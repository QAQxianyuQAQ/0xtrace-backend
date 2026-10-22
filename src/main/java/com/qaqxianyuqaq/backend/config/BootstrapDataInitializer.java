package com.qaqxianyuqaq.backend.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.repository.event.EventMapper;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 初始化数据装配。
 *
 * <p>首次启动时创建管理员账号与当前赛事记录，便于本地与测试环境直接使用；已存在数据时不覆盖。</p>
 */
@Component
public class BootstrapDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDataInitializer.class);

    /** 默认赛事开放时长，交由管理员通过接口调整。 */
    private static final Duration DEFAULT_EVENT_DURATION = Duration.ofDays(30);

    private final TraceQuestProperties properties;

    private final UserMapper userMapper;

    private final EventMapper eventMapper;

    private final PasswordEncoder passwordEncoder;

    public BootstrapDataInitializer(TraceQuestProperties properties, UserMapper userMapper, EventMapper eventMapper,
                                    PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.eventMapper = eventMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getBootstrap().isEnabled()) {
            return;
        }
        // 1. 无管理员账号时创建默认管理员，密码以 BCrypt 存储
        boolean hasAdmin = userMapper.exists(Wrappers.<User>lambdaQuery().eq(User::getRole, UserRole.ADMIN));
        if (!hasAdmin) {
            User admin = new User();
            admin.setId(IdGenerator.next("usr"));
            admin.setUsername(properties.getBootstrap().getAdminUsername());
            admin.setPasswordHash(passwordEncoder.encode(properties.getBootstrap().getAdminPassword()));
            admin.setRole(UserRole.ADMIN);
            admin.setStatus(AccountStatus.ACTIVE);
            admin.setTokenVersion(0);
            admin.setCreatedAt(TimeUtil.now());
            userMapper.insert(admin);
            log.info("已创建默认管理员账号: {}", admin.getUsername());
        }

        // 2. 无赛事记录时创建默认赛事，状态开放并预留 30 天窗口
        if (eventMapper.selectCount(null) == 0) {
            EventConfig event = new EventConfig();
            event.setId(IdGenerator.next("evt"));
            event.setName(properties.getBootstrap().getEventName());
            event.setStatus(EventStatus.OPEN);
            event.setTimezone(properties.getBootstrap().getEventTimezone());
            event.setStartsAt(TimeUtil.now());
            event.setEndsAt(TimeUtil.now().plus(DEFAULT_EVENT_DURATION));
            event.setMaxTeamSize(4);
            event.setCreatedAt(TimeUtil.now());
            event.setUpdatedAt(TimeUtil.now());
            eventMapper.insert(event);
            log.info("已创建默认赛事: {}", event.getName());
        }
    }
}
