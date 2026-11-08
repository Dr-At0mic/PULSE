#!/usr/bin/env bash
# PULSE - LAN system monitor with a sci-fi tablet console
# Copyright (C) 2026 Anandhu Suresh
# SPDX-License-Identifier: GPL-3.0-or-later
# Installs the PULSE hub to /opt/pulse-hub as a systemd service running as user 'pulse'.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo"; exit 1; }
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/pulse-hub

apt-get install -y python3-venv >/dev/null
id pulse &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin pulse
mkdir -p "$DEST"
install -m 644 "$SRC/hub.py" "$SRC/requirements.txt" "$DEST/"
[[ -d "$DEST/venv" ]] || python3 -m venv "$DEST/venv"
"$DEST/venv/bin/pip" install -q --upgrade pip
"$DEST/venv/bin/pip" install -q -r "$DEST/requirements.txt"
if [[ ! -f "$DEST/config.yaml" ]]; then
  TOKEN=$(python3 -c "import secrets; print(secrets.token_urlsafe(24))")
  sed -e "s/CHANGE-ME-agent-token/$TOKEN/" -e "s/CHANGE-ME-client-token/$TOKEN/" \
      "$SRC/config.yaml.example" > "$DEST/config.yaml"
  echo ">> generated token: $TOKEN  (use it in every agent config and the tablet)"
  echo ">> edit $DEST/config.yaml and set 'host' to this server's LAN IP"
fi
chown -R root:pulse "$DEST"; chmod 640 "$DEST/config.yaml"
install -m 644 "$SRC/pulse-hub.service" /etc/systemd/system/pulse-hub.service
systemctl daemon-reload
systemctl enable --now pulse-hub
systemctl --no-pager status pulse-hub | head -5
