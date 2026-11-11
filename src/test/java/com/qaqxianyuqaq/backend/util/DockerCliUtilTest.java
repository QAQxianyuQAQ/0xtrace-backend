package com.qaqxianyuqaq.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import com.qaqxianyuqaq.backend.exception.ContainerRuntimeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Docker 输出解析测试：覆盖宿主机告警先于容器标识的情况。 */
class DockerCliUtilTest {
    @TempDir
    Path directory;

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
    @Test
    @DisplayName("已有镜像时完全不访问仓库")
    void shouldUseCachedImageWithoutPulling() throws Exception {
        Path docker = script("if [ \"$1 $2\" = 'image inspect' ]; then echo sha256:cached; exit 0; fi\nexit 91");
        DockerCliUtil.pull(docker.toString(), "local/challenge", "tq", Duration.ofSeconds(1));
        assertThat(Files.readString(directory.resolve("commands"))).contains("image inspect").doesNotContain("pull ");
    }

    @Test
    @DisplayName("缺失本地镜像时拉取一次")
    void shouldPullMissingImageOnce() throws Exception {
        Path docker = script("if [ \"$1 $2\" = 'image inspect' ]; then exit 1; fi\n[ \"$1\" = pull ]");
        DockerCliUtil.pull(docker.toString(), "local/challenge", "tq", Duration.ofSeconds(1));
        assertThat(Files.readAllLines(directory.resolve("commands"))).hasSize(2);
        assertThat(Files.readString(directory.resolve("commands"))).contains("pull local/challenge:tq");
    }

    @Test
    @DisplayName("拉取失败转为稳定运行时错误且不暴露仓库输出")
    void shouldSanitizeRegistryFailure() throws Exception {
        Path docker = script("echo 'private registry output'; exit 1");
        assertThatThrownBy(() -> DockerCliUtil.pull(docker.toString(), "local/challenge", "tq", Duration.ofSeconds(1)))
                .isInstanceOf(ContainerRuntimeException.class).hasMessageContaining("预加载镜像")
                .hasMessageNotContaining("private registry");
    }

    @Test
    @DisplayName("容器启动禁止 Docker 隐式拉取")
    void shouldNeverPullDuringRun() throws Exception {
        Path docker = script("echo '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'");
        DockerCliUtil.startContainer(docker.toString(), "tq-test", "chal", "team", "runtime", "local/challenge",
                "tq", "FLAG", "test-only", 8080, "64m", "0.1", 32, Duration.ofSeconds(1));
        assertThat(Files.readString(directory.resolve("commands"))).contains("--pull=never");
    }

    @Test
    @DisplayName("删除失败且容器仍存在时必须报错，禁止把失败当作成功")
    void shouldRejectRemovalFailureForExistingContainer() throws Exception {
        Path docker = script("if [ \"$1\" = container ]; then echo tq-existing; exit 0; fi\nexit 1");
        assertThatThrownBy(() -> DockerCliUtil.removeQuietly(docker.toString(), "tq-existing", Duration.ofSeconds(1)))
                .isInstanceOf(ContainerRuntimeException.class).hasMessageContaining("原实例已保留");
    }

    @Test
    @DisplayName("删除失败且 Docker 不可用时禁止假定容器已经不存在")
    void shouldRejectRemovalWhenAbsenceCannotBeConfirmed() throws Exception {
        Path docker = script("echo 'private daemon failure'; exit 1");
        assertThatThrownBy(() -> DockerCliUtil.removeQuietly(docker.toString(), "tq-existing", Duration.ofSeconds(1)))
                .isInstanceOf(ContainerRuntimeException.class).hasMessageNotContaining("private daemon");
    }

    @Test
    @DisplayName("仅在只读查询确认目标不存在时允许忽略删除错误")
    void shouldAllowRemovalOfConfirmedMissingContainer() throws Exception {
        Path docker = script("if [ \"$1\" = container ]; then echo tq-other; exit 0; fi\nexit 1");
        DockerCliUtil.removeQuietly(docker.toString(), "tq-missing", Duration.ofSeconds(1));
        assertThat(Files.readString(directory.resolve("commands"))).contains("container ls");
    }

    private Path script(String body) throws Exception {
        Path file = directory.resolve("docker");
        Files.writeString(file, "#!/bin/sh\necho \"$*\" >> '" + directory.resolve("commands") + "'\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
