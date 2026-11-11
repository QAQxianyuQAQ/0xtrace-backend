package com.qaqxianyuqaq.backend.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;

class AuditSnapshotUtilTest {
    @Test
    void shouldUnwrapAndKeepOnlySafeFieldsAtEveryDepth() {
        String safe = AuditSnapshotUtil.snapshot(ResponseEntity.ok(Map.of("id", "team", "answer", "private-flag",
                "unknownSecret", "private-unknown", "team", Map.of("name", "retained", "joinCode", "private-join"))));
        assertThat(safe).contains("retained", "team").doesNotContain("private-flag", "private-unknown", "private-join", "joinCode");
        assertThat(AuditSnapshotUtil.sanitize("not-json-secret")).isNull();
        assertThat(AuditSnapshotUtil.sanitize("\"plain-private-string\"")).isNull();
    }
    @Test
    void shouldNormalizeTimeOffsetsAndRejectInvalidOrReversedRanges() {
        RecordTimeRange range = RecordTimeRange.parse("2026-11-11T08:00:00+08:00", "2026-11-11T00:00:01Z");
        assertThat(range.from().toString()).isEqualTo("2026-11-11T00:00");
        assertThatThrownBy(() -> RecordTimeRange.parse("invalid", null)).isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> RecordTimeRange.parse("2026-11-12T00:00:00Z", "2026-11-11T00:00:00Z"))
                .isInstanceOf(ValidationFailedException.class);
    }
}
