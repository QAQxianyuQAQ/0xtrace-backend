FROM node:20-alpine

LABEL org.opencontainers.image.title="sacc-morse-md5" \
      org.opencontainers.image.description="SACC 趣味闯关：摩斯电码 + 源码里的错误 md5" \
      org.opencontainers.image.version="1.0.0"

ENV NODE_ENV=production \
    PORT=8080

WORKDIR /app

COPY --chown=node:node src/server.js ./server.js
COPY --chown=node:node src/entrypoint.sh ./entrypoint.sh
COPY --chown=node:node src/read-flag.sh ./read-flag.sh

USER node

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

ENTRYPOINT ["sh", "/app/entrypoint.sh"]
