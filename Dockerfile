# 构建阶段：使用 Maven + JDK 25 编译打包
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
# 先解析依赖，充分利用构建缓存
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行阶段：仅保留 slim JRE 25 运行时
FROM eclipse-temurin:25-jre-noble
ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

# 封面白名单格式必须实际解码，解码进程使用内存管道与资源上限。
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

# 非 root 用户运行，降低容器逃逸风险
RUN groupadd --system --gid 10001 tracequest \
 && useradd --system --uid 10001 --gid tracequest --home-dir /app --shell /usr/sbin/nologin tracequest
WORKDIR /app
COPY --from=build --chown=tracequest:tracequest /workspace/target/backend-1.0.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080

# 容器健康由 Kubernetes 的 /api/v1/health 探针负责，镜像内不再重复探测
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
