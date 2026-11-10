package com.qaqxianyuqaq.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Docker 输出解析测试：覆盖宿主机告警先于容器标识的情况。 */
class DockerCliUtilTest {

    @Test
    @DisplayName("跳过 Docker 告警并提取完整容器标识")
    void shouldExtractContainerIdAfterWarning() {
        String containerId = "0123456789abcdef".repeat(4);

        assertThat(DockerCliUtil.parseContainerId("WARNING: cgroup is not mounted\n" + containerId))
                .isEqualTo(containerId);
    }

    @Test
    @DisplayName("没有有效容器标识时拒绝继续")
    void shouldRejectMissingContainerId() {
        assertThatThrownBy(() -> DockerCliUtil.parseContainerId("WARNING: Docker failed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Docker 未返回有效容器标识");
    }
}
