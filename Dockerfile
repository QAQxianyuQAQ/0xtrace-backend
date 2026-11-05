# 构建阶段：使用 Maven + JDK 25 编译打包
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
# Maven 缓存跨构建保留，避免 go-offline 下载与运行无关的插件依赖
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# 验证阶段：可独立构建 --target test，执行单元与 H2 集成回归。
FROM build AS test
RUN --mount=type=cache,target=/root/.m2 mvn -B -q test -Dtest.excluded.groups=none

# 运行阶段：仅保留 slim JRE 25 运行时
FROM eclipse-temurin:25-jre-noble
ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

# 题目运行时服务需要 Docker CLI 通过宿主机 Socket 管理受控容器
RUN apt-get update \
 && apt-get install -y --no-install-recommends docker.io \
 && rm -rf /var/lib/apt/lists/*

# 非 root 用户运行，降低容器逃逸风险
RUN groupadd --system --gid 10001 tracequest \
 && useradd --system --uid 10001 --gid tracequest --home-dir /app --shell /usr/sbin/nologin tracequest
WORKDIR /app
COPY --from=build --chown=tracequest:tracequest /workspace/target/backend-1.0.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080

# 容器健康由外部反向代理访问 /api/v1/health，镜像内不再重复探测
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
