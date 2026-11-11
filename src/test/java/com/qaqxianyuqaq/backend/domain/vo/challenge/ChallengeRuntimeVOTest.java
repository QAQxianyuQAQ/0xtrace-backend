package com.qaqxianyuqaq.backend.domain.vo.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 靶机浏览器入口契约：只返回受鉴权同源网关路径。 */
class ChallengeRuntimeVOTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldSerializeExplicitEntryForReadyRuntime() throws Exception {
        var result = json.valueToTree(runtime("inst_1", "MISSING", "READY", 3600L));
        assertThat(result.path("entryPath").asText()).isEqualTo("/entry/MISSING/");
        assertThat(result.path("status").asText()).isEqualTo("READY");
        assertThat(result.path("containerIp").asText()).isEqualTo("172.23.0.3");
    }

    @Test
    void shouldNotOfferStoppedFailedOrExpiredTargets() {
        for (String state : new String[]{"STOPPED", "FAILED", "PENDING", "PULLING", "STARTING"}) {
            assertThat(json.valueToTree(runtime("inst_1", "MISSING", state, 3600L)).path("entryPath").isNull()).isTrue();
        }
        assertThat(json.valueToTree(runtime(null, "MISSING", "READY", 3600L)).path("entryPath").isNull()).isTrue();
        assertThat(json.valueToTree(runtime("inst_1", "MISSING", "READY", 0L)).path("entryPath").isNull()).isTrue();
    }

    @Test
    void shouldEncodeChallengeAsOnePathSegment() {
        assertThat(json.valueToTree(runtime("inst_1", "//other.example?q#x", "READY", 3600L))
                .path("entryPath").asText()).isEqualTo("/entry/%2F%2Fother.example%3Fq%23x/");
    }

    private ChallengeRuntimeVO runtime(String id, String code, String status, Long seconds) {
        return new ChallengeRuntimeVO(id, code, "team", status, "172.23.0.3", null, null, 5000,
                "wss://example.test/api/v1/challenge-connections", null, null, null, seconds);
    }
}
