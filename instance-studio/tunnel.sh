#!/usr/bin/env bash
# Exposes the local server through a Cloudflare quick tunnel.
# Prints a public https://<random>.trycloudflare.com URL. No account needed.
#
# Needs outbound port 7844. If the tunnel loops on "failed to dial a quic
# connection", the network is blocking UDP: retry with PROTOCOL=http2.
set -euo pipefail

PORT="${1:-8000}"
PROTOCOL="${PROTOCOL:-auto}"

if ! command -v cloudflared >/dev/null 2>&1; then
  echo "cloudflared introuvable." >&2
  echo "Installation: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/" >&2
  exit 1
fi

if ! curl -fsS "http://127.0.0.1:${PORT}/api/instances" >/dev/null 2>&1; then
  echo "Le serveur ne répond pas sur le port ${PORT}. Lance ./run.sh d'abord." >&2
  exit 1
fi

echo "Tunnel vers http://127.0.0.1:${PORT} — toute personne ayant l'URL pourra générer des instances."
if [ "$PROTOCOL" = "auto" ]; then
  exec cloudflared tunnel --url "http://127.0.0.1:${PORT}"
else
  exec cloudflared tunnel --protocol "$PROTOCOL" --url "http://127.0.0.1:${PORT}"
fi
