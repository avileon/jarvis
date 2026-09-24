#!/usr/bin/env bash
# Non-interactive install on a shared host that already has Docker + system Caddy.
# Usage: bash install-shared.sh /opt/jarvis   (repo already present there)
# Touches only: /opt/jarvis, docker project "jarvis", one appended block in /etc/caddy/Caddyfile (backup kept).
set -euo pipefail
DIR="${1:-/opt/jarvis}"
cd "$DIR/deploy"
if [ ! -f .env ]; then
  umask 077
  ADMIN_PASS=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)
  cat > .env <<ENV
DOMAIN=jarvis.vibit.co.il
JARVIS_PORT=3100
POSTGRES_PASSWORD=$(openssl rand -hex 24)
JARVIS_MASTER_KEY=$(openssl rand -hex 32)
SESSION_SECRET=$(openssl rand -hex 32)
ADMIN_USERNAME=avi
ADMIN_PASSWORD=$ADMIN_PASS
ENV
  echo "ADMIN_PASSWORD=$ADMIN_PASS"
fi
docker compose -f docker-compose.shared.yml up -d --build
for i in $(seq 1 60); do curl -fs http://127.0.0.1:3100/api/health >/dev/null && break; sleep 2; done
curl -fs http://127.0.0.1:3100/api/health && echo
if ! grep -q "jarvis.vibit.co.il" /etc/caddy/Caddyfile; then
  cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak-$(date +%Y%m%d-%H%M%S)"
  printf '\n' >> /etc/caddy/Caddyfile
  cat caddy-site.conf >> /etc/caddy/Caddyfile
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
  systemctl reload caddy
  echo "caddy reloaded"
fi
docker compose -f docker-compose.shared.yml ps
