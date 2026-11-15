FROM alpine:latest

RUN apk add --no-cache curl ca-certificates && \
    curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 \
    -o /usr/local/bin/cloudflared && \
    chmod +x /usr/local/bin/cloudflared && \
    mkdir -p /etc/cloudflared

COPY deploy/railway/cloudflared/config.yml /etc/cloudflared/config.yml
COPY deploy/railway/cloudflared/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
