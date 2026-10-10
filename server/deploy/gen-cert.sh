#!/usr/bin/env bash
# 生成同步服务的自签证书（在 NAS 上执行）
#
#   bash deploy/gen-cert.sh                 # 默认用 116.62.121.29 作为 SAN
#   bash deploy/gen-cert.sh 1.2.3.4         # 指定公网入口 IP
#
# 生成的证书只用于「公网入口 → NAS」这段链路的加密。
# App 端不校验证书（legado 的 HTTP 客户端信任任意证书），所以自签完全够用。
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="$SERVER_DIR/certs"
HOST_IP="${1:-116.62.121.29}"

if ! command -v openssl >/dev/null 2>&1; then
    echo "未找到 openssl，请先安装：apt install -y openssl" >&2
    exit 1
fi

mkdir -p "$CERT_DIR"

openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$CERT_DIR/key.pem" \
    -out "$CERT_DIR/cert.pem" \
    -subj "/CN=legado-sync" \
    -addext "subjectAltName=IP:${HOST_IP},DNS:legado-sync"

chmod 600 "$CERT_DIR/key.pem"

echo "证书已生成："
echo "  $CERT_DIR/cert.pem"
echo "  $CERT_DIR/key.pem"
echo
echo "把下面两行写进 $SERVER_DIR/config.json："
echo '  "tlsCert": "./certs/cert.pem",'
echo '  "tlsKey": "./certs/key.pem"'
