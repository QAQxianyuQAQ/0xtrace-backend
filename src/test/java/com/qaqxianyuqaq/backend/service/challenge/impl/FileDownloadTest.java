package com.qaqxianyuqaq.backend.service.challenge.impl;

import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.controller.file.FileController;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeFile;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.domain.enums.FileKind;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeFileMapper;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.challenge.FileStorageService;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileDownloadTest {
    @Mock MinioClient minioClient;
    @Mock TraceQuestProperties properties;
    @Mock ChallengeMapper challengeMapper;
    @Mock ChallengeFileMapper challengeFileMapper;
    @Mock CacheService cacheService;
    @InjectMocks FileStorageServiceImpl service;
    final UserPrincipal player = new UserPrincipal("u1", "player", UserRole.CONTESTANT, null, 0);
    final UserPrincipal admin = new UserPrincipal("a1", "admin", UserRole.ADMIN, null, 0);
    ChallengeFile file;
    Challenge challenge;

    @BeforeEach void setUp() {
        file = new ChallengeFile(); file.setId("f1"); file.setChallengeId("c1");
        challenge = new Challenge(); challenge.setId("c1");
    }

    @ParameterizedTest
    @EnumSource(value = ChallengeStatus.class, names = {"DRAFT", "ARCHIVED"})
    void hiddenChallengeFilesCannotBeDownloadedByPlayers(ChallengeStatus status) {
        prepare(status);
        assertThatThrownBy(() -> service.requireFile("f1", player)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(minioClient);
    }

    @ParameterizedTest
    @EnumSource(value = ChallengeStatus.class, names = {"PUBLISHED", "PAUSED"})
    void visibleChallengeFilesRemainDownloadable(ChallengeStatus status) {
        prepare(status);
        assertThat(service.requireFile("f1", player)).isSameAs(file);
    }

    @Test void adminCanPreviewDraftFile() {
        prepare(ChallengeStatus.DRAFT);
        assertThat(service.requireFile("f1", admin)).isSameAs(file);
    }

    @Test void orphanFileIsNotDownloadableEvenByAdmin() {
        when(challengeFileMapper.selectById("f1")).thenReturn(file);
        assertThatThrownBy(() -> service.requireFile("f1", admin)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(minioClient);
    }

    @Test void missingFileReturnsNotFoundWithoutOpeningStorage() {
        assertThatThrownBy(() -> service.requireFile("f1", player)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(challengeMapper, minioClient);
    }

    @Test void sameOriginResponseKeepsFilenameAndClosesStorageStream() throws IOException {
        FileStorageService storage = mock(FileStorageService.class);
        file.setKind(FileKind.ATTACHMENT); file.setOriginalName("现场资料.txt");
        file.setSizeBytes(3L); file.setContentType("text/html");
        when(storage.requireFile("f1", player)).thenReturn(file);
        AtomicBoolean closed = new AtomicBoolean();
        var stream = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        };
        when(storage.openFile(file)).thenReturn(stream);
        var response = new FileController(storage).download("f1", player);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getLocation()).isNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/octet-stream");
        var disposition = ContentDisposition.parse(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(disposition.getFilename()).isEqualTo("现场资料.txt");
        assertThat(disposition.getType()).isEqualTo("attachment");
        verify(storage, never()).openFile(file);
        var output = new MockHttpOutputMessage();
        new ResourceHttpMessageConverter().write(response.getBody(), response.getHeaders().getContentType(), output);
        assertThat(output.getBodyAsBytes()).containsExactly(1, 2, 3);
        assertThat(closed).isTrue();
    }

    private void prepare(ChallengeStatus status) {
        challenge.setStatus(status);
        when(challengeFileMapper.selectById("f1")).thenReturn(file);
        when(challengeMapper.selectById("c1")).thenReturn(challenge);
    }
}
