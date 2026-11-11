package com.qaqxianyuqaq.backend.aspect;

import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamJoinDTO;
import com.qaqxianyuqaq.backend.domain.enums.SolveSource;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.impl.ChallengeContainerServiceImpl;
import com.qaqxianyuqaq.backend.service.contestant.impl.TeamServiceImpl;
import com.qaqxianyuqaq.backend.service.submission.impl.ScoringServiceImpl;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/** 分布式锁键解析测试。 */
class DistributedLockAspectTest {

    @ParameterizedTest
    @ValueSource(strings = {"p1", "a1"})
    void shouldResolveIndexedAliasWithoutParameterMetadata(String alias) throws Throwable {
        CacheService cacheService = mock(CacheService.class);
        DistributedLockAspect aspect = new DistributedLockAspect(cacheService);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        DistributedLock lock = mock(DistributedLock.class);
        Team team = new Team();
        team.setId("team_42");
        Method method = BiConsumer.class.getMethod("accept", Object.class, Object.class);
        assertThat(method.getParameters()).allMatch(parameter -> !parameter.isNamePresent());

        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"challenge_1", team});
        when(lock.key()).thenReturn("'team-instance:' + #" + alias + ".id");
        when(lock.waitMillis()).thenReturn(100L);
        when(lock.leaseMillis()).thenReturn(1000L);
        when(cacheService.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.lockAndProceed(joinPoint, lock);

        assertThat(result).isEqualTo("ok");
        verify(cacheService).setIfAbsent(eq(CacheKeys.lock("team-instance:team_42")),
                anyString(), eq(Duration.ofMillis(1000)));
    }

    @ParameterizedTest
    @MethodSource("lockedMethods")
    void shouldUseCorrectArgumentForEveryLock(Method method, Object[] args, String expectedKey) throws Throwable {
        CacheService cacheService = mock(CacheService.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
        when(cacheService.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());

        new DistributedLockAspect(cacheService).lockAndProceed(joinPoint, method.getAnnotation(DistributedLock.class));

        verify(cacheService).setIfAbsent(eq(CacheKeys.lock(expectedKey)), anyString(), any(Duration.class));
        verify(joinPoint).proceed();
    }

    private static Stream<Arguments> lockedMethods() throws NoSuchMethodException {
        Challenge challenge = new Challenge();
        challenge.setId("challenge_1");
        Team team = new Team();
        team.setId("team_42");
        UserPrincipal principal = new UserPrincipal("user_1", "tester", UserRole.CONTESTANT, null, 0);
        return Stream.of(
                Arguments.of(ChallengeContainerServiceImpl.class.getMethod("ensureStarted", Challenge.class, Team.class),
                        new Object[]{challenge, team}, "team-instance:team_42"),
                Arguments.of(ChallengeContainerServiceImpl.class.getMethod("extend", Challenge.class, Team.class),
                        new Object[]{challenge, team}, "team-instance-extend:team_42"),
                Arguments.of(TeamServiceImpl.class.getMethod("createTeam", String.class, TeamCreateDTO.class, UserPrincipal.class),
                        new Object[]{"event_1", new TeamCreateDTO("测试队伍"), principal}, "team-create:event_1:user_1"),
                Arguments.of(TeamServiceImpl.class.getMethod("joinTeam", String.class, TeamJoinDTO.class, UserPrincipal.class),
                        new Object[]{"event_1", new TeamJoinDTO("ABCDEF"), principal}, "team-join:event_1:ABCDEF"),
                Arguments.of(ScoringServiceImpl.class.getMethod("award", UserPrincipal.class, Challenge.class,
                                SolveSource.class, String.class, String.class, boolean.class, Team.class),
                        new Object[]{principal, challenge, SolveSource.ANSWER, "answer", "req_1", true, team}, "challenge_1"));
    }
}
