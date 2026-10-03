#!/usr/bin/env bash
set -euo pipefail
INSTALL_DIR=/usr/local/bin
ENV_FILE=/etc/serverwatch-agent.env
SERVICE_FILE=/etc/systemd/system/serverwatch-agent.service

if [[ $EUID -ne 0 ]]; then echo "Run as root." >&2; exit 1; fi
if [[ -z "${SERVERWATCH_TOKEN:-}" ]]; then
  read -r -s -p "ServerWatch agent token: " SERVERWATCH_TOKEN; echo
fi
if [[ -z "${SERVERWATCH_TOKEN:-}" ]]; then echo "Token is required." >&2; exit 1; fi

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) BIN_URL="https://github.com/SyedMdAbuHaider/ServerWatch-Android/releases/latest/download/serverwatch-agent-linux-amd64" ;;
  aarch64|arm64) BIN_URL="https://github.com/SyedMdAbuHaider/ServerWatch-Android/releases/latest/download/serverwatch-agent-linux-arm64" ;;
  *) echo "Unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

curl -fsSL "$BIN_URL" -o "$INSTALL_DIR/serverwatch-agent"
chmod 0755 "$INSTALL_DIR/serverwatch-agent"
cat > "$ENV_FILE" <<EOF
SERVERWATCH_ADDR=0.0.0.0:8787
SERVERWATCH_TOKEN=$SERVERWATCH_TOKEN
SERVERWATCH_SERVICES=${SERVERWATCH_SERVICES:-}
EOF
install -m 0644 agent/serverwatch-agent.service "$SERVICE_FILE"
systemctl daemon-reload
systemctl enable --now serverwatch-agent
systemctl --no-pager status serverwatch-agent
echo "ServerWatch agent installed on :8787"
