#!/usr/bin/env bash
# Pull the latest code and rebuild. Tablets reload the new UI automatically (no APK reinstall).
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"
git pull --ff-only
cd deploy
docker compose up -d --build
docker image prune -f >/dev/null
docker compose ps
