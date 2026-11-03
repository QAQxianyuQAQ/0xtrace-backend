package com.qaqxianyuqaq.backend.service.contestant.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.*;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.repository.user.*;
import com.qaqxianyuqaq.backend.service.cache.SessionService;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContestantQueryTest {
    @Mock UserMapper userMapper;
    @Mock TeamMapper teamMapper;
    @Mock TeamMemberMapper teamMemberMapper;
    @Mock SolveMapper solveMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock SessionService sessionService;
    @Mock TeamService teamService;
    @Mock EventService eventService;
    @InjectMocks ContestantServiceImpl service;

    @Test void noEventStillListsExistingAccountsAndSearchesByUsername() {
        when(eventService.requireCurrent()).thenThrow(new NotFoundException("赛事尚未配置"));
        User user = new User(); user.setId("u1"); user.setUsername("member");
        user.setRole(UserRole.CONTESTANT); user.setStatus(AccountStatus.ACTIVE);
        Page<User> page = new Page<>(1, 20, 1); page.setRecords(List.of(user));
        when(userMapper.selectPage(any(), any())).thenReturn(page);
        var result = service.page("member", null, null, new PageQueryDTO(1, 20));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().username()).isEqualTo("member");
        assertThat(result.items().getFirst().team()).isNull();
        verifyNoInteractions(teamMapper, teamMemberMapper);
    }

    @Test void noEventTeamFilterReturnsEmptyPage() {
        when(eventService.requireCurrent()).thenThrow(new NotFoundException("赛事尚未配置"));
        assertThat(service.page(null, null, "team1", new PageQueryDTO(1, 20)).items()).isEmpty();
        verifyNoInteractions(userMapper, teamMapper, teamMemberMapper);
    }

    @Test void unexpectedEventFailureIsNotHidden() {
        when(eventService.requireCurrent()).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.page(null, null, null, new PageQueryDTO(1, 20)))
                .isInstanceOf(IllegalStateException.class);
    }
}
