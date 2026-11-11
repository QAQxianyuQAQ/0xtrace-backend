package com.qaqxianyuqaq.backend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaqxianyuqaq.backend.exception.ContainerRuntimeException;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 单机 Docker CLI 工具。
 *
 * <p>所有命令均使用参数数组执行，禁止拼接 Shell 字符串；命令输出与异常不得包含注入 Flag。</p>
 */
public final class DockerCliUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DockerCliUtil() {
    }

    /** 容器检查结果，仅保留运行时需要的最小字段。 */
    public record ContainerInfo(String containerId, String state, String containerIp, String hostIp,
                                Integer hostPort) {
    }

    /** 执行 Docker 命令，超时后强制终止并抛出统一运行时异常。 */
    public static String run(String dockerBinary, List<String> arguments, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(dockerBinary);
        command.addAll(arguments);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (Exception exception) {
            throw new IllegalStateException("无法启动 Docker CLI", exception);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = process.getInputStream()) {
                stream.transferTo(output);
            } catch (Exception ignored) {
                // 进程终止时读取线程可能同步失败，由退出码统一判断结果
            }
        });

        try {
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("Docker 命令执行超时");
            }
            reader.join(Duration.ofSeconds(2).toMillis());
            String text = output.toString(StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Docker 命令执行失败: " + abbreviate(text));
            }
            return text;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Docker 命令被中断", exception);
        }
    }

    /** 确保题目容器内部网络存在且不允许直接访问外网。 */
    public static void ensureInternalNetwork(String dockerBinary, String network, Duration timeout) {
        try {
            run(dockerBinary, List.of("network", "inspect", network), timeout);
        } catch (IllegalStateException exception) {
            run(dockerBinary, List.of("network", "create", "--internal", network), timeout);
        }
    }

    /** 优先复用管理员已加载的镜像，缺失时才限时拉取，避免内网每次启动访问仓库。 */
    public static void pull(String dockerBinary, String image, String tag, Duration timeout) {
        String reference = image + ":" + tag;
        try {
            run(dockerBinary, List.of("image", "inspect", "--format", "{{.Id}}", reference), timeout);
            return;
        } catch (IllegalStateException ignored) {
            // 本地镜像不存在时才访问仓库；镜像更新由管理员预先拉取或离线加载。
        }
        try {
            run(dockerBinary, List.of("pull", reference),
                    timeout.compareTo(Duration.ofSeconds(45)) > 0 ? Duration.ofSeconds(45) : timeout);
        } catch (IllegalStateException exception) {
            LoggerFactory.getLogger(DockerCliUtil.class).warn("题目镜像准备失败, image={}", reference, exception);
            throw new ContainerRuntimeException("题目镜像不可用，请联系管理员预加载镜像或检查镜像仓库连接");
        }
    }

    /** 读取镜像声明的第一个 TCP 监听端口，避免让管理端手工填写容器端口。 */
    public static int inspectImagePort(String dockerBinary, String image, String tag, Duration timeout) {
        String json = run(dockerBinary, List.of("image", "inspect", image + ":" + tag), timeout);
        try {
            JsonNode exposed = MAPPER.readTree(json).path(0).path("Config").path("ExposedPorts");
            Iterator<String> names = exposed.fieldNames();
            int selected = Integer.MAX_VALUE;
            while (names.hasNext()) {
                String name = names.next();
                if (!name.endsWith("/tcp")) {
                    continue;
                }
                int parsed = Integer.parseInt(name.substring(0, name.indexOf('/')));
                selected = Math.min(selected, parsed);
            }
            if (selected == Integer.MAX_VALUE) {
                throw new IllegalStateException("镜像未声明可用的 TCP EXPOSE 端口");
            }
            return selected;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析镜像监听端口", exception);
        }
    }

    /** 仅在只读查询确认容器不存在时忽略删除错误，避免丢失仍在运行的实例记录。 */
    public static void removeQuietly(String dockerBinary, String containerName, Duration timeout) {
        try {
            run(dockerBinary, List.of("rm", "-f", containerName), timeout);
        } catch (IllegalStateException exception) {
            try {
                if (!containerExists(dockerBinary, containerName, timeout)) {
                    return;
                }
            } catch (IllegalStateException ignored) {
                // 无法确认删除结果时保留原记录，不把 Docker 故障当作容器不存在。
            }
            throw new ContainerRuntimeException("容器删除尚未确认，原实例已保留，请稍后重试");
        }
    }

    /** 按完整名称确认容器是否存在，避免名称前缀匹配误认其他队伍。 */
    public static boolean containerExists(String dockerBinary, String containerName, Duration timeout) {
        return run(dockerBinary, List.of("container", "ls", "--all", "--format", "{{.Names}}",
                "--filter", "name=" + containerName), timeout).lines().anyMatch(containerName::equals);
    }

    /** 启动容器并返回容器标识；宿主端口只绑定回环地址并随机分配。 */
    public static String startContainer(String dockerBinary, String containerName, String challengeId,
                                        String teamId, String network, String image, String tag,
                                        String flagEnvName, String flagValue, int containerPort,
                                        String memoryLimit, String cpuLimit, long pidsLimit,
                                        Duration timeout) {
        List<String> args = List.of(
                "run", "-d", "--pull=never",
                "--name", containerName,
                "--label", "tracequest.managed=true",
                "--label", "tracequest.challenge=" + challengeId,
                "--label", "tracequest.team=" + teamId,
                "--network", network,
                "--memory", memoryLimit,
                "--cpus", cpuLimit,
                "--pids-limit", String.valueOf(pidsLimit),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true",
                "-p", "127.0.0.1::" + containerPort,
                "-e", flagEnvName + "=" + flagValue,
                image + ":" + tag);
        return parseContainerId(run(dockerBinary, args, timeout));
    }

    /** 从 Docker 输出中提取完整容器标识，跳过内核告警等非标识文本。 */
    static String parseContainerId(String output) {
        return output.lines()
                .map(String::trim)
                .filter(line -> line.matches("[0-9a-fA-F]{12,64}"))
                .reduce((ignored, current) -> current)
                .orElseThrow(() -> new IllegalStateException("Docker 未返回有效容器标识"));
    }

    /** 查询容器状态、内部 IP 和随机宿主端口。 */
    public static ContainerInfo inspect(String dockerBinary, String containerId, String network,
                                        int containerPort, Duration timeout) {
        String json = run(dockerBinary, List.of("inspect", containerId), timeout);
        try {
            JsonNode root = MAPPER.readTree(json).path(0);
            String inspectedContainerId = root.path("Id").asText("");
            String state = root.path("State").path("Status").asText("");
            JsonNode networks = root.path("NetworkSettings").path("Networks");
            String containerIp = networks.path(network).path("IPAddress").asText(null);
            JsonNode bindings = root.path("NetworkSettings").path("Ports")
                    .path(containerPort + "/tcp");
            JsonNode binding = bindings.isArray() && !bindings.isEmpty() ? bindings.path(0) : null;
            String hostIp = binding == null ? null : binding.path("HostIp").asText(null);
            int hostPort = binding == null ? 0 : binding.path("HostPort").asInt(0);
            return new ContainerInfo(inspectedContainerId, state, containerIp, hostIp,
                    hostPort <= 0 ? null : hostPort);
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析 Docker 容器状态", exception);
        }
    }

    /** 等待容器内部端口可连接，避免启动命令返回时业务进程尚未就绪。 */
    public static boolean waitForPort(String host, int port, Duration timeout, Duration interval) {
        if (host == null || host.isBlank()) {
            return false;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                return true;
            } catch (Exception exception) {
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** 截断 Docker 错误输出，避免日志或接口响应无限增大。 */
    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "无输出";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
