#!/bin/sh
set -e

# TUNNEL_CREDS 환경변수에서 credentials 파일 생성
echo "$TUNNEL_CREDS" > /etc/cloudflared/creds.json

exec cloudflared tunnel --config /etc/cloudflared/config.yml run
