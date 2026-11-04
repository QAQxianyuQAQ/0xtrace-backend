FROM node:22-alpine
WORKDIR /app
COPY gateway.mjs /app/gateway.mjs
COPY guide.js /app/guide.js
ENV NODE_ENV=production
EXPOSE 8090 8091 8092 8093 8094 8095 8096
CMD ["node", "/app/gateway.mjs"]
