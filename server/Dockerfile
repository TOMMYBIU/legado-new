# 可选的镜像构建方式（把代码打进镜像，数据和证书仍走挂载）
#   docker build -t legado-sync .
#   docker run -d --name legado-sync --restart unless-stopped \
#     -p 127.0.0.1:8765:8765 \
#     -v "$(pwd)/config.json:/app/config.json" \
#     -v "$(pwd)/certs:/app/certs:ro" \
#     -v "$(pwd)/data:/app/data" \
#     legado-sync
FROM node:20-alpine

WORKDIR /app

COPY server.js package.json ./

ENV NODE_ENV=production
EXPOSE 8765

CMD ["node", "server.js"]
