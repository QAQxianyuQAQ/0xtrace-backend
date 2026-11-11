package com.qaqxianyuqaq.backend.service.cache.impl;

import com.qaqxianyuqaq.backend.domain.entity.IdempotencyRecord;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.repository.common.IdempotencyRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 并发唯一键冲突必须返回首个快照，不能吞掉不同内容或其他数据库错误。 */
class IdempotencyServiceImplTest {
    private final IdempotencyRecordMapper mapper = mock(IdempotencyRecordMapper.class);
    private final IdempotencyServiceImpl service = new IdempotencyServiceImpl(mapper);

    @Test void shouldReturnTheSavedSnapshotAfterConcurrentSameContentInsert() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setRequestHash("same-hash"); existing.setResponseJson("first-response");
        when(mapper.insert(any(IdempotencyRecord.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectOne(any())).thenReturn(existing);
        assertThat(service.saveResponse("submission", "key", "user", "same-hash", "later-response"))
                .isEqualTo("first-response");
    }

    @Test void shouldRejectConcurrentDifferentContent() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setRequestHash("first-hash"); existing.setResponseJson("first-response");
        when(mapper.insert(any(IdempotencyRecord.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectOne(any())).thenReturn(existing);
        assertThatThrownBy(() -> service.saveResponse("submission", "key", "user", "different-hash", "later-response"))
                .isInstanceOf(ConflictException.class);
    }

    @Test void shouldKeepUnexpectedPrimaryKeyCollisionsVisible() {
        DuplicateKeyException collision = new DuplicateKeyException("unrelated primary key collision");
        when(mapper.insert(any(IdempotencyRecord.class))).thenThrow(collision);
        assertThatThrownBy(() -> service.saveResponse("submission", "key", "user", "hash", "response"))
                .isSameAs(collision);
    }

    @Test void shouldSaveTheFirstSnapshotNormally() {
        assertThat(service.saveResponse("submission", "key", "user", "hash", "response")).isEqualTo("response");
        verify(mapper).insert(any(IdempotencyRecord.class));
        verify(mapper, never()).selectOne(any());
    }
}
