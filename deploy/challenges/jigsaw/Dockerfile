# 异形数独拼图 · 容器题镜像
#   构建：docker build -t sacc/jigsaw-sudoku:1.0 .
#   运行：docker run --rm -p 8080:8080 -e FLAG='SACC{...}' sacc/jigsaw-sudoku:1.0
# 镜像内只有题面 + 答案的 sha256，没有明文答案；flag 只从环境变量读。
FROM python:3.12-alpine

LABEL org.opencontainers.image.title="jigsaw-sudoku" \
      org.opencontainers.image.description="异形数独拼图 · 容器题（9 块碎片拼成 9x9 再解不规则数独）" \
      org.opencontainers.image.version="1.0"

# 目录结构与出题机一致：/srv/app 放服务端，/srv/data 放题面与答案哈希
WORKDIR /srv
COPY app/server.py      /srv/app/server.py
COPY app/index.html     /srv/app/index.html
COPY data/puzzle.json   /srv/data/puzzle.json
COPY data/answer.sha256 /srv/data/answer.sha256

ENV PORT=8080 \
    FLAG_ENV=FLAG \
    TTL_SECONDS=7200 \
    SUBMIT_LIMIT_PER_MIN=20 \
    PYTHONUNBUFFERED=1

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/api/health >/dev/null 2>&1 || exit 1

# 非 root 运行；服务端不写任何文件
RUN adduser -D -u 10001 ctf && chown -R ctf:ctf /srv
USER ctf

CMD ["python3", "-u", "/srv/app/server.py"]
