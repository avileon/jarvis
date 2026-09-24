#!/usr/bin/env bash
# JARVIS bootstrap for a fresh Ubuntu 22.04/24.04 server (run as root).
#   curl -fsSL https://raw.githubusercontent.com/avileon/jarvis/main/deploy/install.sh -o install.sh && bash install.sh
# Nothing is changed before you confirm the plan.
set -euo pipefail

REPO="${JARVIS_REPO:-https://github.com/avileon/jarvis.git}"
DIR="${JARVIS_DIR:-/opt/jarvis}"
DOMAIN="${DOMAIN:-jarvis.vibit.co.il}"

c() { printf '\033[1;36m%s\033[0m\n' "$*"; }
die() { printf '\033[1;31m%s\033[0m\n' "$*"; exit 1; }

[ "$(id -u)" = 0 ] || die "Run as root (sudo -i)."
. /etc/os-release
[ "${ID:-}" = ubuntu ] || c "Warning: tested on Ubuntu only (found $PRETTY_NAME)."

c "== Environment =="
echo "OS:       $PRETTY_NAME"
echo "Kernel:   $(uname -r)"
echo "CPU:      $(nproc) cores"
echo "Memory:   $(free -h | awk '/Mem:/{print $2" total, "$7" available"}')"
echo "Disk /:   $(df -h / | awk 'NR==2{print $4" free of "$2}')"
echo "Public IP: $(curl -fsS4 https://api.ipify.org 2>/dev/null || echo unknown)"
echo "Docker:   $(docker --version 2>/dev/null || echo 'not installed')"
echo "Node:     $(node -v 2>/dev/null || echo 'not installed')"
echo "Ports in use (80/443/3000):"; ss -ltnp 2>/dev/null | awk 'NR==1 || /:(80|443|3000) /' || true
echo "UFW:      $(ufw status 2>/dev/null | head -1 || echo 'not installed')"
echo

c "== Plan =="
cat <<PLAN
 1. Install Docker Engine + Compose plugin (if missing)
 2. Clone $REPO into $DIR
 3. Create $DIR/deploy/.env with random secrets (chmod 600), domain $DOMAIN
 4. If UFW is active: allow 22, 80, 443 (nothing else)
 5. docker compose up -d --build  (postgres + jarvis + caddy with Let's Encrypt HTTPS)
 Existing services and files are not modified or deleted.
PLAN
read -rp "Proceed? [y/N] " ok
[ "${ok,,}" = y ] || die "Aborted."

read -rp "Domain [$DOMAIN]: " d; DOMAIN="${d:-$DOMAIN}"
IP=$(curl -fsS4 https://api.ipify.org || true)
RES=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)
if [ -n "$IP" ] && [ "$RES" != "$IP" ]; then
  c "DNS: $DOMAIN resolves to '${RES:-nothing}', this server is $IP."
  echo "Create an A record: $DOMAIN -> $IP, then continue (HTTPS certificate needs it)."
  read -rp "Continue anyway? [y/N] " ok; [ "${ok,,}" = y ] || die "Fix DNS and re-run."
fi

if ! command -v docker >/dev/null; then
  c "Installing Docker…"
  apt-get update -q
  apt-get install -y -q ca-certificates curl git
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
command -v git >/dev/null || apt-get install -y -q git

if [ ! -d "$DIR/.git" ]; then
  c "Cloning repository…"
  if ! git clone -q "$REPO" "$DIR" 2>/dev/null; then
    echo "The repository is private. Create a fine-grained GitHub token with read-only 'Contents' access to avileon/jarvis."
    read -rsp "GitHub token: " GHT; echo
    git clone -q "https://x-access-token:${GHT}@github.com/avileon/jarvis.git" "$DIR"
    git -C "$DIR" remote set-url origin "$REPO"
    git -C "$DIR" config credential.helper store
    printf 'https://x-access-token:%s@github.com\n' "$GHT" > /root/.git-credentials; chmod 600 /root/.git-credentials
  fi
fi

ENV="$DIR/deploy/.env"
if [ ! -f "$ENV" ]; then
  ADMIN_PASS=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)
  umask 077
  cat > "$ENV" <<EOF
DOMAIN=$DOMAIN
POSTGRES_PASSWORD=$(openssl rand -hex 24)
JARVIS_MASTER_KEY=$(openssl rand -hex 32)
SESSION_SECRET=$(openssl rand -hex 32)
ADMIN_USERNAME=avi
ADMIN_PASSWORD=$ADMIN_PASS
EOF
  chmod 600 "$ENV"
  c "Admin login:  avi / $ADMIN_PASS   (change it in the admin panel → אבטחה)"
  echo "Back up $ENV — JARVIS_MASTER_KEY decrypts your stored API keys and tokens."
fi

if ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow 22/tcp >/dev/null; ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
  c "UFW: allowed 22, 80, 443."
fi

c "Building and starting…"
cd "$DIR/deploy"
docker compose up -d --build
sleep 5
docker compose ps
c "Done. Admin: https://$DOMAIN/admin    Station: https://$DOMAIN/"
echo "Update later with: bash $DIR/deploy/update.sh"
