# 适配层：在原镜像之上只替换入口脚本，让平台注入的 FLAG 生效
FROM sacc-source-flag:latest
USER root
COPY entrypoint.tq.py /app/entrypoint.py
RUN chmod 0755 /app/entrypoint.py
USER 10001:10001
