package com.qaqxianyuqaq.backend.service.challenge.impl;

import com.qaqxianyuqaq.backend.domain.dto.challenge.ChallengeUpdateDTO;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.enums.*;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.challenge.*;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.security.CredentialCipher;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.challenge.*;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChallengePublicationTest {
    @Mock ChallengeMapper challengeMapper;
    @Mock ChallengeFileMapper challengeFileMapper;
    @Mock SolveMapper solveMapper;
    @Mock CacheService cacheService;
    @Mock EventService eventService;
    @Mock FileStorageService fileStorageService;
    @Mock CredentialCipher credentialCipher;
    @Mock TeamService teamService;
    @Mock ChallengeContainerService challengeContainerService;
    @InjectMocks ChallengeServiceImpl service;
    Challenge challenge;

    @BeforeEach void draft() {
        challenge = new Challenge(); challenge.setId("c1"); challenge.setCode("TEST-01");
        challenge.setTitle("Test"); challenge.setCategory("Web"); challenge.setDifficulty("Easy");
        challenge.setPoints(400); challenge.setVersion(0); challenge.setStatus(ChallengeStatus.DRAFT);
        challenge.setRuntimeType(ChallengeRuntimeType.MANUAL);
        when(challengeMapper.selectOne(any())).thenReturn(challenge);
    }
    @Test void patchCannotPublishWithoutAnAnswer() {
        assertThatThrownBy(() -> service.update("TEST-01", patch("published"))).isInstanceOf(ValidationFailedException.class);
        verify(challengeMapper, never()).updateById(any(Challenge.class));
    }
    @Test void dedicatedPublishHasTheSameAnswerValidation() {
        assertThatThrownBy(() -> service.changeStatus("TEST-01", ChallengeStatus.PUBLISHED)).isInstanceOf(ValidationFailedException.class);
        verify(challengeMapper, never()).updateById(any(Challenge.class));
    }
    @Test void patchPublishesCompleteManualChallenge() {
        challenge.setAnswerSecret("encrypted");
        when(challengeFileMapper.selectList(any())).thenReturn(List.of());
        var result = service.update("TEST-01", patch("published"));
        assertThat(result.status()).isEqualTo("published");
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.publishedAt()).isNotNull();
        verify(challengeMapper).updateById(challenge);
    }
    @Test void editingValidPublishedChallengeStillWorks() {
        challenge.setAnswerSecret("encrypted"); challenge.setStatus(ChallengeStatus.PUBLISHED);
        when(challengeFileMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.update("TEST-01", patch(null)).status()).isEqualTo("published");
    }
    @Test void statusChangeCannotPublishIncompleteDockerRuntime() {
        challenge.setRuntimeType(ChallengeRuntimeType.DOCKER); challenge.setDockerImage("test/image");
        assertThatThrownBy(() -> service.changeStatus("TEST-01", ChallengeStatus.PUBLISHED)).isInstanceOf(ValidationFailedException.class);
    }
    @Test void clearingDockerFieldsCannotBecomePublishedManualChallengeWithoutAnswer() {
        challenge.setRuntimeType(ChallengeRuntimeType.DOCKER);
        var update = new ChallengeUpdateDTO(null, null, null, null, null, null, null, "", "", "", "", null, "published", 0);
        assertThatThrownBy(() -> service.update("TEST-01", update)).isInstanceOf(ValidationFailedException.class);
        verify(challengeMapper, never()).updateById(any(Challenge.class));
    }
    private ChallengeUpdateDTO patch(String status) {
        return new ChallengeUpdateDTO(null, null, null, null, null, null, null, null, null, null, null, null, status, 0);
    }
}
